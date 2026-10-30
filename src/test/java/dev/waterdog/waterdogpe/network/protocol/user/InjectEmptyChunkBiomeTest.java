package dev.waterdog.waterdogpe.network.protocol.user;

import dev.waterdog.waterdogpe.network.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class InjectEmptyChunkBiomeTest {

    /**
     * One biome palette as written by {@code writePalette}: 1 byte header, 512 bytes of packed
     * entries (4096 values, 1 bit per value), 1 byte palette size and 1 byte palette entry.
     */
    private static final int BIOME_PALETTE_DATA_SIZE = 512;
    private static final int BIOME_PALETTE_SIZE = 1 + BIOME_PALETTE_DATA_SIZE + 2;
    private static final byte BIOME_PALETTE_HEADER = (1 << 1) | 1;
    private static final byte BIOME_PALETTE_LINK = (byte) ((127 << 1) | 1);

    @Test
    void requestModePayloadCarriesBiomesForEveryDimension() {
        assertBiomePayload(PlayerRewriteUtils.DIMENSION_OVERWORLD, 24);
        assertBiomePayload(PlayerRewriteUtils.DIMENSION_NETHER, 8);
        assertBiomePayload(PlayerRewriteUtils.DIMENSION_END, 16);
    }

    @Test
    void fullChunkPayloadKeepsSectionHeaderAndBiomes() {
        LevelChunkPacket packet = PlayerRewriteUtils.injectEmptyChunk(
                0, 0, PlayerRewriteUtils.DIMENSION_NETHER, ProtocolVersion.MINECRAFT_PE_1_21_130, false);
        try {
            assertFalse(packet.isRequestSubChunks());
            assertEquals(1, packet.getSubChunksLength());

            byte[] data = bytes(packet);
            assertEquals(2 + BIOME_PALETTE_SIZE + 7 + 1, data.length);
            assertEquals(8, data[0], "section version");
            assertEquals(0, data[1], "zero block storages");
            assertEquals(BIOME_PALETTE_HEADER, data[2]);
            assertEquals(0, data[data.length - 1], "border block byte terminates the payload");
        } finally {
            packet.release();
        }
    }

    @Test
    void requestModePayloadMatchesTheFullChunkBiomes() {
        assertBiomesMatch(PlayerRewriteUtils.DIMENSION_OVERWORLD);
        assertBiomesMatch(PlayerRewriteUtils.DIMENSION_NETHER);
        assertBiomesMatch(PlayerRewriteUtils.DIMENSION_END);
    }

    @Test
    void requestModeBefore11830FallsBackToFullChunk() {
        LevelChunkPacket packet = PlayerRewriteUtils.injectEmptyChunk(
                0, 0, PlayerRewriteUtils.DIMENSION_OVERWORLD, ProtocolVersion.MINECRAFT_PE_1_18_0, true);
        try {
            assertFalse(packet.isRequestSubChunks(), "request mode is not serializable before 1.18.30");
            assertEquals(1, packet.getSubChunksLength());
            assertTrue(packet.getData().isReadable());
        } finally {
            packet.release();
        }
    }

    /**
     * The request mode payload is the full fake chunk without its two byte section header, so the
     * biome layout can never drift between the two paths.
     */
    private static void assertBiomesMatch(int dimension) {
        LevelChunkPacket full = PlayerRewriteUtils.injectEmptyChunk(
                0, 0, dimension, ProtocolVersion.MINECRAFT_PE_1_21_130, false);
        LevelChunkPacket request = PlayerRewriteUtils.injectEmptyChunk(
                0, 0, dimension, ProtocolVersion.MINECRAFT_PE_1_21_130, true);
        try {
            byte[] fullData = bytes(full);
            assertArrayEquals(
                    Arrays.copyOfRange(fullData, 2, fullData.length),
                    bytes(request));
        } finally {
            full.release();
            request.release();
        }
    }

    private static void assertBiomePayload(int dimension, int biomeSections) {
        LevelChunkPacket packet = PlayerRewriteUtils.injectEmptyChunk(
                0, 0, dimension, ProtocolVersion.MINECRAFT_PE_1_21_130, true);
        try {
            assertTrue(packet.isRequestSubChunks());

            byte[] data = bytes(packet);
            assertEquals(BIOME_PALETTE_SIZE + (biomeSections - 1) + 1, data.length,
                    "payload must be one biome palette, one link per remaining section and a border byte");
            assertEquals(BIOME_PALETTE_HEADER, data[0], "first biome section carries the runtime palette");
            for (int i = 1; i <= BIOME_PALETTE_DATA_SIZE; i++) {
                assertEquals(0, data[i], "packed biome data must stay zero at index " + i);
            }
            assertEquals(2, data[1 + BIOME_PALETTE_DATA_SIZE], "palette size of 1, zigzag varint encoded");
            assertEquals(0, data[2 + BIOME_PALETTE_DATA_SIZE], "single palette entry, biome runtime id 0");
            for (int i = 0; i < biomeSections - 1; i++) {
                assertEquals(BIOME_PALETTE_LINK, data[BIOME_PALETTE_SIZE + i],
                        "every biome section after the first must link to the previous palette");
            }
            assertEquals(0, data[data.length - 1], "border block byte terminates the payload");
        } finally {
            packet.release();
        }
    }

    private static byte[] bytes(LevelChunkPacket packet) {
        ByteBuf data = packet.getData();
        byte[] copy = new byte[data.readableBytes()];
        data.getBytes(data.readerIndex(), copy);
        return copy;
    }
}
