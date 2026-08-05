package dev.waterdog.waterdogpe.network.protocol.registry;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ReverseItemRewriterTest {

    private ServerIdMapping createMapping(int serverId, int unifiedId) {
        Int2IntMap s2u = new Int2IntOpenHashMap();
        Int2IntMap u2s = new Int2IntOpenHashMap();
        s2u.put(serverId, unifiedId);
        u2s.put(unifiedId, serverId);
        return new ServerIdMapping(s2u, u2s);
    }

    /**
     * Mapping where item ids and block ids both need translation (sequential palette mode).
     */
    private ServerIdMapping createBlockMapping(int serverItemId, int unifiedItemId,
                                               int serverBlockId, int unifiedBlockId) {
        Int2IntMap is2u = new Int2IntOpenHashMap();
        Int2IntMap iu2s = new Int2IntOpenHashMap();
        Int2IntMap bs2u = new Int2IntOpenHashMap();
        Int2IntMap bu2s = new Int2IntOpenHashMap();
        is2u.put(serverItemId, unifiedItemId);
        iu2s.put(unifiedItemId, serverItemId);
        bs2u.put(serverBlockId, unifiedBlockId);
        bu2s.put(unifiedBlockId, serverBlockId);
        return new ServerIdMapping(is2u, iu2s, bs2u, bu2s);
    }

    @Test
    void testIdentityMappingSkipsRewrite() {
        ReverseItemRewriter rewriter = new ReverseItemRewriter(ServerIdMapping.IDENTITY);
        MobEquipmentPacket packet = new MobEquipmentPacket();
        packet.setItem(ItemData.AIR);

        assertEquals(PacketSignal.UNHANDLED, rewriter.doRewrite(packet));
    }

    @Test
    void testNullMappingSkipsRewrite() {
        ReverseItemRewriter rewriter = new ReverseItemRewriter(null);
        MobEquipmentPacket packet = new MobEquipmentPacket();

        assertEquals(PacketSignal.UNHANDLED, rewriter.doRewrite(packet));
    }

    @Test
    void testMobEquipmentPacketRewrite() {
        // unified 200 -> server 100
        ServerIdMapping mapping = createMapping(100, 200);
        ReverseItemRewriter rewriter = new ReverseItemRewriter(mapping);

        ItemDefinition unifiedDef = new SimpleItemDefinition("custom:sword", 200, false);
        ItemData item = ItemData.builder().definition(unifiedDef).count(1).build();

        MobEquipmentPacket packet = new MobEquipmentPacket();
        packet.setItem(item);

        PacketSignal result = rewriter.doRewrite(packet);
        assertEquals(PacketSignal.HANDLED, result);
        assertEquals(100, packet.getItem().getDefinition().getRuntimeId());
    }

    @Test
    void testMobEquipmentAirNotRewritten() {
        ServerIdMapping mapping = createMapping(100, 200);
        ReverseItemRewriter rewriter = new ReverseItemRewriter(mapping);

        MobEquipmentPacket packet = new MobEquipmentPacket();
        packet.setItem(ItemData.AIR);

        assertEquals(PacketSignal.UNHANDLED, rewriter.doRewrite(packet));
    }

    @Test
    void testUnknownUnifiedIdReplacedWithAir() {
        // Only unified id 200 is known to this server
        ServerIdMapping mapping = createMapping(100, 200);
        ReverseItemRewriter rewriter = new ReverseItemRewriter(mapping);

        // Item with unified id 999 — not known to this server
        ItemDefinition unknownDef = new SimpleItemDefinition("custom:other_server_item", 999, false);
        ItemData item = ItemData.builder().definition(unknownDef).count(1).build();

        MobEquipmentPacket packet = new MobEquipmentPacket();
        packet.setItem(item);

        PacketSignal result = rewriter.doRewrite(packet);
        assertEquals(PacketSignal.HANDLED, result);
        assertSame(ItemData.AIR, packet.getItem());
    }

    @Test
    void testNoRewriteWhenIdsMatch() {
        // unified id == server id, but mapping is not identity
        Int2IntMap s2u = new Int2IntOpenHashMap();
        Int2IntMap u2s = new Int2IntOpenHashMap();
        s2u.put(100, 100);  // same id
        s2u.put(200, 300);  // different id for another item
        u2s.put(100, 100);
        u2s.put(300, 200);
        ServerIdMapping mapping = new ServerIdMapping(s2u, u2s);
        ReverseItemRewriter rewriter = new ReverseItemRewriter(mapping);

        ItemDefinition def = new SimpleItemDefinition("custom:same_id_item", 100, false);
        ItemData item = ItemData.builder().definition(def).count(1).build();

        MobEquipmentPacket packet = new MobEquipmentPacket();
        packet.setItem(item);

        // id 100 maps to 100, no change needed
        PacketSignal result = rewriter.doRewrite(packet);
        assertEquals(PacketSignal.UNHANDLED, result);
    }

    // --- blockRuntimeId reverse translation (sequential palette mode) ---

    @Test
    void testBlockRuntimeIdReverseTranslated() {
        // item unified 200 -> server 100; block unified 8 -> server 5
        ServerIdMapping mapping = createBlockMapping(100, 200, 5, 8);
        ReverseItemRewriter rewriter = new ReverseItemRewriter(mapping);

        ItemDefinition unifiedItem = new SimpleItemDefinition("custom:ore_item", 200, false);
        BlockDefinition unifiedBlock = new SimpleBlockDefinition("custom:ore", 8, NbtMap.EMPTY);
        ItemData item = ItemData.builder()
                .definition(unifiedItem)
                .blockDefinition(unifiedBlock)
                .count(1)
                .build();

        MobEquipmentPacket packet = new MobEquipmentPacket();
        packet.setItem(item);

        PacketSignal result = rewriter.doRewrite(packet);
        assertEquals(PacketSignal.HANDLED, result);
        // both ids translated back to server values
        assertEquals(100, packet.getItem().getDefinition().getRuntimeId());
        assertNotNull(packet.getItem().getBlockDefinition());
        assertEquals(5, packet.getItem().getBlockDefinition().getRuntimeId());
    }

    @Test
    void testBlockIdentityMappingDoesNotTouchBlockId() {
        // Item side non-identity, block side identity (two-arg constructor)
        ServerIdMapping mapping = createMapping(100, 200);
        ReverseItemRewriter rewriter = new ReverseItemRewriter(mapping);

        ItemDefinition unifiedItem = new SimpleItemDefinition("custom:ore_item", 200, false);
        BlockDefinition block = new SimpleBlockDefinition("custom:ore", 8, NbtMap.EMPTY);
        ItemData item = ItemData.builder()
                .definition(unifiedItem)
                .blockDefinition(block)
                .count(1)
                .build();

        MobEquipmentPacket packet = new MobEquipmentPacket();
        packet.setItem(item);

        PacketSignal result = rewriter.doRewrite(packet);
        assertEquals(PacketSignal.HANDLED, result);
        // item id translated, block id left at 8 (identity)
        assertEquals(100, packet.getItem().getDefinition().getRuntimeId());
        assertEquals(8, packet.getItem().getBlockDefinition().getRuntimeId());
    }

    @Test
    void testUnknownUnifiedBlockIdDroppedToAir() {
        // Only unified block id 8 is known; item 999's block (id 42) is exclusive to another server.
        ServerIdMapping mapping = createBlockMapping(100, 200, 5, 8);
        ReverseItemRewriter rewriter = new ReverseItemRewriter(mapping);

        ItemDefinition unknownItem = new SimpleItemDefinition("custom:other", 999, false);
        // make this item known so it isn't replaced wholesale with AIR (we want to isolate block handling)
        // Reuse a known item id but attach an unknown block to exercise the block branch.
        ItemDefinition knownItem = new SimpleItemDefinition("custom:known", 200, false);
        BlockDefinition unknownBlock = new SimpleBlockDefinition("custom:exclusive_block", 42, NbtMap.EMPTY);
        ItemData item = ItemData.builder()
                .definition(knownItem)
                .blockDefinition(unknownBlock)
                .count(1)
                .build();

        MobEquipmentPacket packet = new MobEquipmentPacket();
        packet.setItem(item);

        PacketSignal result = rewriter.doRewrite(packet);
        assertEquals(PacketSignal.HANDLED, result);
        // item id translated (200->100), block binding dropped to AIR's block definition
        assertEquals(100, packet.getItem().getDefinition().getRuntimeId());
        assertSame(ItemData.AIR.getBlockDefinition(), packet.getItem().getBlockDefinition());
    }

    @Test
    void testInventoryTransactionPacketRewritesItemInHand() {
        // Cover the InventoryTransactionPacket path which the original test suite only exercised via MobEquipment.
        ServerIdMapping mapping = createMapping(100, 200);
        ReverseItemRewriter rewriter = new ReverseItemRewriter(mapping);

        ItemDefinition unifiedDef = new SimpleItemDefinition("custom:sword", 200, false);
        ItemData item = ItemData.builder().definition(unifiedDef).count(1).build();

        InventoryTransactionPacket packet = new InventoryTransactionPacket();
        packet.setItemInHand(item);

        PacketSignal result = rewriter.doRewrite(packet);
        assertEquals(PacketSignal.HANDLED, result);
        assertEquals(100, packet.getItemInHand().getDefinition().getRuntimeId());
    }
}
