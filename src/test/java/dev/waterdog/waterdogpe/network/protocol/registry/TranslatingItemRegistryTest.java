package dev.waterdog.waterdogpe.network.protocol.registry;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TranslatingItemRegistryTest {

    @Test
    void testGetKnownDefinition() {
        Int2ObjectMap<ItemDefinition> map = new Int2ObjectOpenHashMap<>();
        // Server runtimeId 100 maps to unified definition with runtimeId 200
        ItemDefinition unifiedDef = new SimpleItemDefinition("custom:sword", 200, false);
        map.put(100, unifiedDef);

        TranslatingItemRegistry registry = new TranslatingItemRegistry(map);
        ItemDefinition result = registry.getDefinition(100);
        assertEquals("custom:sword", result.getIdentifier());
        assertEquals(200, result.getRuntimeId());
    }

    @Test
    void testGetUnknownDefinitionReturnsPlaceholder() {
        Int2ObjectMap<ItemDefinition> map = new Int2ObjectOpenHashMap<>();
        TranslatingItemRegistry registry = new TranslatingItemRegistry(map);

        ItemDefinition result = registry.getDefinition(999);
        assertEquals("unknown", result.getIdentifier());
        assertEquals(999, result.getRuntimeId());
    }

    @Test
    void testIsRegisteredTrue() {
        Int2ObjectMap<ItemDefinition> map = new Int2ObjectOpenHashMap<>();
        ItemDefinition def = new SimpleItemDefinition("custom:gem", 50, false);
        map.put(50, def);

        TranslatingItemRegistry registry = new TranslatingItemRegistry(map);
        assertTrue(registry.isRegistered(def));
    }

    @Test
    void testIsRegisteredFalseForUnknown() {
        Int2ObjectMap<ItemDefinition> map = new Int2ObjectOpenHashMap<>();
        TranslatingItemRegistry registry = new TranslatingItemRegistry(map);

        ItemDefinition unknown = new SimpleItemDefinition("custom:nope", 777, false);
        assertFalse(registry.isRegistered(unknown));
    }

    @Test
    void testMultipleDefinitions() {
        Int2ObjectMap<ItemDefinition> map = new Int2ObjectOpenHashMap<>();
        ItemDefinition def1 = new SimpleItemDefinition("custom:a", 10, false);
        ItemDefinition def2 = new SimpleItemDefinition("custom:b", 20, false);
        map.put(1, def1);
        map.put(2, def2);

        TranslatingItemRegistry registry = new TranslatingItemRegistry(map);
        assertEquals(10, registry.getDefinition(1).getRuntimeId());
        assertEquals(20, registry.getDefinition(2).getRuntimeId());
    }
}
