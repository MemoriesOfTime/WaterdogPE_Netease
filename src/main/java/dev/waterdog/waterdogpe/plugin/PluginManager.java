/*
 * Copyright 2022 WaterdogTEAM
 * Licensed under the GNU General Public License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.waterdog.waterdogpe.plugin;

import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.utils.exceptions.PluginChangeStateException;
import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.CustomClassLoaderConstructor;
import org.yaml.snakeyaml.representer.Representer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

public class PluginManager {

    public static final Yaml yamlLoader;
    static {
        DumperOptions dumperOptions = new DumperOptions();
        Representer representer = new Representer(dumperOptions);
        representer.getPropertyUtils().setSkipMissingProperties(true);
        LoaderOptions loaderOptions = new LoaderOptions();
        yamlLoader = new Yaml(new CustomClassLoaderConstructor(PluginManager.class.getClassLoader(), loaderOptions), representer, dumperOptions);
    }

    @Getter
    private final ProxyServer proxy;
    private final PluginLoader pluginLoader;

    protected final Object2ObjectMap<String, PluginClassLoader> pluginClassLoaders = new Object2ObjectArrayMap<>();
    private final Object2ObjectMap<String, Plugin> pluginMap = new Object2ObjectArrayMap<>();
    private final Object2ObjectMap<String, Class<?>> cachedClasses = new Object2ObjectArrayMap<>();
    /**
     * Plugin name → on-disk jar path, populated in {@link #registerClassLoader} and consulted by
     * {@link #reloadPlugin(String)} so a single-plugin reload no longer has to re-scan (and re-parse
     * the YAML of) every jar in the plugin folder.
     */
    private final Object2ObjectMap<String, Path> pluginJarPaths = new Object2ObjectArrayMap<>();

    private final List<Pair<PluginYAML, Path>> pluginsToLoad = new ObjectArrayList<>();
    private final List<Plugin> enabledOrder = new ObjectArrayList<>();

    public PluginManager(ProxyServer proxy) {
        this.proxy = proxy;
        this.pluginLoader = new PluginLoader(this);
        try {
            this.loadPluginsInside(this.proxy.getPluginPath());
        } catch (IOException e) {
            this.proxy.getLogger().error("Error while filtering plugin files", e);
        }
    }

    private void loadPluginsInside(Path folderPath) throws IOException {
        Comparator<PluginYAML> comparator = (o1, o2) -> {
            if (o2.getName().equalsIgnoreCase(o1.getName())) {
                return 0;
            }
            if (o2.getDepends() == null) {
                return 1;
            }
            // Dependency names in plugin.yml may not match the depended plugin's declared name
            // case-for-case; compare case-insensitively so load order is correct.
            String o1Name = o1.getName();
            boolean depends = o2.getDepends().stream()
                    .anyMatch(d -> d != null && d.equalsIgnoreCase(o1Name));
            return depends ? -1 : 1;
        };

        Map<PluginYAML, Path> plugins = new TreeMap<>(comparator);
        try (Stream<Path> stream = Files.walk(folderPath)){
            stream.filter(Files::isRegularFile).filter(PluginLoader::isJarFile).forEach(jarPath -> {
                PluginYAML config = this.loadPluginConfig(jarPath);
                if (config != null) {
                    plugins.put(config, jarPath);
                }
            });
        }
        plugins.forEach(this::registerClassLoader);
    }

    private PluginYAML loadPluginConfig(Path path) {
        if (!Files.isRegularFile(path) || !PluginLoader.isJarFile(path)) {
            this.proxy.getLogger().warning("Cannot load plugin: Provided file is no jar file: " + path.getFileName());
            return null;
        }

        File pluginFile = path.toFile();
        if (!pluginFile.exists()) {
            return null;
        }
        return this.pluginLoader.loadPluginData(pluginFile, yamlLoader);
    }

    private PluginClassLoader registerClassLoader(PluginYAML config, Path path) {
        if (this.getPluginByName(config.getName()) != null) {
            this.proxy.getLogger().warning("Plugin is already loaded: {}", config.getName());
            return null;
        }

        PluginClassLoader classLoader = this.pluginLoader.loadClassLoader(config, path.toFile());
        if (classLoader != null) {
            String key = this.norm(config.getName());
            this.pluginClassLoaders.put(key, classLoader);
            this.pluginJarPaths.put(key, path);
            this.pluginsToLoad.add(ObjectObjectImmutablePair.of(config, path));
            this.proxy.getLogger().debug("Loaded class loader from {}", path.getFileName());
        }
        return classLoader;
    }

    public void loadAllPlugins() {
        for (Pair<PluginYAML, Path> pair : this.pluginsToLoad) {
            this.loadPlugin(pair.key(), pair.value());
        }
        this.pluginsToLoad.clear();
    }

    public Plugin loadPlugin(PluginYAML config, Path path) {
        File pluginFile = path.toFile();
        if (this.getPluginByName(config.getName()) != null) {
            this.proxy.getLogger().warning("Plugin is already loaded: {}", config.getName());
            return null;
        }

        PluginClassLoader classLoader = this.pluginClassLoaders.get(this.norm(config.getName()));
        if (classLoader == null) {
            classLoader = this.registerClassLoader(config, path);
        }

        if (classLoader == null) {
            return null;
        }

        Plugin plugin = this.pluginLoader.loadPluginJAR(config, pluginFile, classLoader);
        if (plugin == null) {
            return null;
        }

        try {
            plugin.onStartup();
        } catch (Exception e) {
            this.proxy.getLogger().error("Failed to load plugin {}!", config.getName(), e);
            return null;
        }

        this.proxy.getLogger().info("Loaded plugin {} successfully! (version={}, author={})", config.getName(), config.getVersion(), config.getAuthor());
        this.pluginMap.put(this.norm(config.getName()), plugin);
        return plugin;
    }

    public int enableAllPlugins() {
        LinkedList<Plugin> failed = new LinkedList<>();
        int enabled = 0;

        for (Plugin plugin : this.pluginMap.values()) {
            if (this.enablePlugin(plugin, null)) {
                enabled++;
            } else {
                failed.add(plugin);
            }
        }

        if (failed.isEmpty()) {
            return enabled;
        }

        StringBuilder builder = new StringBuilder("§cFailed to load plugins: §e");
        while (failed.peek() != null) {
            Plugin plugin = failed.poll();
            builder.append(plugin.getName());
            if (failed.peek() != null) {
                builder.append(", ");
            }
        }
        this.proxy.getLogger().warning(builder.toString());
        return enabled;
    }

    public boolean enablePlugin(Plugin plugin, String parent) {
        if (plugin.isEnabled()) return true;
        String pluginName = plugin.getName();

        if (plugin.getDescription().getDepends() != null) {
            for (String depend : plugin.getDescription().getDepends()) {
                if (parent != null && this.norm(depend).equals(this.norm(parent))) {
                    this.proxy.getLogger().warning("§cCan not enable plugin " + pluginName + " circular dependency " + parent + "!");
                    return false;
                }

                Plugin dependPlugin = this.getPluginByName(depend);
                if (dependPlugin == null) {
                    this.proxy.getLogger().warning("§cCan not enable plugin " + pluginName + " missing dependency " + depend + "!");
                    return false;
                }

                if (!dependPlugin.isEnabled() && !this.enablePlugin(dependPlugin, pluginName)) {
                    return false;
                }
            }
        }

        try {
            plugin.setEnabled(true);
        } catch (PluginChangeStateException e) {
            this.proxy.getLogger().error(e.getMessage(), e.getCause());
            try {
                plugin.setEnabled(false);
            } catch (PluginChangeStateException disableException) {
                this.proxy.getLogger().error(disableException.getMessage(), disableException.getCause());
            }
            return false;
        }
        this.enabledOrder.add(plugin);
        return true;
    }

    public void disableAllPlugins() {
        // Disable in reverse of enable order so dependents shut down before their dependencies
        List<Plugin> order = new ObjectArrayList<>(this.enabledOrder);
        for (Plugin plugin : this.pluginMap.values()) {
            if (plugin.isEnabled() && !order.contains(plugin)) {
                order.add(plugin); // enabled outside enablePlugin; disable last
            }
        }

        for (int i = order.size() - 1; i >= 0; i--) {
            Plugin plugin = order.get(i);
            this.proxy.getLogger().info("Disabling plugin " + plugin.getName() + "!");
            try {
                plugin.setEnabled(false);
            } catch (PluginChangeStateException e) {
                this.proxy.getLogger().error(e.getMessage(), e.getCause());
            }
        }
        this.enabledOrder.clear();
    }

    /**
     * Fully unload a single plugin: disable it, release every reference the proxy holds to its
     * classes, and close its ClassLoader so the jar can be GC'd. Intended for hot-reload.
     *
     * <p>Refuses to unload if any other plugin currently depends on it: dependents would still
     * hold a strong reference to the old Class&lt;?&gt; of every class they imported from the
     * target, and reloading would leave the proxy with two versions of the same classes — a
     * {@link ClassCastException} waiting to happen. Reload dependents first, or use
     * {@link #reloadAllPlugins()}.</p>
     *
     * @param name case-sensitive plugin name
     * @return true if the plugin was unloaded (or was not loaded to begin with and had no
     *         dependents); false if unload was refused due to dependents
     */
    public boolean unloadPlugin(String name) {
        name = this.norm(name);
        Plugin plugin = this.pluginMap.get(name);
        if (plugin == null) {
            // Not loaded — still need the dependency check so callers can rely on the invariant.
            List<String> dependents = this.findDependents(name);
            if (!dependents.isEmpty()) {
                this.proxy.getLogger().warning("Cannot unload '{}': depended on by [{}]. Unload those first.",
                        name, String.join(", ", dependents));
                return false;
            }
            return true;
        }

        // Refuse if any other plugin depends on this one (uses the old classes by reference)
        List<String> dependents = this.findDependents(name);
        if (!dependents.isEmpty()) {
            this.proxy.getLogger().warning("Cannot unload '{}': depended on by [{}]. Unload those first.",
                    name, String.join(", ", dependents));
            return false;
        }

        ClassLoader loader = plugin.getClass().getClassLoader();

        // 1. Disable first so onDisable() runs while everything is still wired up
        if (plugin.isEnabled()) {
            try {
                plugin.setEnabled(false);
            } catch (PluginChangeStateException e) {
                this.proxy.getLogger().error(e.getMessage(), e.getCause());
            }
        }

        // 2. Plugin-defined cleanup hook (external resources not bound to the enable cycle)
        try {
            plugin.onUnload();
        } catch (Throwable t) {
            this.proxy.getLogger().error("Plugin {} threw in onUnload(); continuing with unload", name, t);
        }

        // 3. Drop every strong reference the proxy holds to this plugin's classes
        this.pluginMap.remove(name);
        this.enabledOrder.remove(plugin);

        // cachedClasses is the cross-plugin global cache that short-circuits findClass();
        // leaving entries in would make newly loaded plugins resolve to the OLD class.
        this.cachedClasses.values().removeIf(clazz -> clazz != null && clazz.getClassLoader() == loader);

        // 4. Release event subscriptions and scheduled tasks owned by this plugin's classes
        int subs = this.proxy.getEventManager().unsubscribe(loader);
        int tasks = this.proxy.getScheduler().cancelTasksByClassLoader(loader);

        // 5. Remove the Log4j LoggerConfig this plugin registered in Plugin.buildLogger()
        this.removePluginLoggers(plugin);

        // 6. Drop the ClassLoader entry and close it so the jar file handle is released
        this.pluginClassLoaders.remove(name);
        this.pluginJarPaths.remove(name);
        if (loader instanceof PluginClassLoader pcl) {
            try {
                pcl.close();
            } catch (IOException e) {
                this.proxy.getLogger().warning("Failed to close class loader for plugin {}", name, e);
            }
        }

        this.proxy.getLogger().info("Unloaded plugin {} (removed {} event subscription(s), {} scheduled task(s)). " +
                "Classes will be collected on the next GC.", name, subs, tasks);
        return true;
    }

    /**
     * Reload a single plugin from its jar on disk. Equivalent to {@link #unloadPlugin(String)}
     * followed by a fresh load + enable.
     *
     * @param name plugin name (matched case-insensitively)
     * @return the new Plugin instance, or null if unload failed or the jar could not be loaded
     */
    public Plugin reloadPlugin(String name) {
        final String normalizedName = this.norm(name);
        name = normalizedName;
        Plugin existing = this.pluginMap.get(name);
        if (existing != null) {
            if (!this.unloadPlugin(name)) {
                return null;
            }
        }

        // Fast path: the jar path was recorded when the ClassLoader was registered, so a hot
        // reload of a plugin that loaded successfully at boot can skip rescanning the folder.
        PluginYAML config = null;
        Path jarPath = this.pluginJarPaths.get(name);
        if (jarPath != null && Files.isRegularFile(jarPath)) {
            config = this.loadPluginConfig(jarPath);
        }

        // Fallback: rescan the plugin folder. Covers plugins whose jar exists on disk but never
        // registered a ClassLoader (e.g. they failed to load at boot), and any path staleness.
        if (config == null) {
            Path scanned = null;
            try (Stream<Path> stream = Files.walk(this.proxy.getPluginPath())) {
                var opt = stream.filter(Files::isRegularFile)
                        .filter(PluginLoader::isJarFile)
                        .map(p -> {
                            PluginYAML c = this.loadPluginConfig(p);
                            return c != null ? new ObjectObjectImmutablePair<PluginYAML, Path>(c, p) : null;
                        })
                        .filter(Objects::nonNull)
                        .filter(pair -> normalizedName.equalsIgnoreCase(pair.key().getName()))
                        .findFirst();
                if (opt.isPresent()) {
                    config = opt.get().key();
                    scanned = opt.get().value();
                }
            } catch (IOException e) {
                this.proxy.getLogger().error("Error while scanning plugin folder for {}", name, e);
                return null;
            }
            jarPath = scanned;
        }

        if (config == null || jarPath == null) {
            this.proxy.getLogger().error("Cannot reload plugin {}: jar not found in plugin folder", name);
            return null;
        }

        Plugin plugin = this.loadPlugin(config, jarPath);
        if (plugin == null) {
            this.proxy.getLogger().error("Failed to reload plugin {}: loadPlugin returned null", name);
            return null;
        }

        if (!this.enablePlugin(plugin, null)) {
            this.proxy.getLogger().error("Failed to reload plugin {}: enablePlugin returned false", name);
            return null;
        }

        this.proxy.getLogger().info("Reloaded plugin {} successfully! (version={}, author={})",
                config.getName(), config.getVersion(), config.getAuthor());
        return plugin;
    }

    /**
     * Reload every plugin. Unloads in dependents-first order (a plugin is unloaded only after
     * every other loaded plugin that depends on it), then re-scans the plugin folder and
     * loads/enables everything from scratch. Useful when several plugins form a dependency
     * cluster and reloading one alone is impossible.
     *
     * @return number of plugins successfully enabled, or -1 if the unload or rescan phase
     *         was interrupted by an unexpected error
     */
    public int reloadAllPlugins() {
        // Compute an unload order in which each plugin comes after its dependents. We tear
        // everything down together via unloadPluginUnchecked (which skips the dependents
        // check), but honouring dependency direction makes onDisable fire in a saner order
        // and keeps the contract honest if onUnload ever gains ordering-sensitive semantics.
        List<Plugin> order = this.computeDependentsFirstUnloadOrder();
        for (Plugin plugin : order) {
            String name = plugin.getName();
            // Bypass the dependents check by unloading directly from internal state.
            if (!this.unloadPluginUnchecked(name, plugin)) {
                this.proxy.getLogger().error("Unexpected failure during unload of {}; aborting reload-all", name);
                return -1;
            }
        }

        // Re-scan and reload
        try {
            this.loadPluginsInside(this.proxy.getPluginPath());
        } catch (IOException e) {
            this.proxy.getLogger().error("Error while scanning plugin folder during reload-all", e);
            return -1;
        }
        this.loadAllPlugins();
        return this.enableAllPlugins();
    }

    /**
     * Order all currently loaded plugins so that each plugin appears <em>after</em> every other
     * loaded plugin that depends on it (dependents first). Used by {@link #reloadAllPlugins()}.
     *
     * <p>Each round emits plugins that no remaining plugin depends on; if no plugin qualifies
     * (a cyclic dependency edge), the survivors are appended as-is so the unload still makes
     * progress instead of looping forever.</p>
     */
    private List<Plugin> computeDependentsFirstUnloadOrder() {
        List<Plugin> remaining = new ObjectArrayList<>(this.pluginMap.values());
        List<Plugin> ordered = new ObjectArrayList<>();
        while (!remaining.isEmpty()) {
            boolean progress = false;
            for (Iterator<Plugin> it = remaining.iterator(); it.hasNext(); ) {
                Plugin candidate = it.next();
                List<String> candidateDependents = this.findDependents(candidate.getName());
                boolean hasPendingDependent = false;
                for (Plugin other : remaining) {
                    if (other != candidate && candidateDependents.contains(other.getName())) {
                        hasPendingDependent = true;
                        break;
                    }
                }
                if (!hasPendingDependent) {
                    ordered.add(candidate);
                    it.remove();
                    progress = true;
                }
            }
            if (!progress) {
                // Defensive: a dependency cycle (or a dependent we cannot resolve) would otherwise
                // loop forever; flush the rest so the caller still unloads everything.
                ordered.addAll(remaining);
                remaining.clear();
            }
        }
        return ordered;
    }

    /**
     * Internal unload that skips the dependents check, used by {@link #reloadAllPlugins()} which
     * already unloads in dependents-first order.
     */
    private boolean unloadPluginUnchecked(String name, Plugin plugin) {
        name = this.norm(name);
        ClassLoader loader = plugin.getClass().getClassLoader();

        if (plugin.isEnabled()) {
            try {
                plugin.setEnabled(false);
            } catch (PluginChangeStateException e) {
                this.proxy.getLogger().error(e.getMessage(), e.getCause());
            }
        }
        try {
            plugin.onUnload();
        } catch (Throwable t) {
            this.proxy.getLogger().error("Plugin {} threw in onUnload(); continuing with unload", name, t);
        }

        this.pluginMap.remove(name);
        this.enabledOrder.remove(plugin);
        this.cachedClasses.values().removeIf(clazz -> clazz != null && clazz.getClassLoader() == loader);
        this.proxy.getEventManager().unsubscribe(loader);
        this.proxy.getScheduler().cancelTasksByClassLoader(loader);
        this.removePluginLoggers(plugin);

        this.pluginClassLoaders.remove(name);
        this.pluginJarPaths.remove(name);
        if (loader instanceof PluginClassLoader pcl) {
            try {
                pcl.close();
            } catch (IOException e) {
                this.proxy.getLogger().warning("Failed to close class loader for plugin {}", name, e);
            }
        }
        return true;
    }

    /**
     * @return names of currently loaded plugins whose {@code depends} list contains {@code target}.
     *         Used to decide whether a hot-reload is safe.
     */
    private List<String> findDependents(String target) {
        // target arrives already normalized to lower case by callers; compare case-insensitively
        // against the depends list (which holds the original casing from plugin.yml).
        List<String> dependents = new ObjectArrayList<>();
        for (Plugin other : this.pluginMap.values()) {
            List<String> deps = other.getDescription().getDepends();
            if (deps != null && deps.stream().anyMatch(d -> d != null && this.norm(d).equals(target))) {
                dependents.add(other.getName());
            }
        }
        return dependents;
    }

    /**
     * Remove the two {@link LoggerConfig}s that {@link Plugin#buildLogger()} registered
     * (one keyed by plugin name, one by main-class FQCN) so the Log4j configuration stops
     * holding strong references to the unloaded plugin's class objects.
     */
    private void removePluginLoggers(Plugin plugin) {
        try {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            org.apache.logging.log4j.core.config.Configuration config = context.getConfiguration();
            // Configuration#getLoggers returns the live logger map; removeLogger returns void.
            Map<String, LoggerConfig> loggers = config.getLoggers();
            // Mirror Plugin.buildLogger(), which keys the second logger by the main class binary
            // name (Class#getName() never returns null, unlike getCanonicalName() for anonymous
            // or local classes — keeping both sides on getName() avoids leaking a "null"-keyed logger).
            String mainClass = plugin.getClass().getName();
            boolean changed = false;
            if (loggers.containsKey(plugin.getName())) {
                config.removeLogger(plugin.getName());
                changed = true;
            }
            if (loggers.containsKey(mainClass)) {
                config.removeLogger(mainClass);
                changed = true;
            }
            if (changed) {
                context.updateLoggers();
            }
        } catch (Throwable t) {
            // Log4j config errors must never block a plugin unload
            this.proxy.getLogger().warning("Failed to remove Log4j loggers for plugin {}", plugin.getName(), t);
        }
    }

    public Class<?> getClassFromCache(String className) {
        Class<?> clazz = this.cachedClasses.get(className);
        if (clazz != null) {
            return clazz;
        }

        for (PluginClassLoader loader : this.pluginClassLoaders.values()) {
            try {
                if ((clazz = loader.findClass(className, false)) != null) {
                    return clazz;
                }
            } catch (ClassNotFoundException e) {
                //ignore
            }
        }
        return null;
    }

    protected void cacheClass(String className, Class<?> clazz) {
        this.cachedClasses.putIfAbsent(className, clazz);
    }

    public Map<String, Plugin> getPluginMap() {
        return Collections.unmodifiableMap(this.pluginMap);
    }

    public Collection<Plugin> getPlugins() {
        return Collections.unmodifiableCollection(this.pluginMap.values());
    }

    public Collection<PluginClassLoader> getPluginClassLoaders() {
        return Collections.unmodifiableCollection(this.pluginClassLoaders.values());
    }

    public Plugin getPluginByName(String pluginName) {
        return this.pluginMap.getOrDefault(this.norm(pluginName), null);
    }

    /**
     * Normalize a plugin name for use as a map key or comparison. Plugin names are treated
     * case-insensitively everywhere they are looked up (commands, dependency resolution, reload),
     * so all three name-indexed maps (pluginMap, pluginClassLoaders, pluginJarPaths) are keyed by
     * the lower-cased form. {@link Plugin#getName()} still returns the original casing for display.
     *
     * @param name the raw plugin name, may be null
     * @return the lower-cased name, or null if the input was null
     */
    private String norm(String name) {
        return name == null ? null : name.toLowerCase(java.util.Locale.ROOT);
    }

}
