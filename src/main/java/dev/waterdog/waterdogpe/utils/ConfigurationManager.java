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
import dev.waterdog.waterdogpe.network.serverinfo.ServerInfo;
import dev.waterdog.waterdogpe.network.serverinfo.ServerInfoMap;
import dev.waterdog.waterdogpe.plugin.PluginClassLoader;
import dev.waterdog.waterdogpe.plugin.PluginManager;
import dev.waterdog.waterdogpe.utils.config.*;
import dev.waterdog.waterdogpe.utils.config.proxy.ProxyConfig;
import lombok.AllArgsConstructor;
import lombok.Getter;
import net.cubespace.Yamler.Config.InvalidConfigurationException;

import java.io.File;
import java.io.IOException;
import java.util.ServiceLoader.Provider;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ConfigurationManager {

    private final ProxyServer proxy;
    private ProxyConfig proxyConfig;
    private LangConfig langConfig;

    private final Map<String, PendingAction> pendingActions = new ConcurrentHashMap<>();
    private volatile boolean eventListenersRegistered = false;

    public ConfigurationManager(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Deprecated
    public static Configuration newConfig(File file, Type type) {
        return newConfig(file.toString(), type);
    }

    @Deprecated
    public static Configuration newConfig(String file, Type type) {
        switch (type) {
            case YAML:
                return new YamlConfig(file);
            case JSON:
                return new JsonConfig(file);
            default:
                return null;
        }
    }

    public void loadProxyConfig() throws InvalidConfigurationException {
        File configFile = new File(this.proxy.getDataPath().toString() + "/config.yml");
        ProxyConfig config = new ProxyConfig(configFile);
        config.init();
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
     * Servers with no active players are added/removed/updated immediately.
     * Changes on servers with active players are deferred until all players leave.
     */
    public ReloadResult reloadServerInfos(ServerInfoMap serverInfoMap) throws InvalidConfigurationException {
        this.proxyConfig.init();

        ServerList newServerList = this.proxyConfig.getServerList();
        Set<String> newNames = newServerList.keySet();
        // Copy to avoid ConcurrentModificationException during iteration
        Set<String> currentNames = new HashSet<>(serverInfoMap.keySet());

        int added = 0;
        int removed = 0;
        int updated = 0;
        List<String> pendingUpdates = new ArrayList<>();
        List<String> pendingRemovals = new ArrayList<>();

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
                // New server: add
                try {
                    serverInfoMap.put(name, serverInfoMap.fromServerEntry(entry));
                    added++;
                } catch (Exception e) {
                    this.proxy.getLogger().error("Failed to create ServerInfo from " + entry, e);
                }
            } else {
                // Existing server: check address change (skip if already has pending action)
                if (this.pendingActions.containsKey(name)) {
                    continue;
                }
                ServerInfo current = serverInfoMap.get(name);
                if (current != null && !addressEquals(current, entry)) {
                    if (current.getPlayers().isEmpty()) {
                        // No players: replace immediately (put overwrites atomically)
                        try {
                            serverInfoMap.put(name, serverInfoMap.fromServerEntry(entry));
                            updated++;
                        } catch (Exception e) {
                            this.proxy.getLogger().error("Failed to update ServerInfo from " + entry, e);
                        }
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

        return new ReloadResult(added, removed, updated, pendingUpdates, pendingRemovals);
    }

    private boolean addressEquals(ServerInfo current, ServerEntry entry) {
        return current.getAddress().equals(entry.getAddress())
                && Objects.equals(current.getPublicAddress(), entry.getPublicAddress());
    }

    private void registerPendingEventListeners() {
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

    private void applyPendingAction(String serverName) {
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
            // UPDATE action: put overwrites atomically
            try {
                serverInfoMap.put(serverName, serverInfoMap.fromServerEntry(action.getNewEntry()));
                this.proxy.getLogger().info("Deferred update applied: server '{}' address updated (all players left)", serverName);
            } catch (Exception e) {
                this.proxy.getLogger().error("Failed to apply deferred update for server " + serverName, e);
            }
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
        this.langConfig = new LangConfig(langFile);
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

    public ProxyServer getProxy() {
        return this.proxy;
    }

    public ProxyConfig getProxyConfig() {
        return this.proxyConfig;
    }

    public LangConfig getLangConfig() {
        return this.langConfig;
    }

    @Getter
    @AllArgsConstructor
    public static class PendingAction {
        private final ServerEntry newEntry;
    }

    @Getter
    @AllArgsConstructor
    public static class ReloadResult {
        private final int added;
        private final int removed;
        private final int updated;
        private final List<String> pendingUpdates;
        private final List<String> pendingRemovals;
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
}
