package dev.waterdog.waterdogpe.network.protocol.registry;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefinitionAggregatorTest {

    private DefinitionAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new DefinitionAggregator();
    }

    // ==================== Item Aggregation ====================

    @Test
    void testRegisterSingleServerItems() {
        List<ItemDefinition> items = List.of(
                new SimpleItemDefinition("custom:sword", 1000, false),
                new SimpleItemDefinition("custom:shield", 1001, false)
        );
        aggregator.registerServer("lobby", items, Collections.emptyList());

        assertEquals(2, aggregator.getUnifiedItemCount());
        assertTrue(aggregator.hasServer("lobby"));
        assertEquals(1, aggregator.getServerCount());
    }

    @Test
    void testRegisterMultipleServersWithDistinctItems() {
        List<ItemDefinition> lobbyItems = List.of(
                new SimpleItemDefinition("custom:hat", 1000, false)
        );
        List<ItemDefinition> survivalItems = List.of(
                new SimpleItemDefinition("custom:pickaxe", 2000, false)
        );

        aggregator.registerServer("lobby", lobbyItems, Collections.emptyList());
        aggregator.registerServer("survival", survivalItems, Collections.emptyList());

        assertEquals(2, aggregator.getUnifiedItemCount());
        List<ItemDefinition> unified = aggregator.getUnifiedItemDefinitions();
        assertEquals(2, unified.size());
    }

    @Test
    void testDuplicateItemIdentifierUsesFirstRegistered() {
        List<ItemDefinition> lobbyItems = List.of(
                new SimpleItemDefinition("custom:gem", 100, false)
        );
        List<ItemDefinition> survivalItems = List.of(
                new SimpleItemDefinition("custom:gem", 200, false)
        );

        aggregator.registerServer("lobby", lobbyItems, Collections.emptyList());
        aggregator.registerServer("survival", survivalItems, Collections.emptyList());

        // Only 1 unified definition for "custom:gem"
        assertEquals(1, aggregator.getUnifiedItemCount());
        // The unified runtimeId should be 100 (first registered)
        ItemDefinition unified = aggregator.getUnifiedItemDefinitions().get(0);
        assertEquals("custom:gem", unified.getIdentifier());
        assertEquals(100, unified.getRuntimeId());
    }

    @Test
    void testItemRuntimeIdConflictResolution() {
        // Two different items that happen to share the same runtimeId
        List<ItemDefinition> lobbyItems = List.of(
                new SimpleItemDefinition("custom:sword", 500, false)
        );
        List<ItemDefinition> survivalItems = List.of(
                new SimpleItemDefinition("custom:bow", 500, false)
        );

        aggregator.registerServer("lobby", lobbyItems, Collections.emptyList());
        aggregator.registerServer("survival", survivalItems, Collections.emptyList());

        assertEquals(2, aggregator.getUnifiedItemCount());

        List<ItemDefinition> unified = aggregator.getUnifiedItemDefinitions();
        int swordId = unified.stream().filter(d -> d.getIdentifier().equals("custom:sword")).findFirst().get().getRuntimeId();
        int bowId = unified.stream().filter(d -> d.getIdentifier().equals("custom:bow")).findFirst().get().getRuntimeId();
        assertNotEquals(swordId, bowId, "Conflicting runtimeIds should be resolved to different unified IDs");
    }

    @Test
    void testItemDefinitionVersionIncrementsOnNewItem() {
        List<ItemDefinition> items1 = List.of(new SimpleItemDefinition("custom:a", 1, false));
        aggregator.registerServer("server1", items1, Collections.emptyList());
        int versionAfterFirst = aggregator.getItemDefinitionVersion();

        // Second server with new item should increment version
        List<ItemDefinition> items2 = List.of(new SimpleItemDefinition("custom:b", 2, false));
        aggregator.registerServer("server2", items2, Collections.emptyList());

        assertTrue(aggregator.getItemDefinitionVersion() > versionAfterFirst);
    }

    @Test
    void testItemDefinitionVersionDoesNotIncrementWithoutNewItems() {
        List<ItemDefinition> items = List.of(new SimpleItemDefinition("custom:a", 1, false));
        aggregator.registerServer("server1", items, Collections.emptyList());
        aggregator.registerServer("server2", items, Collections.emptyList());
        int versionAfterTwo = aggregator.getItemDefinitionVersion();

        // Re-register server2 with same items
        aggregator.registerServer("server2", items, Collections.emptyList());
        assertEquals(versionAfterTwo, aggregator.getItemDefinitionVersion());
    }

    // ==================== Block Aggregation ====================

    @Test
    void testRegisterBlockProperties() {
        NbtMap props = NbtMap.builder().putString("type", "custom").build();
        List<BlockPropertyData> blocks = List.of(new BlockPropertyData("custom:ore", props));

        aggregator.registerServer("server1", Collections.emptyList(), blocks);

        List<BlockPropertyData> unified = aggregator.getUnifiedBlockProperties();
        assertEquals(1, unified.size());
        assertEquals("custom:ore", unified.get(0).getName());
    }

    @Test
    void testBlockPropertyConflictFirstServerWins() {
        NbtMap props1 = NbtMap.builder().putInt("hardness", 5).build();
        NbtMap props2 = NbtMap.builder().putInt("hardness", 10).build();

        aggregator.registerServer("server1", Collections.emptyList(), List.of(new BlockPropertyData("custom:ore", props1)));
        aggregator.registerServer("server2", Collections.emptyList(), List.of(new BlockPropertyData("custom:ore", props2)));

        List<BlockPropertyData> unified = aggregator.getUnifiedBlockProperties();
        assertEquals(1, unified.size());
        // First server's properties win
        assertEquals(props1, unified.get(0).getProperties());
    }

    @Test
    void testBlockDefinitionVersionIncrements() {
        aggregator.registerServer("server1", Collections.emptyList(),
                List.of(new BlockPropertyData("custom:a", NbtMap.EMPTY)));
        int v1 = aggregator.getBlockDefinitionVersion();

        aggregator.registerServer("server2", Collections.emptyList(),
                List.of(new BlockPropertyData("custom:b", NbtMap.EMPTY)));
        assertTrue(aggregator.getBlockDefinitionVersion() > v1);
    }

    // ==================== Entity Aggregation ====================

    @Test
    void testRegisterEntityIdentifiers() {
        NbtMap entity1 = NbtMap.builder().putString("id", "custom:dragon").build();
        NbtMap identifiers = NbtMap.builder()
                .putList("idlist", NbtType.COMPOUND, List.of(entity1))
                .build();

        aggregator.registerServer("server1", Collections.emptyList(), Collections.emptyList());
        aggregator.registerEntityIdentifiers("server1", identifiers);

        NbtMap merged = aggregator.getMergedEntityIdentifiers();
        assertNotNull(merged);
        List<NbtMap> idlist = merged.getList("idlist", NbtType.COMPOUND);
        assertEquals(1, idlist.size());
        assertEquals("custom:dragon", idlist.get(0).getString("id"));
    }

    @Test
    void testMergeEntityIdentifiersFromMultipleServers() {
        NbtMap entity1 = NbtMap.builder().putString("id", "custom:dragon").build();
        NbtMap entity2 = NbtMap.builder().putString("id", "custom:golem").build();

        NbtMap ids1 = NbtMap.builder().putList("idlist", NbtType.COMPOUND, List.of(entity1)).build();
        NbtMap ids2 = NbtMap.builder().putList("idlist", NbtType.COMPOUND, List.of(entity2)).build();

        aggregator.registerServer("server1", Collections.emptyList(), Collections.emptyList());
        aggregator.registerServer("server2", Collections.emptyList(), Collections.emptyList());
        aggregator.registerEntityIdentifiers("server1", ids1);
        aggregator.registerEntityIdentifiers("server2", ids2);

        NbtMap merged = aggregator.getMergedEntityIdentifiers();
        List<NbtMap> idlist = merged.getList("idlist", NbtType.COMPOUND);
        assertEquals(2, idlist.size());
    }

    @Test
    void testDuplicateEntityIdentifierNotDuplicated() {
        NbtMap entity = NbtMap.builder().putString("id", "custom:npc").build();
        NbtMap ids = NbtMap.builder().putList("idlist", NbtType.COMPOUND, List.of(entity)).build();

        aggregator.registerServer("server1", Collections.emptyList(), Collections.emptyList());
        aggregator.registerServer("server2", Collections.emptyList(), Collections.emptyList());
        aggregator.registerEntityIdentifiers("server1", ids);
        aggregator.registerEntityIdentifiers("server2", ids);

        List<NbtMap> idlist = aggregator.getMergedEntityIdentifiers().getList("idlist", NbtType.COMPOUND);
        assertEquals(1, idlist.size());
    }

    @Test
    void testMergedEntityIdentifiersReturnsNullWhenEmpty() {
        assertNull(aggregator.getMergedEntityIdentifiers());
    }

    // ==================== Cleanup ====================

    @Test
    void testCleanupRemovedItemDefinitions() {
        List<ItemDefinition> original = List.of(
                new SimpleItemDefinition("custom:a", 1, false),
                new SimpleItemDefinition("custom:b", 2, false)
        );
        aggregator.registerServer("server1", original, Collections.emptyList());
        assertEquals(2, aggregator.getUnifiedItemCount());

        // Re-register with only "custom:a" — "custom:b" should be cleaned up
        List<ItemDefinition> updated = List.of(new SimpleItemDefinition("custom:a", 1, false));
        aggregator.registerServer("server1", updated, Collections.emptyList());
        assertEquals(1, aggregator.getUnifiedItemCount());
        assertEquals("custom:a", aggregator.getUnifiedItemDefinitions().get(0).getIdentifier());
    }

    @Test
    void testCleanupDoesNotRemoveItemUsedByOtherServer() {
        List<ItemDefinition> items = List.of(new SimpleItemDefinition("custom:shared", 1, false));
        aggregator.registerServer("server1", items, Collections.emptyList());
        aggregator.registerServer("server2", items, Collections.emptyList());

        // Remove from server1 — should still exist because server2 has it
        aggregator.registerServer("server1", Collections.emptyList(), Collections.emptyList());
        assertEquals(1, aggregator.getUnifiedItemCount());
    }

    @Test
    void testCleanupRemovedBlockProperties() {
        List<BlockPropertyData> blocks = List.of(
                new BlockPropertyData("custom:ore", NbtMap.EMPTY),
                new BlockPropertyData("custom:log", NbtMap.EMPTY)
        );
        aggregator.registerServer("server1", Collections.emptyList(), blocks);
        assertEquals(2, aggregator.getUnifiedBlockProperties().size());

        // Re-register with only "custom:ore"
        aggregator.registerServer("server1", Collections.emptyList(),
                List.of(new BlockPropertyData("custom:ore", NbtMap.EMPTY)));
        assertEquals(1, aggregator.getUnifiedBlockProperties().size());
    }

    @Test
    void testCleanupRemovedEntityIdentifiers() {
        NbtMap e1 = NbtMap.builder().putString("id", "custom:a").build();
        NbtMap e2 = NbtMap.builder().putString("id", "custom:b").build();
        NbtMap ids = NbtMap.builder().putList("idlist", NbtType.COMPOUND, List.of(e1, e2)).build();

        aggregator.registerServer("server1", Collections.emptyList(), Collections.emptyList());
        aggregator.registerEntityIdentifiers("server1", ids);
        assertEquals(2, aggregator.getMergedEntityIdentifiers().getList("idlist", NbtType.COMPOUND).size());

        // Update with only entity "custom:a"
        NbtMap updatedIds = NbtMap.builder().putList("idlist", NbtType.COMPOUND, List.of(e1)).build();
        aggregator.registerEntityIdentifiers("server1", updatedIds);
        assertEquals(1, aggregator.getMergedEntityIdentifiers().getList("idlist", NbtType.COMPOUND).size());
    }

    // ==================== ID Mapping ====================

    @Test
    void testCreateMappingIdentityWhenNoConflict() {
        List<ItemDefinition> items = List.of(new SimpleItemDefinition("custom:a", 100, false));
        aggregator.registerServer("server1", items, Collections.emptyList());

        ServerIdMapping mapping = aggregator.createMapping("server1");
        assertTrue(mapping.isIdentity());
    }

    @Test
    void testCreateMappingNonIdentityWhenConflict() {
        // server1 and server2 both claim runtimeId 500 for different items
        aggregator.registerServer("server1",
                List.of(new SimpleItemDefinition("custom:sword", 500, false)),
                Collections.emptyList());
        aggregator.registerServer("server2",
                List.of(new SimpleItemDefinition("custom:bow", 500, false)),
                Collections.emptyList());

        // server2 mapping should NOT be identity (custom:bow got a different unified id)
        ServerIdMapping mapping = aggregator.createMapping("server2");
        assertFalse(mapping.isIdentity());
        // server2's runtimeId 500 → new unified id (not 500)
        int unifiedId = mapping.translateItemId(500);
        assertNotEquals(500, unifiedId);
    }

    @Test
    void testCreateMappingForUnknownServer() {
        ServerIdMapping mapping = aggregator.createMapping("unknown");
        assertTrue(mapping.isIdentity());
    }

    @Test
    void testBuildTranslatingRegistryNullForIdentity() {
        aggregator.registerServer("server1",
                List.of(new SimpleItemDefinition("custom:a", 1, false)),
                Collections.emptyList());
        assertNull(aggregator.buildTranslatingRegistry("server1"));
    }

    @Test
    void testBuildTranslatingRegistryNonNullForConflict() {
        aggregator.registerServer("server1",
                List.of(new SimpleItemDefinition("custom:a", 500, false)),
                Collections.emptyList());
        aggregator.registerServer("server2",
                List.of(new SimpleItemDefinition("custom:b", 500, false)),
                Collections.emptyList());

        TranslatingItemRegistry registry = aggregator.buildTranslatingRegistry("server2");
        assertNotNull(registry);
    }

    // ==================== Component Data (≤1.21.50) ====================

    @Test
    void testRegisterComponentData() {
        NbtMap compData = NbtMap.builder().putString("type", "weapon").build();
        List<ItemDefinition> items = List.of(
                new SimpleItemDefinition("custom:sword", 1, null, true, compData)
        );

        assertTrue(aggregator.registerComponentData(items));
        List<ItemDefinition> result = aggregator.getUnifiedComponentItems();
        assertEquals(1, result.size());
        assertEquals("custom:sword", result.get(0).getIdentifier());
        assertNotNull(result.get(0).getComponentData());
    }

    @Test
    void testRegisterComponentDataFirstServerWins() {
        NbtMap data1 = NbtMap.builder().putString("source", "server1").build();
        NbtMap data2 = NbtMap.builder().putString("source", "server2").build();

        aggregator.registerComponentData(List.of(new SimpleItemDefinition("custom:item", 1, null, true, data1)));
        aggregator.registerComponentData(List.of(new SimpleItemDefinition("custom:item", 1, null, true, data2)));

        List<ItemDefinition> result = aggregator.getUnifiedComponentItems();
        assertEquals(1, result.size());
        assertEquals("server1", result.get(0).getComponentData().getString("source"));
    }

    // ==================== Block Network IDs Hashed ====================

    @Test
    void testBlockNetworkIdsHashedFlag() {
        aggregator.registerBlockNetworkIdsHashed("server1", true);
        aggregator.registerBlockNetworkIdsHashed("server2", false);

        assertEquals(true, aggregator.getBlockNetworkIdsHashed("server1"));
        assertEquals(false, aggregator.getBlockNetworkIdsHashed("server2"));
        assertNull(aggregator.getBlockNetworkIdsHashed("unknown"));
    }

    // ==================== Server Snapshot ====================

    @Test
    void testGetServerSnapshot() {
        List<ItemDefinition> items = List.of(new SimpleItemDefinition("custom:x", 1, false));
        List<BlockPropertyData> blocks = List.of(new BlockPropertyData("custom:block", NbtMap.EMPTY));
        aggregator.registerServer("server1", items, blocks);

        DefinitionAggregator.ServerSnapshot snapshot = aggregator.getServerSnapshot("server1");
        assertNotNull(snapshot);
        assertEquals(1, snapshot.getItemDefinitions().size());
        assertEquals(1, snapshot.getBlockProperties().size());
    }

    @Test
    void testGetServerSnapshotReturnsNullForUnknown() {
        assertNull(aggregator.getServerSnapshot("nonexistent"));
    }

    // --- Block runtime id aggregation (sequential palette mode) ---

    @Test
    void testUnifiedBlockOrderStableIndexIsUnifiedRuntimeId() {
        aggregator.registerServer("server1", Collections.emptyList(),
                List.of(new BlockPropertyData("custom:a", NbtMap.EMPTY),
                        new BlockPropertyData("custom:b", NbtMap.EMPTY)));

        List<String> order = aggregator.getUnifiedBlockOrder();
        assertEquals(List.of("custom:a", "custom:b"), order);
        // unified runtime id == position in the order list
        assertEquals("custom:a", order.get(0));
        assertEquals("custom:b", order.get(1));
    }

    @Test
    void testBlockMappingIdentityInHashMode() {
        // Hash mode: block ids are content-derived, no translation needed.
        aggregator.registerServer("server1", Collections.emptyList(),
                List.of(new BlockPropertyData("custom:a", NbtMap.EMPTY)));
        aggregator.registerBlockNetworkIdsHashed("server1", true);

        ServerIdMapping mapping = aggregator.createMapping("server1");
        assertTrue(mapping.isBlockIdentity());
        // buildTranslatingBlockRegistry returns null in hash mode (no translation needed)
        assertNull(aggregator.buildTranslatingBlockRegistry("server1"));
    }

    @Test
    void testBlockMappingNonIdentityInSequentialMode() {
        // Two servers register custom blocks in different orders -> positions differ.
        aggregator.registerServer("server1", Collections.emptyList(),
                List.of(new BlockPropertyData("custom:a", NbtMap.EMPTY),
                        new BlockPropertyData("custom:b", NbtMap.EMPTY)));
        aggregator.registerBlockNetworkIdsHashed("server1", false);

        aggregator.registerServer("server2", Collections.emptyList(),
                List.of(new BlockPropertyData("custom:b", NbtMap.EMPTY),
                        new BlockPropertyData("custom:a", NbtMap.EMPTY)));
        aggregator.registerBlockNetworkIdsHashed("server2", false);

        // Unified order: server1 registered first -> [custom:a=0, custom:b=1]
        // server2's positions: custom:b at pos 0, custom:a at pos 1
        // So server2 needs translation: pos0(b)->unified1, pos1(a)->unified0
        ServerIdMapping mapping2 = aggregator.createMapping("server2");
        assertFalse(mapping2.isBlockIdentity());
        // server2 pos 0 (custom:b) -> unified id 1
        assertEquals(1, mapping2.translateBlockId(0));
        // server2 pos 1 (custom:a) -> unified id 0
        assertEquals(0, mapping2.translateBlockId(1));
        // reverse: unified 0 -> server2 pos 1, unified 1 -> server2 pos 0
        assertEquals(1, mapping2.reverseTranslateBlockId(0));
        assertEquals(0, mapping2.reverseTranslateBlockId(1));
    }

    @Test
    void testBuildTranslatingBlockRegistrySequentialMode() {
        aggregator.registerServer("server1", Collections.emptyList(),
                List.of(new BlockPropertyData("custom:a", NbtMap.EMPTY),
                        new BlockPropertyData("custom:b", NbtMap.EMPTY)));
        aggregator.registerBlockNetworkIdsHashed("server1", false);

        TranslatingBlockRegistry registry = aggregator.buildTranslatingBlockRegistry("server1");
        // server1's positions match unified order, so it should still build a registry,
        // but the ids are identity (pos 0 -> unified 0, pos 1 -> unified 1).
        assertNotNull(registry);
        assertEquals(0, registry.getDefinition(0).getRuntimeId());
        assertEquals(1, registry.getDefinition(1).getRuntimeId());
    }

    @Test
    void testBuildTranslatingBlockRegistryReturnsNullForUnknownServer() {
        assertNull(aggregator.buildTranslatingBlockRegistry("nonexistent"));
    }
}
