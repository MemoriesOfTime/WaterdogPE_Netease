package dev.waterdog.waterdogpe.network.protocol.registry;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ReverseItemRewriterTest {

    private ServerIdMapping createMapping(int serverId, int unifiedId) {
        Int2IntMap s2u = new Int2IntOpenHashMap();
        Int2IntMap u2s = new Int2IntOpenHashMap();
        s2u.put(serverId, unifiedId);
        u2s.put(unifiedId, serverId);
        return new ServerIdMapping(s2u, u2s);
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
}
