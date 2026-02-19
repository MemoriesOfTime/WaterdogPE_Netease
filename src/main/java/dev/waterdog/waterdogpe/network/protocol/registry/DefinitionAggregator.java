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

package dev.waterdog.waterdogpe.network.protocol.registry;

import dev.waterdog.waterdogpe.network.serverinfo.ServerInfo;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global singleton that aggregates custom item, block, and entity definitions
 * from all downstream servers into unified registries.
 * <p>
 * Thread-safe: registration and query methods are synchronized.
 */
@Log4j2
public class DefinitionAggregator {

    /**
     * Snapshot of definitions from a single downstream server.
     */
    public static class ServerSnapshot {
        private final List<ItemDefinition> itemDefinitions;
        private final List<BlockPropertyData> blockProperties;
        private NbtMap entityIdentifiers;

        public ServerSnapshot(List<ItemDefinition> itemDefinitions, List<BlockPropertyData> blockProperties) {
            this.itemDefinitions = itemDefinitions != null ? new ArrayList<>(itemDefinitions) : Collections.emptyList();
            this.blockProperties = blockProperties != null ? new ArrayList<>(blockProperties) : Collections.emptyList();
        }
    }

    /** Per-server snapshots, keyed by server name */
    private final Map<String, ServerSnapshot> serverSnapshots = new ConcurrentHashMap<>();

    /** Unified item definitions: identifier -> unified ItemDefinition */
    private final Map<String, ItemDefinition> unifiedItems = new LinkedHashMap<>();

    /** Unified block properties: block name -> BlockPropertyData */
    private final Map<String, BlockPropertyData> unifiedBlockProperties = new LinkedHashMap<>();

    /** Unified entity identifiers: entity id string -> NbtMap entry */
    private final Map<String, NbtMap> unifiedEntityEntries = new LinkedHashMap<>();

    /** Track whether new definitions were added since last check */
    private volatile boolean hasNewDefinitions = false;

    /** Monotonically increasing version for item definitions, incremented when new items are discovered */
    private volatile int itemDefinitionVersion = 0;

    /** Monotonically increasing version for block definitions, incremented when new block properties are discovered */
    private volatile int blockDefinitionVersion = 0;

    /** Temporary storage for players who need to reconnect to refresh definitions, keyed by player UUID */
    private final Map<UUID, PendingRefreshTarget> pendingRefreshTargets = new ConcurrentHashMap<>();

    /**
     * Register a server's item and block definitions.
     * The first server to register an identifier determines its unified runtime ID.
     */
    public synchronized void registerServer(String serverName, List<ItemDefinition> itemDefs, List<BlockPropertyData> blockProps) {
        ServerSnapshot snapshot = new ServerSnapshot(itemDefs, blockProps);
        ServerSnapshot previous = this.serverSnapshots.put(serverName, snapshot);
        boolean discoveredNewItems = false;
        boolean discoveredNewBlocks = false;

        // Aggregate item definitions
        for (ItemDefinition def : snapshot.itemDefinitions) {
            if (this.unifiedItems.putIfAbsent(def.getIdentifier(), def) == null) {
                if (previous != null) {
                    // New identifier found from a known server (re-registration with new items)
                    this.hasNewDefinitions = true;
                    discoveredNewItems = true;
                    log.info("New item definition discovered from server {}: {} (runtimeId={})",
                            serverName, def.getIdentifier(), def.getRuntimeId());
                }
            }
        }

        // Aggregate block properties
        for (BlockPropertyData bp : snapshot.blockProperties) {
            if (this.unifiedBlockProperties.putIfAbsent(bp.getName(), bp) == null) {
                if (previous != null) {
                    this.hasNewDefinitions = true;
                    discoveredNewBlocks = true;
                    log.info("New block property discovered from server {}: {}", serverName, bp.getName());
                }
            }
        }

        // Mark new definitions if this is a newly discovered server
        if (previous == null && this.serverSnapshots.size() > 1) {
            this.hasNewDefinitions = true;
            discoveredNewItems = true;
            discoveredNewBlocks = true;
        }

        // Increment versions independently
        if (discoveredNewItems) {
            this.itemDefinitionVersion++;
        }
        if (discoveredNewBlocks) {
            this.blockDefinitionVersion++;
        }

        log.info("Registered definitions from server {}: {} items, {} block properties",
                serverName, snapshot.itemDefinitions.size(), snapshot.blockProperties.size());
    }

    /**
     * Register entity identifiers from a server.
     */
    public synchronized void registerEntityIdentifiers(String serverName, NbtMap identifiers) {
        ServerSnapshot snapshot = this.serverSnapshots.get(serverName);
        if (snapshot != null) {
            snapshot.entityIdentifiers = identifiers;
        }

        if (identifiers == null) {
            return;
        }

        List<NbtMap> idlist = identifiers.getList("idlist", NbtType.COMPOUND);
        if (idlist == null) {
            return;
        }

        for (NbtMap entry : idlist) {
            String id = entry.getString("id", null);
            if (id != null) {
                this.unifiedEntityEntries.putIfAbsent(id, entry);
            }
        }
    }

    /**
     * Create a ServerIdMapping for a specific downstream server.
     * Maps the server's item runtime IDs to unified runtime IDs.
     */
    public synchronized ServerIdMapping createMapping(String serverName) {
        ServerSnapshot snapshot = this.serverSnapshots.get(serverName);
        if (snapshot == null) {
            return ServerIdMapping.IDENTITY;
        }

        Int2IntMap serverToUnified = new Int2IntOpenHashMap();
        Int2IntMap unifiedToServer = new Int2IntOpenHashMap();
        boolean isIdentity = true;

        for (ItemDefinition serverDef : snapshot.itemDefinitions) {
            ItemDefinition unifiedDef = this.unifiedItems.get(serverDef.getIdentifier());
            if (unifiedDef == null) {
                continue;
            }

            int serverId = serverDef.getRuntimeId();
            int unifiedId = unifiedDef.getRuntimeId();
            serverToUnified.put(serverId, unifiedId);
            unifiedToServer.put(unifiedId, serverId);

            if (serverId != unifiedId) {
                isIdentity = false;
            }
        }

        if (isIdentity) {
            return ServerIdMapping.IDENTITY;
        }
        return new ServerIdMapping(serverToUnified, unifiedToServer);
    }

    /**
     * Build a TranslatingItemRegistry for a specific downstream server.
     * Returns null if the mapping is identity (no translation needed).
     */
    public synchronized TranslatingItemRegistry buildTranslatingRegistry(String serverName) {
        ServerSnapshot snapshot = this.serverSnapshots.get(serverName);
        if (snapshot == null) {
            return null;
        }

        ServerIdMapping mapping = createMapping(serverName);
        if (mapping.isIdentity()) {
            return null;
        }

        Int2ObjectMap<ItemDefinition> translating = new Int2ObjectOpenHashMap<>();
        for (ItemDefinition serverDef : snapshot.itemDefinitions) {
            ItemDefinition unifiedDef = this.unifiedItems.get(serverDef.getIdentifier());
            if (unifiedDef != null) {
                // key = server's runtimeId, value = ItemDefinition with unified runtimeId
                translating.put(serverDef.getRuntimeId(), unifiedDef);
            }
        }

        return new TranslatingItemRegistry(translating);
    }

    /**
     * Build a SimpleDefinitionRegistry containing all unified item definitions.
     * Used for the upstream (client-facing) codec helper.
     */
    public synchronized SimpleDefinitionRegistry<ItemDefinition> buildUnifiedItemRegistry() {
        SimpleDefinitionRegistry.Builder<ItemDefinition> builder = SimpleDefinitionRegistry.builder();
        for (ItemDefinition def : this.unifiedItems.values()) {
            builder.add(def);
        }
        return builder.build();
    }

    /**
     * Get the unified item definitions list (for StartGamePacket / ItemComponentPacket).
     */
    public synchronized List<ItemDefinition> getUnifiedItemDefinitions() {
        return new ArrayList<>(this.unifiedItems.values());
    }

    /**
     * Get the unified block properties list (for StartGamePacket).
     */
    public synchronized List<BlockPropertyData> getUnifiedBlockProperties() {
        return new ArrayList<>(this.unifiedBlockProperties.values());
    }

    /**
     * Get the merged entity identifiers NbtMap (for AvailableEntityIdentifiersPacket).
     */
    public synchronized NbtMap getMergedEntityIdentifiers() {
        if (this.unifiedEntityEntries.isEmpty()) {
            return null;
        }

        return NbtMap.builder()
                .putList("idlist", NbtType.COMPOUND, new ArrayList<>(this.unifiedEntityEntries.values()))
                .build();
    }

    /**
     * Check and reset the "new definitions discovered" flag.
     */
    public boolean checkAndResetNewDefinitions() {
        boolean had = this.hasNewDefinitions;
        this.hasNewDefinitions = false;
        return had;
    }

    /**
     * Check if a server has been registered.
     */
    public boolean hasServer(String serverName) {
        return this.serverSnapshots.containsKey(serverName);
    }

    /**
     * Get the number of registered servers.
     */
    public int getServerCount() {
        return this.serverSnapshots.size();
    }

    /**
     * Get the total number of unified item definitions.
     */
    public int getUnifiedItemCount() {
        return this.unifiedItems.size();
    }

    /**
     * Get the current item definition version. Incremented when new item definitions are discovered.
     */
    public int getItemDefinitionVersion() {
        return this.itemDefinitionVersion;
    }

    /**
     * Get the current block definition version. Incremented when new block properties are discovered.
     */
    public int getBlockDefinitionVersion() {
        return this.blockDefinitionVersion;
    }

    /**
     * Store a pending refresh target for a player who needs to reconnect to get updated definitions.
     */
    public void addPendingRefreshTarget(UUID playerUuid, ServerInfo targetServer) {
        this.pendingRefreshTargets.put(playerUuid, new PendingRefreshTarget(targetServer, System.currentTimeMillis()));
    }

    /**
     * Retrieve and remove a pending refresh target for a player.
     * Returns null if no pending target exists or if it has expired (>30 seconds).
     */
    public ServerInfo removePendingRefreshTarget(UUID playerUuid) {
        PendingRefreshTarget target = this.pendingRefreshTargets.remove(playerUuid);
        if (target == null) {
            return null;
        }
        // Expire after 30 seconds
        if (System.currentTimeMillis() - target.timestamp > 30_000) {
            return null;
        }
        return target.serverInfo;
    }

    private static class PendingRefreshTarget {
        final ServerInfo serverInfo;
        final long timestamp;

        PendingRefreshTarget(ServerInfo serverInfo, long timestamp) {
            this.serverInfo = serverInfo;
            this.timestamp = timestamp;
        }
    }
}
