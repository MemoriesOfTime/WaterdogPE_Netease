package dev.waterdog.waterdogpe.network.protocol.registry;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerIdMappingTest {

    @Test
    void testIdentityMapping() {
        ServerIdMapping identity = ServerIdMapping.IDENTITY;
        assertTrue(identity.isIdentity());
        // Identity returns original values
        assertEquals(42, identity.translateItemId(42));
        assertEquals(42, identity.reverseTranslateItemId(42));
        assertTrue(identity.isKnownUnified(999));
    }

    @Test
    void testTranslateServerToUnified() {
        Int2IntMap s2u = new Int2IntOpenHashMap();
        Int2IntMap u2s = new Int2IntOpenHashMap();
        s2u.put(100, 200);
        u2s.put(200, 100);

        ServerIdMapping mapping = new ServerIdMapping(s2u, u2s);
        assertFalse(mapping.isIdentity());
        assertEquals(200, mapping.translateItemId(100));
    }

    @Test
    void testReverseTranslateUnifiedToServer() {
        Int2IntMap s2u = new Int2IntOpenHashMap();
        Int2IntMap u2s = new Int2IntOpenHashMap();
        s2u.put(100, 200);
        u2s.put(200, 100);

        ServerIdMapping mapping = new ServerIdMapping(s2u, u2s);
        assertEquals(100, mapping.reverseTranslateItemId(200));
    }

    @Test
    void testUnknownIdReturnsOriginal() {
        Int2IntMap s2u = new Int2IntOpenHashMap();
        Int2IntMap u2s = new Int2IntOpenHashMap();
        s2u.put(10, 20);
        u2s.put(20, 10);

        ServerIdMapping mapping = new ServerIdMapping(s2u, u2s);
        // Unknown server id returns itself
        assertEquals(999, mapping.translateItemId(999));
        // Unknown unified id returns itself
        assertEquals(888, mapping.reverseTranslateItemId(888));
    }

    @Test
    void testIsKnownUnified() {
        Int2IntMap s2u = new Int2IntOpenHashMap();
        Int2IntMap u2s = new Int2IntOpenHashMap();
        s2u.put(100, 200);
        u2s.put(200, 100);

        ServerIdMapping mapping = new ServerIdMapping(s2u, u2s);
        assertTrue(mapping.isKnownUnified(200));
        assertFalse(mapping.isKnownUnified(999));
    }

    @Test
    void testMultipleMappings() {
        Int2IntMap s2u = new Int2IntOpenHashMap();
        Int2IntMap u2s = new Int2IntOpenHashMap();
        s2u.put(10, 100);
        s2u.put(20, 200);
        s2u.put(30, 300);
        u2s.put(100, 10);
        u2s.put(200, 20);
        u2s.put(300, 30);

        ServerIdMapping mapping = new ServerIdMapping(s2u, u2s);
        assertEquals(100, mapping.translateItemId(10));
        assertEquals(200, mapping.translateItemId(20));
        assertEquals(300, mapping.translateItemId(30));
        assertEquals(10, mapping.reverseTranslateItemId(100));
        assertEquals(20, mapping.reverseTranslateItemId(200));
        assertEquals(30, mapping.reverseTranslateItemId(300));
    }
}
