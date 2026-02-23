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
import it.unimi.dsi.fastutil.ints.*;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
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
        @Getter
        private final List<ItemDefinition> itemDefinitions;
        @Getter
        private final List<BlockPropertyData> blockProperties;
        @Getter
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

    /** All runtime IDs already allocated in the unified item registry, used to detect and resolve conflicts */
    private final IntSet usedUnifiedRuntimeIds = new IntOpenHashSet();

    /** Unified block properties: block name -> BlockPropertyData */
    private final Map<String, BlockPropertyData> unifiedBlockProperties = new LinkedHashMap<>();

    /** Unified entity identifiers: entity id string -> NbtMap entry */
    private final Map<String, NbtMap> unifiedEntityEntries = new LinkedHashMap<>();

    /** Component data from ItemComponentPacket, keyed by item identifier (≤1.21.50 only) */
    private final Map<String, NbtMap> unifiedComponentData = new LinkedHashMap<>();

    /** Per-server blockNetworkIdsHashed flag from StartGamePacket (v582+) */
    private final Map<String, Boolean> serverBlockNetworkIdsHashed = new ConcurrentHashMap<>();

    /** Monotonically increasing version for item definitions, incremented when new items are discovered */
    private volatile int itemDefinitionVersion = 0;

    /** Monotonically increasing version for block definitions, incremented when new block properties are discovered */
    private volatile int blockDefinitionVersion = 0;

    /** Temporary storage for players who need to reconnect to refresh definitions, keyed by player UUID */
    private final Map<UUID, PendingRefreshTarget> pendingRefreshTargets = new ConcurrentHashMap<>();

    /** Optional cache for persisting definitions across proxy restarts */
    private DefinitionCache cache;

    /**
     * Initialize the aggregator from a cached definition file.
     * Should be called once at proxy startup before any player connects.
     *
     * @param cache the DefinitionCache to use for loading and saving
     */
    public void initFromCache(DefinitionCache cache) {
        // Load cached data first without setting this.cache,
        // so registerServer/registerEntityIdentifiers won't trigger redundant cache writes during initialization
        Map<String, DefinitionCache.LoadedSnapshot> cached = cache.load();
        for (Map.Entry<String, DefinitionCache.LoadedSnapshot> entry : cached.entrySet()) {
            DefinitionCache.LoadedSnapshot snapshot = entry.getValue();
            this.registerServer(entry.getKey(), snapshot.items(), snapshot.blockProperties());
            if (snapshot.entityIdentifiers() != null) {
                this.registerEntityIdentifiers(entry.getKey(), snapshot.entityIdentifiers());
            }
        }
        this.cache = cache;
        if (!cached.isEmpty()) {
            log.info("Pre-populated aggregator from cache: {} servers, {} unified items, {} unified block properties, {} unified entity entries",
                    cached.size(), this.unifiedItems.size(), this.unifiedBlockProperties.size(), this.unifiedEntityEntries.size());
        }
    }

    /**
     * Register a server's item and block definitions.
     * The first server to register an identifier determines its unified runtime ID.
     */
    public synchronized void registerServer(String serverName, List<ItemDefinition> itemDefs, List<BlockPropertyData> blockProps) {
        if (blockProps == null) {
            ServerSnapshot existing = this.serverSnapshots.get(serverName);
            if (existing != null) {
                blockProps = existing.getBlockProperties();
            }
        }

        ServerSnapshot snapshot = new ServerSnapshot(itemDefs, blockProps);
        ServerSnapshot previous = this.serverSnapshots.put(serverName, snapshot);
        boolean discoveredNewItems = false;
        boolean discoveredNewBlocks = false;

        // Aggregate item definitions; version increments only on actual new additions
        // (avoids false-positive stale detection for servers with 0 items in StartGamePacket).
        for (ItemDefinition def : snapshot.itemDefinitions) {
            if (this.unifiedItems.containsKey(def.getIdentifier())) {
                // Refresh if existing definition lacks componentData but new one has it
                // (happens when cache-loaded definitions are replaced by fresh server data)
                ItemDefinition existing = this.unifiedItems.get(def.getIdentifier());
                if (existing.getComponentData() == null && def.getComponentData() != null) {
                    this.unifiedItems.put(def.getIdentifier(), new SimpleItemDefinition(
                            existing.getIdentifier(), existing.getRuntimeId(),
                            def.getVersion(), existing.isComponentBased(), def.getComponentData()));
                }
                continue;
            }
            // New identifier — allocate a unified runtimeId, resolving conflicts if needed
            int unifiedId = def.getRuntimeId();
            if (!this.usedUnifiedRuntimeIds.add(unifiedId)) {
                // Conflict: another item already occupies this runtimeId; find a free one
                unifiedId = this.findNextAvailableRuntimeId();
                this.usedUnifiedRuntimeIds.add(unifiedId);
                log.debug("Resolved item runtimeId conflict for {} from server {}: server id {} -> unified id {}",
                        def.getIdentifier(), serverName, def.getRuntimeId(), unifiedId);
            }
            ItemDefinition unifiedDef = (unifiedId == def.getRuntimeId()) ? def
                    : new SimpleItemDefinition(def.getIdentifier(), unifiedId, def.getVersion(), def.isComponentBased(), def.getComponentData());
            this.unifiedItems.put(def.getIdentifier(), unifiedDef);
            // Increment only when other servers exist (existing clients may need to refresh)
            if (previous != null || this.serverSnapshots.size() > 1) {
                discoveredNewItems = true;
                log.debug("New item definition discovered from server {}: {} (unifiedId={})",
                        serverName, def.getIdentifier(), unifiedId);
            }
        }

        // Track this server's previous block names to distinguish self-update from cross-server conflict.
        Set<String> previousServerBlockNames = Collections.emptySet();
        if (previous != null && !previous.getBlockProperties().isEmpty()) {
            previousServerBlockNames = new HashSet<>(previous.getBlockProperties().size());
            for (BlockPropertyData bp : previous.getBlockProperties()) {
                previousServerBlockNames.add(bp.getName());
            }
        }

        // Aggregate block properties
        for (BlockPropertyData bp : snapshot.blockProperties) {
            BlockPropertyData existing = this.unifiedBlockProperties.get(bp.getName());
            if (existing == null) {
                this.unifiedBlockProperties.put(bp.getName(), bp);
                if (previous != null || this.serverSnapshots.size() > 1) {
                    discoveredNewBlocks = true;
                    log.debug("New block property discovered from server {}: {}", serverName, bp.getName());
                }
            } else if (!blockPropertiesEqual(existing.getProperties(), bp.getProperties())) {
                if (previousServerBlockNames.contains(bp.getName())) {
                    // Same server update — refresh unified registry
                    this.unifiedBlockProperties.put(bp.getName(), bp);
                    discoveredNewBlocks = true;
                    log.debug("Block property '{}' updated by server {} (properties changed)", bp.getName(), serverName);
                } else {
                    // Different server registered this block first — genuine cross-server conflict
                    log.warn("Block property conflict for '{}': server {} has different properties, using first registered version",
                            bp.getName(), serverName);
                }
            }
        }

        // Clean up definitions that are no longer used by any server
        boolean removedDefinitions = false;
        if (previous != null) {
            removedDefinitions = cleanupRemovedDefinitions(previous, snapshot);
        }

        // Increment versions independently (only for NEW definitions, not removals)
        if (discoveredNewItems) {
            this.itemDefinitionVersion++;
        }
        if (discoveredNewBlocks) {
            this.blockDefinitionVersion++;
        }

        log.info("Registered definitions from server {}: {} items, {} block properties",
                serverName, snapshot.itemDefinitions.size(), snapshot.blockProperties.size());

        boolean dataChanged = previous == null || discoveredNewItems || discoveredNewBlocks || removedDefinitions;
        if (dataChanged && this.cache != null) {
            Map<String, ServerSnapshot> snapshotsCopy = new LinkedHashMap<>(this.serverSnapshots);
            CompletableFuture.runAsync(() -> this.cache.save(snapshotsCopy));
        }
    }

    /**
     * Remove definitions from the unified maps that were in the old snapshot
     * but not in the new one, and are not present in any other server's snapshot.
     *
     * @return true if any definitions were removed
     */
    private boolean cleanupRemovedDefinitions(ServerSnapshot oldSnapshot, ServerSnapshot newSnapshot) {
        boolean removed = false;

        // Collect new snapshot's identifiers for quick lookup
        Set<String> newItemIds = new HashSet<>(newSnapshot.itemDefinitions.size());
        for (ItemDefinition def : newSnapshot.itemDefinitions) {
            newItemIds.add(def.getIdentifier());
        }

        // Check each old item: if missing from new snapshot and no other server has it, remove
        for (ItemDefinition oldDef : oldSnapshot.itemDefinitions) {
            String id = oldDef.getIdentifier();
            if (!newItemIds.contains(id) && !isDefinitionUsedByAnyServer(id, true)) {
                ItemDefinition removedDef = this.unifiedItems.remove(id);
                if (removedDef != null) {
                    this.usedUnifiedRuntimeIds.remove(removedDef.getRuntimeId());
                }
                this.unifiedComponentData.remove(id);
                removed = true;
            }
        }

        // Same for block properties
        Set<String> newBlockNames = new HashSet<>(newSnapshot.blockProperties.size());
        for (BlockPropertyData bp : newSnapshot.blockProperties) {
            newBlockNames.add(bp.getName());
        }

        for (BlockPropertyData oldBp : oldSnapshot.blockProperties) {
            String name = oldBp.getName();
            if (!newBlockNames.contains(name) && !isDefinitionUsedByAnyServer(name, false)) {
                this.unifiedBlockProperties.remove(name);
                removed = true;
            }
        }

        return removed;
    }

    /**
     * Check if a definition identifier is present in any current server snapshot.
     * Called after the current server's snapshot has already been replaced.
     */
    private boolean isDefinitionUsedByAnyServer(String identifier, boolean isItem) {
        for (ServerSnapshot snap : this.serverSnapshots.values()) {
            if (isItem) {
                for (ItemDefinition def : snap.itemDefinitions) {
                    if (def.getIdentifier().equals(identifier)) {
                        return true;
                    }
                }
            } else {
                for (BlockPropertyData bp : snap.blockProperties) {
                    if (bp.getName().equals(identifier)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Register entity identifiers from a server.
     */
    public synchronized void registerEntityIdentifiers(String serverName, NbtMap identifiers) {
        ServerSnapshot snapshot = this.serverSnapshots.get(serverName);
        NbtMap previousIdentifiers = null;
        if (snapshot != null) {
            previousIdentifiers = snapshot.entityIdentifiers;
            snapshot.entityIdentifiers = identifiers;
        }

        if (identifiers == null) {
            return;
        }

        List<NbtMap> idlist = identifiers.getList("idlist", NbtType.COMPOUND);
        if (idlist == null) {
            return;
        }

        boolean discoveredNew = false;
        for (NbtMap entry : idlist) {
            String id = entry.getString("id", null);
            if (id != null && this.unifiedEntityEntries.putIfAbsent(id, entry) == null) {
                discoveredNew = true;
            }
        }

        // Clean up entities that were in the old snapshot but not in the new one
        boolean removedEntities = false;
        if (previousIdentifiers != null) {
            removedEntities = cleanupRemovedEntities(previousIdentifiers, identifiers);
        }

        boolean dataChanged = discoveredNew || removedEntities;
        if (dataChanged && this.cache != null) {
            Map<String, ServerSnapshot> snapshotsCopy = new LinkedHashMap<>(this.serverSnapshots);
            CompletableFuture.runAsync(() -> this.cache.save(snapshotsCopy));
        }
    }

    /**
     * Remove entity entries from the unified map that were in the old identifiers
     * but not in the new ones, and are not present in any other server's snapshot.
     *
     * @return true if any entries were removed
     */
    private boolean cleanupRemovedEntities(NbtMap oldIdentifiers, NbtMap newIdentifiers) {
        List<NbtMap> oldList = oldIdentifiers.getList("idlist", NbtType.COMPOUND);
        if (oldList == null || oldList.isEmpty()) {
            return false;
        }

        Set<String> newEntityIds = new HashSet<>();
        List<NbtMap> newList = newIdentifiers.getList("idlist", NbtType.COMPOUND);
        if (newList != null) {
            for (NbtMap entry : newList) {
                String id = entry.getString("id", null);
                if (id != null) {
                    newEntityIds.add(id);
                }
            }
        }

        boolean removed = false;
        for (NbtMap oldEntry : oldList) {
            String id = oldEntry.getString("id", null);
            if (id != null && !newEntityIds.contains(id) && !isEntityUsedByAnyServer(id)) {
                this.unifiedEntityEntries.remove(id);
                removed = true;
            }
        }
        return removed;
    }

    /**
     * Check if an entity ID is present in any current server snapshot's entity identifiers.
     */
    private boolean isEntityUsedByAnyServer(String entityId) {
        for (ServerSnapshot snap : this.serverSnapshots.values()) {
            if (snap.entityIdentifiers == null) {
                continue;
            }
            List<NbtMap> idlist = snap.entityIdentifiers.getList("idlist", NbtType.COMPOUND);
            if (idlist == null) {
                continue;
            }
            for (NbtMap entry : idlist) {
                if (entityId.equals(entry.getString("id", null))) {
                    return true;
                }
            }
        }
        return false;
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
     * Compare block property NbtMaps, using serialized bytes as fallback
     * to handle Boolean/Byte type differences after cache round-trip.
     */
    private static boolean blockPropertiesEqual(NbtMap a, NbtMap b) {
        if (a.equals(b)) return true;
        try {
            ByteArrayOutputStream baosA = new ByteArrayOutputStream();
            try (NBTOutputStream outA = NbtUtils.createWriterLE(baosA)) {
                outA.writeTag(a);
            }
            ByteArrayOutputStream baosB = new ByteArrayOutputStream();
            try (NBTOutputStream outB = NbtUtils.createWriterLE(baosB)) {
                outB.writeTag(b);
            }
            return Arrays.equals(baosA.toByteArray(), baosB.toByteArray());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Find the next available (unused) unified item runtime ID.
     * Must be called within a synchronized context.
     */
    private int findNextAvailableRuntimeId() {
        int max = 0;
        for (int id : this.usedUnifiedRuntimeIds) {
            if (id > max) max = id;
        }
        return max + 1;
    }

    /**
     * Build a SimpleDefinitionRegistry containing all unified item definitions.
     * Used for the upstream (client-facing) codec helper.
     * Runtime ID conflicts are resolved at registration time, so this registry is always conflict-free.
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
     * Register component data from an ItemComponentPacket (≤1.21.50).
     * First server to register data for an identifier wins.
     *
     * @return true if any new component data was registered
     */
    public synchronized boolean registerComponentData(List<ItemDefinition> items) {
        boolean added = false;
        for (ItemDefinition item : items) {
            NbtMap data = item.getComponentData();
            if (data != null && this.unifiedComponentData.putIfAbsent(item.getIdentifier(), data) == null) {
                added = true;
            }
        }
        return added;
    }

    /**
     * Build a list of ItemDefinitions containing all unified component data (≤1.21.50).
     * runtimeId is set to 0 because the v419 serializer does not write runtimeId.
     */
    public synchronized List<ItemDefinition> getUnifiedComponentItems() {
        List<ItemDefinition> result = new ArrayList<>(this.unifiedComponentData.size());
        for (Map.Entry<String, NbtMap> entry : this.unifiedComponentData.entrySet()) {
            result.add(new SimpleItemDefinition(entry.getKey(), 0, null, true, entry.getValue()));
        }
        return result;
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
     * Get the ServerSnapshot for a specific server.
     * Returns null if the server has not been registered.
     */
    public synchronized ServerSnapshot getServerSnapshot(String serverName) {
        return this.serverSnapshots.get(serverName);
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
     * Register a server's blockNetworkIdsHashed flag from StartGamePacket (v582+).
     */
    public void registerBlockNetworkIdsHashed(String serverName, boolean hashed) {
        this.serverBlockNetworkIdsHashed.put(serverName, hashed);
    }

    /**
     * Get a server's blockNetworkIdsHashed flag.
     * Returns null if the server has not been seen yet.
     */
    public Boolean getBlockNetworkIdsHashed(String serverName) {
        return this.serverBlockNetworkIdsHashed.get(serverName);
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
