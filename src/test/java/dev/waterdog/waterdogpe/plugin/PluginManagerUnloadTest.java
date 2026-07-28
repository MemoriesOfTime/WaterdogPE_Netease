/*
 * Copyright 2026 WaterdogTEAM
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
import dev.waterdog.waterdogpe.event.EventManager;
import dev.waterdog.waterdogpe.logger.MainLogger;
import dev.waterdog.waterdogpe.scheduler.WaterdogScheduler;
import dev.waterdog.waterdogpe.utils.config.proxy.ProxyConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link PluginManager#unloadPlugin(String)}: it must disable the plugin, drop every
 * internal reference (plugin map, class-loader map, global class cache, enabled order),
 * delegate cleanup to the event manager and scheduler, and refuse when dependents exist.
 *
 * <p>State is injected reflectively into the package-private/private maps of PluginManager
 * (test lives in the same package), avoiding the need for a real plugin jar.</p>
 */
public class PluginManagerUnloadTest {

    @TempDir
    Path pluginFolder;

    private ProxyServer proxy;
    private EventManager eventManager;
    private WaterdogScheduler scheduler;
    private PluginManager pluginManager;

    @BeforeEach
    void setUp() throws Exception {
        this.proxy = mock(ProxyServer.class);
        this.eventManager = mock(EventManager.class);
        this.scheduler = mock(WaterdogScheduler.class);
        MainLogger logger = mock(MainLogger.class);
        ProxyConfig config = mock(ProxyConfig.class);

        when(this.proxy.getEventManager()).thenReturn(this.eventManager);
        when(this.proxy.getScheduler()).thenReturn(this.scheduler);
        when(this.proxy.getLogger()).thenReturn(logger);
        when(this.proxy.getConfiguration()).thenReturn(config);
        when(this.proxy.getPluginPath()).thenReturn(this.pluginFolder);
        when(config.getIdleThreads()).thenReturn(1);

        // PluginManager constructor calls loadPluginsInside(pluginPath); tempdir is empty so the
        // manager starts with no plugins.
        this.pluginManager = new PluginManager(this.proxy);
    }

    @Test
    void unloadPluginRemovesAllInternalReferences() throws Exception {
        Plugin plugin = injectPlugin("Demo", "1.0", null);
        ClassLoader loader = plugin.getClass().getClassLoader(); // system loader in test JVM

        // Seed the class-loader map so we can assert it is cleared. PluginManager keys name maps
        // by the lower-cased name (see PluginManager#norm), so inject with the same convention.
        PluginClassLoader pcl = mock(PluginClassLoader.class);
        setField(this.pluginManager, "pluginClassLoaders",
                injectIntoMap(field(this.pluginManager, "pluginClassLoaders"), "demo", pcl));

        // Seed cachedClasses with an entry whose ClassLoader equals the plugin's loader.
        Class<?> seededClass = plugin.getClass();
        setField(this.pluginManager, "cachedClasses",
                injectIntoMap(field(this.pluginManager, "cachedClasses"), "demo.Foo", seededClass));

        assertTrue(this.pluginManager.getPluginByName("Demo") != null);
        assertTrue(getCachedClasses(this.pluginManager).containsValue(seededClass));

        boolean ok = this.pluginManager.unloadPlugin("Demo");
        assertTrue(ok, "unload should succeed when no dependents exist");

        assertNull(this.pluginManager.getPluginByName("Demo"), "plugin must be removed from pluginMap");
        assertFalse(this.pluginManager.getPluginClassLoaders().stream()
                        .anyMatch(cl -> cl == pcl), "plugin class loader must be removed");
        assertFalse(plugin.isEnabled(), "plugin must be disabled");
        assertFalse(getCachedClasses(this.pluginManager).containsValue(seededClass),
                "cachedClasses entries owned by the plugin's loader must be removed");
    }

    @Test
    void unloadPluginRefusesWhenDependentsExist() throws Exception {
        Plugin target = injectPlugin("Lib", "1.0", null);
        Plugin dependent = injectPlugin("App", "1.0", List.of("Lib"));

        boolean ok = this.pluginManager.unloadPlugin("Lib");
        assertFalse(ok, "unload must be refused while a dependent is loaded");

        // State must be untouched
        assertEquals(target, this.pluginManager.getPluginByName("Lib"));
        assertEquals(dependent, this.pluginManager.getPluginByName("App"));
    }

    @Test
    void unloadUnknownPluginWithDependentsRefused() throws Exception {
        // Even if the target is not loaded, a dependent config still makes reload unsafe.
        injectPlugin("App", "1.0", List.of("Ghost"));
        boolean ok = this.pluginManager.unloadPlugin("Ghost");
        assertFalse(ok);
    }

    @Test
    void unloadUnknownPluginWithoutDependentsSucceeds() {
        boolean ok = this.pluginManager.unloadPlugin("NoSuchPlugin");
        assertTrue(ok, "unloading a non-existent plugin with no dependents is a no-op success");
    }

    @Test
    void unloadCallsOnDisableThenOnUnload() throws Exception {
        int[] calls = {0};
        Plugin plugin = injectPlugin("Tracked", "1.0", null, new Plugin() {
            @Override
            public void onEnable() {}
            @Override
            public void onDisable() { calls[0] += 10; }
            @Override
            public void onUnload() { calls[0] += 1; }
        });
        // force enabled so setEnabled(false) path runs
        setEnabledField(plugin, true);

        assertTrue(this.pluginManager.unloadPlugin("Tracked"));
        assertEquals(11, calls[0], "onDisable then onUnload must both run (10 + 1)");
    }

    @Test
    void getPluginByNameIsCaseInsensitive() throws Exception {
        Plugin plugin = injectPlugin("MyPlugin", "1.0", null);
        assertSame(plugin, this.pluginManager.getPluginByName("MyPlugin"));
        assertSame(plugin, this.pluginManager.getPluginByName("myplugin"));
        assertSame(plugin, this.pluginManager.getPluginByName("MYPLUGIN"));
        assertSame(plugin, this.pluginManager.getPluginByName("mYpLuGiN"));
        assertNull(this.pluginManager.getPluginByName("other"));
    }

    @Test
    void unloadPluginIsCaseInsensitive() throws Exception {
        Plugin plugin = injectPlugin("CamelCase", "1.0", null);
        // Unload using a different casing than the declared name
        boolean ok = this.pluginManager.unloadPlugin("camelcase");
        assertTrue(ok, "unload must succeed regardless of the casing passed in");
        assertNull(this.pluginManager.getPluginByName("CamelCase"),
                "plugin must be removed after case-insensitive unload");
    }

    @Test
    void findDependentsIsCaseInsensitive() throws Exception {
        // The target is declared "Lib"; a dependent lists it as "lib" in its depends.
        injectPlugin("Lib", "1.0", null);
        injectPlugin("App", "1.0", List.of("lib"));

        // unloadPlugin("Lib") must detect the dependent despite the casing mismatch.
        boolean ok = this.pluginManager.unloadPlugin("Lib");
        assertFalse(ok, "unload must be refused when a dependent references the name in different case");
    }

    // ---------- helpers ----------

    /** Inject a plugin instance into pluginMap + enabledOrder using reflection. */
    @SuppressWarnings("unchecked")
    private Plugin injectPlugin(String name, String version, List<String> depends) throws Exception {
        return injectPlugin(name, version, depends, null);
    }

    @SuppressWarnings("unchecked")
    private Plugin injectPlugin(String name, String version, List<String> depends, Plugin override) throws Exception {
        PluginYAML desc = new PluginYAML();
        desc.name = name;
        desc.version = version;
        desc.author = "test";
        desc.main = "test." + name;
        desc.depends = depends == null ? Collections.emptyList() : depends;

        Plugin plugin = override != null ? override : new Plugin() {
            @Override public void onEnable() {}
        };
        // Bypass Plugin.init(): it calls buildLogger() and proxy.getPluginPath(), both of which
        // require a fully wired Log4j/proxy setup. The unload path only needs `description`.
        setField(plugin, "description", desc);

        Object pluginMap = field(this.pluginManager, "pluginMap");
        // PluginManager keys all name maps by the lower-cased name (see PluginManager#norm).
        String key = name.toLowerCase(java.util.Locale.ROOT);
        pluginMap.getClass().getMethod("put", Object.class, Object.class).invoke(pluginMap, key, plugin);

        List<Plugin> enabledOrder = (List<Plugin>) field(this.pluginManager, "enabledOrder");
        enabledOrder.add(plugin);
        return plugin;
    }

    private static void setEnabledField(Plugin plugin, boolean value) throws Exception {
        Field f = Plugin.class.getDeclaredField("enabled");
        f.setAccessible(true);
        f.setBoolean(plugin, value);
    }

    private static Object field(Object owner, String name) throws Exception {
        return findField(owner.getClass(), name).get(owner);
    }

    @SuppressWarnings("unchecked")
    private static <K, V> java.util.Map<K, V> injectIntoMap(Object map, K key, V value) {
        ((java.util.Map<K, V>) map).put(key, value);
        return (java.util.Map<K, V>) map;
    }

    private static void setField(Object owner, String name, Object value) throws Exception {
        findField(owner.getClass(), name).set(owner, value);
    }

    /** Walk the class hierarchy so fields declared in superclasses (e.g. Plugin.description) resolve. */
    private static Field findField(Class<?> type, String name) throws Exception {
        Class<?> t = type;
        while (t != null) {
            try {
                Field f = t.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                t = t.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " on " + type.getName());
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Class<?>> getCachedClasses(PluginManager pm) throws Exception {
        return (java.util.Map<String, Class<?>>) field(pm, "cachedClasses");
    }
}
