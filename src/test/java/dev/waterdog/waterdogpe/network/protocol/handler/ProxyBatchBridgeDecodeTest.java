/*
 * Copyright 2026 WaterdogTEAM
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

package dev.waterdog.waterdogpe.network.protocol.handler;

import dev.waterdog.waterdogpe.network.connection.ProxiedConnection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.PacketDirection;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.BedrockPacketSerializer;
import org.cloudburstmc.protocol.bedrock.codec.v819.Bedrock_v819;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A packet the proxy cannot decode must still reach the peer. Dropping it costs the flows the proxy
 * does not participate in, and letting the failure escape costs the whole batch (downstream, where
 * {@code BedrockClientConnection} swallows it) or the connection (upstream, where nothing catches it).
 * <p>
 * Both failure modes degrade to an {@link UnknownPacket} holding the raw payload, which the encoder
 * forwards byte for byte, exactly like a packet id that is not registered in the codec at all.
 */
public class ProxyBatchBridgeDecodeTest {

    private static final byte[] HEADER = {0x42};
    private static final byte[] PAYLOAD = {0x01, 0x02, 0x03, 0x04};

    /**
     * Direction of the upstream bridge: inbound packets are server-bound, so a CLIENT-only packet
     * arriving here is a recipient mismatch.
     */
    private static final PacketDirection DIRECTION = PacketDirection.CLIENT_BOUND;

    private static final BedrockPacketSerializer<TextPacket> FAILING_SERIALIZER = new BedrockPacketSerializer<>() {
        @Override
        public void serialize(ByteBuf buffer, BedrockCodecHelper helper, TextPacket packet) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, TextPacket packet) {
            throw new IllegalStateException("malformed packet");
        }
    };

    private BedrockBatchWrapper batch;

    @AfterEach
    void tearDown() {
        if (this.batch != null && this.batch.refCnt() > 0) {
            this.batch.release();
        }
    }

    private BedrockPacketWrapper feed(BedrockCodec codec, int packetId) {
        ByteBuf buffer = Unpooled.buffer();
        buffer.writeBytes(HEADER);
        buffer.writeBytes(PAYLOAD);

        BedrockPacketWrapper wrapper = BedrockPacketWrapper.create(packetId, 0, 0, null, buffer);
        wrapper.setHeaderLength(HEADER.length);

        // Add directly instead of addPacket() so the batch starts out unmodified.
        this.batch = BedrockBatchWrapper.newInstance();
        this.batch.getPackets().add(wrapper);

        ProxyPacketHandler handler = mock(ProxyPacketHandler.class);
        when(handler.handlePacket(any())).thenReturn(PacketSignal.UNHANDLED);
        when(handler.doPacketRewrite(any())).thenReturn(PacketSignal.UNHANDLED);

        ProxiedConnection source = mock(ProxiedConnection.class);
        when(source.getPacketDirection()).thenReturn(DIRECTION);

        new ProxyBatchBridge(codec, codec.createHelper(), handler, DIRECTION).onBedrockBatch(source, this.batch);
        return wrapper;
    }

    private void assertForwardedUntouched(BedrockPacketWrapper wrapper, int packetId) {
        assertEquals(1, this.batch.getPackets().size(), "an undecodable packet must stay in the batch");
        assertFalse(this.batch.isModified(), "passing a packet through must not force the batch to be re-encoded");

        UnknownPacket unknown = assertInstanceOf(UnknownPacket.class, wrapper.getPacket(),
                "an undecodable packet degrades to UnknownPacket");
        assertEquals(packetId, unknown.getPacketId());
        assertEquals(ByteBufUtil.hexDump(Unpooled.wrappedBuffer(PAYLOAD)), ByteBufUtil.hexDump(unknown.getPayload()),
                "the raw payload must survive so the packet is forwarded byte-exact");

        assertNotNull(wrapper.getPacketBuffer(), "the original buffer is what the encoder forwards");
        assertEquals(HEADER.length + PAYLOAD.length, wrapper.getPacketBuffer().readableBytes());
    }

    @Test
    void deserializationFailureIsForwardedInsteadOfKillingTheBatch() {
        BedrockCodec codec = Bedrock_v819.CODEC.toBuilder()
                .updateSerializer(TextPacket.class, FAILING_SERIALIZER)
                .build();
        int packetId = codec.getPacketDefinition(TextPacket.class).getId();

        assertForwardedUntouched(this.feed(codec, packetId), packetId);
    }

    @Test
    void recipientMismatchIsForwardedInsteadOfDropped() {
        BedrockCodec codec = Bedrock_v819.CODEC;
        // PlayStatusPacket is CLIENT-only, so decoding it as a server-bound packet is a mismatch.
        int packetId = codec.getPacketDefinition(PlayStatusPacket.class).getId();

        assertForwardedUntouched(this.feed(codec, packetId), packetId);
    }
}
