package dev.waterdog.waterdogpe.network.protocol.registry;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TranslatingBlockRegistryTest {

    private static final NbtMap EMPTY_PROPS = NbtMap.EMPTY;

    @Test
    void testGetKnownDefinitionTranslatesServerToUnified() {
        Int2ObjectMap<BlockDefinition> map = new Int2ObjectOpenHashMap<>();
        // Server block runtimeId 5 -> unified definition with runtimeId 8
        BlockDefinition unifiedDef = new SimpleBlockDefinition("custom:ore", 8, EMPTY_PROPS);
        map.put(5, unifiedDef);

        TranslatingBlockRegistry registry = new TranslatingBlockRegistry(map);
        BlockDefinition result = registry.getDefinition(5);
        assertEquals("custom:ore", ((SimpleBlockDefinition) result).getIdentifier());
        assertEquals(8, result.getRuntimeId());
    }

    @Test
    void testGetUnknownDefinitionReturnsPlaceholderWithOriginalId() {
        Int2ObjectMap<BlockDefinition> map = new Int2ObjectOpenHashMap<>();
        TranslatingBlockRegistry registry = new TranslatingBlockRegistry(map);

        BlockDefinition result = registry.getDefinition(999);
        // Unknown id must pass through unchanged so passthrough decoding still works
        assertEquals(999, result.getRuntimeId());
    }

    @Test
    void testIsRegisteredTrue() {
        Int2ObjectMap<BlockDefinition> map = new Int2ObjectOpenHashMap<>();
        // isRegistered looks up by the definition's own runtimeId, so the map key must match it.
        BlockDefinition def = new SimpleBlockDefinition("custom:block", 50, EMPTY_PROPS);
        map.put(50, def);

        TranslatingBlockRegistry registry = new TranslatingBlockRegistry(map);
        assertTrue(registry.isRegistered(def));
    }

    @Test
    void testIsRegisteredFalseForUnknown() {
        Int2ObjectMap<BlockDefinition> map = new Int2ObjectOpenHashMap<>();
        BlockDefinition present = new SimpleBlockDefinition("custom:present", 50, EMPTY_PROPS);
        map.put(50, present);
        TranslatingBlockRegistry registry = new TranslatingBlockRegistry(map);

        // A different definition at a runtimeId not in the map
        BlockDefinition unknown = new SimpleBlockDefinition("custom:nope", 777, EMPTY_PROPS);
        assertFalse(registry.isRegistered(unknown));
    }

    @Test
    void testMultipleDefinitions() {
        Int2ObjectMap<BlockDefinition> map = new Int2ObjectOpenHashMap<>();
        BlockDefinition def1 = new SimpleBlockDefinition("custom:a", 10, EMPTY_PROPS);
        BlockDefinition def2 = new SimpleBlockDefinition("custom:b", 20, EMPTY_PROPS);
        map.put(1, def1);
        map.put(2, def2);

        TranslatingBlockRegistry registry = new TranslatingBlockRegistry(map);
        assertEquals(10, registry.getDefinition(1).getRuntimeId());
        assertEquals(20, registry.getDefinition(2).getRuntimeId());
    }
}
