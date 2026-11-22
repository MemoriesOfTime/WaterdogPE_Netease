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

package dev.waterdog.waterdogpe.utils;

import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.event.defaults.PlayerDisconnectedEvent;
import dev.waterdog.waterdogpe.event.defaults.TransferCompleteEvent;
import dev.waterdog.waterdogpe.network.nethernet.NetherNetProperties;
import dev.waterdog.waterdogpe.network.serverinfo.ServerInfo;
import dev.waterdog.waterdogpe.network.serverinfo.ServerInfoMap;
import dev.waterdog.waterdogpe.plugin.PluginClassLoader;
import dev.waterdog.waterdogpe.plugin.PluginManager;
import dev.waterdog.waterdogpe.utils.config.*;
import dev.waterdog.waterdogpe.utils.config.proxy.ProxyConfig;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import net.cubespace.Yamler.Config.InvalidConfigurationException;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.ServiceLoader.Provider;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class ConfigurationManager {

    private final ProxyServer proxy;
    private ProxyConfig proxyConfig;
    private LangConfig langConfig;

    private final Map<String, PendingAction> pendingActions = new ConcurrentHashMap<>();
    private volatile boolean eventListenersRegistered = false;
    @Getter(AccessLevel.NONE)
    private long serverInfoReloadGeneration;

    public ConfigurationManager(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Deprecated
    public static Configuration newConfig(File file, Type type) {
        return newConfig(file.toString(), type);
    }

    @Deprecated
    public static Configuration newConfig(String file, Type type) {
        return switch (type) {
            case YAML -> new YamlConfig(file);
            case JSON -> new JsonConfig(file);
            default -> null;
        };
    }

    public void loadProxyConfig() throws InvalidConfigurationException {
        File configFile = new File(this.proxy.getDataPath().toString() + "/config.yml");
        ProxyConfig config = new ProxyConfig(configFile);
        config.init();
        NetherNetProperties.applyOverrides(config.getNetherNetSettings().getSignaling());
        this.proxyConfig = config;
    }

    public void loadServerInfos(ServerInfoMap serverInfoMap) {
        for (ServerEntry entry : this.proxyConfig.getServerList().values()) {
            try {
                serverInfoMap.putIfAbsent(entry.getServerName(), serverInfoMap.fromServerEntry(entry));
            } catch (Exception e) {
                this.proxy.getLogger().error("Failed to create ServerInfo from "+entry, e);
            }
        }
    }

    /**
     * Reload server list from config.yml and diff-sync with the runtime ServerInfoMap.
     * Additions and updates are published asynchronously on the resolver thread: until
     * {@link ReloadResult#getReady()} completes, a newly added server is absent from the map and an
     * updated one keeps answering with its previous instance. Removals are immediate.
     * Changes on servers with active players are deferred until all players leave.
     * A newer reload supersedes the previous one: a publication it no longer owns fails with a
     * {@link CancellationException} instead of overwriting the newer config.
     */
    public synchronized ReloadResult reloadServerInfos(ServerInfoMap serverInfoMap) throws InvalidConfigurationException {
        this.proxyConfig.init();
        this.serverInfoReloadGeneration++;

        ServerList newServerList = this.proxyConfig.getServerList();
        Set<String> newNames = newServerList.keySet();
        // Copy to avoid ConcurrentModificationException during iteration
        Set<String> currentNames = new HashSet<>(serverInfoMap.keySet());

        int added = 0;
        int removed = 0;
        int updated = 0;
        List<String> pendingUpdates = new ArrayList<>();
        List<String> pendingRemovals = new ArrayList<>();
        List<CompletableFuture<PublicationResult>> publications = new ArrayList<>();

        // Reconcile existing pending actions with the new config
        for (String name : new ArrayList<>(this.pendingActions.keySet())) {
            PendingAction existing = this.pendingActions.get(name);
            ServerEntry newEntry = newServerList.get(name);
            ServerInfo current = serverInfoMap.get(name);

            if (existing.getNewEntry() == null) {
                // Existing pending REMOVE
                if (newEntry != null) {
                    if (current != null && addressEquals(current, newEntry)) {
                        // Server re-appeared with same address - cancel pending remove
                        this.pendingActions.remove(name);
                    } else {
                        // Server re-appeared with different address - convert to pending update
                        this.pendingActions.put(name, new PendingAction(newEntry));
                    }
                }
                // else: server still not in config - keep pending remove
            } else {
                // Existing pending UPDATE
                if (newEntry == null) {
                    // Server removed from config - convert to pending remove
                    this.pendingActions.put(name, new PendingAction(null));
                } else if (current != null && addressEquals(current, newEntry)) {
                    // Config reverted to match current runtime address - cancel pending
                    this.pendingActions.remove(name);
                } else {
                    // Config still has a different address - update the pending entry
                    this.pendingActions.put(name, new PendingAction(newEntry));
                }
            }
        }

        // Process servers in new config: add new ones, detect address changes
        for (ServerEntry entry : newServerList.values()) {
            String name = entry.getServerName();
            if (!currentNames.contains(name)) {
                // New server: add once its public address has resolved
                publications.add(this.publishWhenReady(serverInfoMap, entry, null));
                added++;
            } else {
                // Existing server: check address change (skip if already has pending action)
                if (this.pendingActions.containsKey(name)) {
                    continue;
                }
                ServerInfo current = serverInfoMap.get(name);
                if (current != null && !addressEquals(current, entry)) {
                    if (current.getPlayers().isEmpty()) {
                        // Keep the current instance available until the replacement has resolved its aliases.
                        publications.add(this.publishWhenReady(serverInfoMap, entry, current));
                        updated++;
                    } else {
                        // Has players: defer update until players leave
                        this.pendingActions.put(name, new PendingAction(entry));
                        pendingUpdates.add(name);
                    }
                }
            }
        }

        // Remove servers not in new config
        for (String name : currentNames) {
            if (!newNames.contains(name)) {
                // Skip if already has pending action
                if (this.pendingActions.containsKey(name)) {
                    continue;
                }
                ServerInfo serverInfo = serverInfoMap.get(name);
                if (serverInfo != null && !serverInfo.getPlayers().isEmpty()) {
                    // Has players: defer removal until players leave
                    this.pendingActions.put(name, new PendingAction(null));
                    pendingRemovals.add(name);
                } else {
                    // No players: remove immediately
                    serverInfoMap.remove(name);
                    removed++;
                }
            }
        }

        // Register event listeners once if there are pending actions
        if (!this.pendingActions.isEmpty()) {
            this.registerPendingEventListeners();
        }

        // Notify reconnect handler to reset state
        this.proxy.getReconnectHandler().onReload();

        CompletableFuture<Void> ready = new CompletableFuture<>();
        ReloadResult result = new ReloadResult(added, removed, updated, pendingUpdates, pendingRemovals, ready);
        this.completeWhenPublished(publications, result);
        return result;
    }

    private boolean addressEquals(ServerInfo current, ServerEntry entry) {
        return current.getAddress().equals(entry.getAddress())
                && Objects.equals(current.getPublicAddress(), entry.getPublicAddress());
    }

    private CompletableFuture<PublicationResult> publishWhenReady(ServerInfoMap serverInfoMap, ServerEntry entry, ServerInfo expected) {
        long generation = this.serverInfoReloadGeneration;
        CompletableFuture<PublicationResult> publication = serverInfoMap.fromServerEntryAsync(entry).thenApply(serverInfo -> {
            PublicationResult result;
            synchronized (this) {
                String name = entry.getServerName();
                if (generation != this.serverInfoReloadGeneration) {
                    throw new CancellationException("Reload for server " + name + " was superseded");
                }
                if (serverInfoMap.get(name) != expected) {
                    throw new IllegalStateException("Server " + name + " changed while reload was resolving addresses");
                }
                if (expected != null && !expected.getPlayers().isEmpty()) {
                    this.pendingActions.put(name, new PendingAction(entry));
                    result = new PublicationResult(name, true);
                } else if (expected == null) {
                    if (serverInfoMap.putIfAbsent(name, serverInfo) != null) {
                        throw new IllegalStateException("Server " + name + " was registered while reload was resolving addresses");
                    }
                    result = new PublicationResult(name, false);
                } else {
                    if (!serverInfoMap.replace(name, expected, serverInfo)) {
                        throw new IllegalStateException("Server " + name + " changed while reload was publishing");
                    }
                    result = new PublicationResult(name, false);
                }
            }
            if (result.deferred()) {
                this.registerPendingEventListeners();
                this.scanAndApplyPendingActions();
            }
            return result;
        });
        publication.whenComplete((result, error) -> {
            if (error == null) {
                return;
            }
            if (error instanceof CancellationException || error.getCause() instanceof CancellationException) {
                return; // a newer reload superseded this publication
            }
            this.proxy.getLogger().error("Failed to publish ServerInfo from " + entry, error);
        });
        return publication;
    }

    private void completeWhenPublished(List<CompletableFuture<PublicationResult>> publications, ReloadResult result) {
        CompletableFuture.allOf(publications.toArray(new CompletableFuture[0])).whenComplete((ignored, error) -> {
            if (error != null) {
                result.ready.completeExceptionally(error);
                return;
            }
            for (CompletableFuture<PublicationResult> future : publications) {
                PublicationResult publication = future.join();
                if (publication.deferred()) {
                    result.markUpdateDeferred(publication.serverName());
                }
            }
            result.ready.complete(null);
        });
    }

    private synchronized void registerPendingEventListeners() {
        if (this.eventListenersRegistered) {
            return;
        }
        this.eventListenersRegistered = true;

        // TransferCompleteEvent: removeConnection already called on source server
        this.proxy.getEventManager().subscribe(TransferCompleteEvent.class, event -> {
            if (this.pendingActions.isEmpty()) {
                return;
            }
            this.scanAndApplyPendingActions();
        });

        // PlayerDisconnectedEvent: player is disconnecting, scan all pending actions
        this.proxy.getEventManager().subscribe(PlayerDisconnectedEvent.class, event -> {
            if (this.pendingActions.isEmpty()) {
                return;
            }
            this.scanAndApplyPendingActions();
        });
    }

    private void scanAndApplyPendingActions() {
        ServerInfoMap serverInfoMap = this.proxy.getServerInfoMap();
        for (String name : new ArrayList<>(this.pendingActions.keySet())) {
            ServerInfo serverInfo = serverInfoMap.get(name);
            // For REMOVE actions, serverInfo may already be null (removed elsewhere)
            if (serverInfo == null || serverInfo.getPlayers().isEmpty()) {
                this.applyPendingAction(name);
            }
        }
    }

    private synchronized void applyPendingAction(String serverName) {
        PendingAction action = this.pendingActions.remove(serverName);
        if (action == null) {
            return;
        }

        ServerInfoMap serverInfoMap = this.proxy.getServerInfoMap();
        ServerInfo current = serverInfoMap.get(serverName);

        // Safety check: if server still has players, put action back
        if (current != null && !current.getPlayers().isEmpty()) {
            this.pendingActions.put(serverName, action);
            return;
        }

        if (action.getNewEntry() == null) {
            // REMOVE action
            serverInfoMap.remove(serverName);
            this.proxy.getLogger().info("Deferred removal applied: server '{}' removed (all players left)", serverName);
        } else {
            // Publish the deferred update once its replacement is ready.
            this.publishWhenReady(serverInfoMap, action.getNewEntry(), current).thenAccept(result -> {
                if (!result.deferred()) {
                    this.proxy.getLogger().info("Deferred update applied: server '{}' address updated (all players left)", serverName);
                }
            });
            this.proxy.getLogger().info("Deferred update scheduled: server '{}' will update after address resolution", serverName);
        }
    }

    public Map<String, PendingAction> getPendingActions() {
        return Collections.unmodifiableMap(this.pendingActions);
    }

    public void loadLanguage() {
        File langFile = new File(this.proxy.getDataPath().toString() + "/lang.ini");
        if (!langFile.exists()) {
            try {
                FileUtils.saveFromResources("lang.ini", langFile);
            } catch (IOException e) {
                this.proxy.getLogger().error("Can not save lang file!", e);
            }
        }
        // Backfill: append any keys present in the bundled jar resource but missing from the
        // on-disk file. This keeps operator customizations intact while making newly added
        // translation keys (from an upgraded jar) visible and editable without deleting the file.
        this.backfillLanguageFile(langFile);
        this.langConfig = new LangConfig(langFile);
    }

    /**
     * Append translation keys defined in the bundled {@code lang.ini} resource that are absent
     * from the on-disk file. The on-disk file is never rewritten for keys it already contains,
     * so existing values (including operator edits) are preserved.
     */
    private void backfillLanguageFile(File langFile) {
        String resourceContent;
        try (java.io.InputStream in = ConfigurationManager.class.getClassLoader().getResourceAsStream("lang.ini")) {
            if (in == null) {
                return; // nothing bundled to backfill from
            }
            resourceContent = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            this.proxy.getLogger().error("Can not read bundled lang.ini resource", e);
            return;
        }

        Set<String> resourceKeys = new LinkedHashSet<>();
        Map<String, String> resourceEntries = new LinkedHashMap<>();
        for (String line : resourceContent.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = trimmed.substring(0, eq);
            resourceKeys.add(key);
            resourceEntries.put(key, trimmed.substring(eq + 1));
        }
        if (resourceKeys.isEmpty()) {
            return;
        }

        Set<String> diskKeys = new HashSet<>();
        try {
            if (langFile.exists()) {
                String diskContent = FileUtils.readFile(langFile);
                for (String line : diskContent.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
                        continue;
                    }
                    int eq = trimmed.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    diskKeys.add(trimmed.substring(0, eq));
                }
            }
        } catch (IOException e) {
            this.proxy.getLogger().error("Can not read existing lang file for backfill", e);
            return;
        }

        // Keys present in the jar but missing on disk → append them.
        List<String> missing = new ArrayList<>();
        for (String key : resourceKeys) {
            if (!diskKeys.contains(key)) {
                missing.add(key);
            }
        }
        if (missing.isEmpty()) {
            return;
        }

        StringBuilder appendage = new StringBuilder();
        appendage.append("\n\n# ---- appended by the proxy on startup ----\n");
        for (String key : missing) {
            appendage.append(key).append('=').append(resourceEntries.get(key)).append('\n');
        }
        try (java.io.FileWriter writer = new java.io.FileWriter(langFile, true)) {
            writer.write(appendage.toString());
        } catch (IOException e) {
            this.proxy.getLogger().error("Failed to backfill missing lang keys into " + langFile, e);
            return;
        }
        this.proxy.getLogger().info("Added {} new translation key(s) to lang.ini: {}",
                missing.size(), String.join(", ", missing));
    }

    public <T> T loadServiceProvider(String providerName, Class<T> clazz) {
        return this.loadServiceProvider(providerName, clazz, null);
    }

    public <T> T loadServiceProvider(String providerName, Class<T> clazz, PluginManager pluginManager) {
        Collection<Provider<T>> providers = new HashSet<>();
        // Lookup using main class load
        ServiceLoader.load(clazz).stream().forEach(providers::add);
        // Lookup in plugin class loaders
        if (pluginManager != null) {
            for (PluginClassLoader loader : pluginManager.getPluginClassLoaders()) {
                ServiceLoader.load(clazz, loader).stream().forEach(providers::add);
            }
        }

        for (Provider<T> provider : providers) {
            if (provider.type().getSimpleName().equals(providerName)) {
                return provider.get();
            }
        }
        return null;
    }

    @AllArgsConstructor
    public enum Type {
        JSON(1),
        YAML(2),
        UNKNOWN(-1);

        @Getter
        private final int id;

        public static Type getTypeById(int id) {
            return Arrays.stream(Type.values()).filter(type -> type.getId() == id).findFirst().orElse(Type.UNKNOWN);
        }
    }

    @Getter
    @AllArgsConstructor
    public static class PendingAction {
        private final ServerEntry newEntry;
    }

    private record PublicationResult(String serverName, boolean deferred) {
    }

    /**
     * Outcome of a server-list reload. {@link #getUpdated()} and the pending lists are only final once
     * {@link #getReady()} completes; read them from that future's completion callback.
     */
    @Getter
    public static class ReloadResult {
        private final int added;
        private final int removed;
        private int updated;
        private final List<String> pendingUpdates;
        private final List<String> pendingRemovals;
        private final CompletableFuture<Void> ready;

        public ReloadResult(int added, int removed, int updated, List<String> pendingUpdates, List<String> pendingRemovals) {
            this(added, removed, updated, pendingUpdates, pendingRemovals, CompletableFuture.completedFuture(null));
        }

        private ReloadResult(int added, int removed, int updated, List<String> pendingUpdates,
                             List<String> pendingRemovals, CompletableFuture<Void> ready) {
            this.added = added;
            this.removed = removed;
            this.updated = updated;
            this.pendingUpdates = pendingUpdates;
            this.pendingRemovals = pendingRemovals;
            this.ready = ready;
        }

        private void markUpdateDeferred(String serverName) {
            this.updated--;
            if (!this.pendingUpdates.contains(serverName)) {
                this.pendingUpdates.add(serverName);
            }
        }
    }
}
