/*
 * Copyright 2023 WaterdogTEAM
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
import dev.waterdog.waterdogpe.network.connection.codec.packet.BedrockPacketCodec;
import dev.waterdog.waterdogpe.network.protocol.Signals;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import lombok.Data;
import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.protocol.bedrock.PacketDirection;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.cloudburstmc.protocol.common.util.Preconditions;

import java.util.ListIterator;

@Data
@Log4j2
public class ProxyBatchBridge implements BedrockPacketHandler {
    private BedrockCodec codec;
    private BedrockCodecHelper helper;

    private ProxyPacketHandler handler;
    private boolean forceEncode;
    private PacketDirection direction;
    private final IntSet undecodablePacketIds = new IntOpenHashSet();

    public ProxyBatchBridge(BedrockCodec codec, BedrockCodecHelper helper, ProxyPacketHandler handler, PacketDirection direction) {
        this.codec = codec;
        this.helper = helper;
        this.direction = direction;
        this.setHandler(handler);
    }

    public void onBedrockBatch(ProxiedConnection source, BedrockBatchWrapper batch) {
        ListIterator<BedrockPacketWrapper> iterator = batch.getPackets().listIterator();
        while (iterator.hasNext()) {
            BedrockPacketWrapper wrapper = iterator.next();
            if (wrapper.getPacket() == null) {
                this.decodePacket(wrapper, source.getPacketDirection());
            }

            if (wrapper.getPacket() == null) {
                if (wrapper.getPacketBuffer() == null) {
                    // Nothing decoded and nothing to forward: this wrapper carries no packet at all.
                    log.debug("Removing empty packet from batch (packetId={})", wrapper.getPacketId());
                    iterator.remove();
                    wrapper.release();
                    batch.modify();
                }
                // Otherwise keep it. A wrapper that still owns its buffer is forwarded byte-exact by
                // the encoder, which always beats dropping a packet we merely could not read.
                continue;
            }

            PacketSignal signal = this.handlePacket(wrapper.getPacket());
            if (this.isForceEncode() || signal == PacketSignal.HANDLED) {
                ReferenceCountUtil.release(wrapper.getPacketBuffer());
                wrapper.setPacketBuffer(null); // clear cached buffer
                batch.modify();
            } else if (signal == Signals.CANCEL) {
                iterator.remove(); // remove from batch
                wrapper.release(); // release
                batch.modify();
            }
        }

        if (!batch.getPackets().isEmpty()) {
            this.sendProxiedBatch(batch);
        }
    }

    @Override
    public PacketSignal handlePacket(BedrockPacket packet) {
        try {
            PacketSignal signal = this.handler.handlePacket(packet);
            PacketSignal rewriteSignal = this.handler.doPacketRewrite(packet);
            if (this.direction.getInbound() == PacketRecipient.CLIENT) { // only track packets sent by downstream
                this.handler.getRewriteMaps().getEntityTracker().trackEntity(packet);
            }
            return Signals.mergeSignals(signal, rewriteSignal);
        } catch (Exception e) {
            throw new IllegalStateException("Error while handling " + packet.getPacketType(), e);
        }
    }

    private void decodePacket(BedrockPacketWrapper wrapper, PacketDirection direction) {
        ByteBuf msg = wrapper.getPacketBuffer().retainedSlice();
        try {
            msg.skipBytes(wrapper.getHeaderLength()); // skip header
            wrapper.setPacket(this.codec.tryDecode(helper, msg, wrapper.getPacketId(), direction.getInbound()));
        } catch (IllegalArgumentException e) {
            // Sent to the wrong recipient. Forward it untouched rather than dropping it: the peer
            // ignores what it does not expect, while a silent drop breaks flows the proxy is not
            // part of and leaves no trace of where the packet went.
            this.passThroughUndecodable(wrapper, "wrong direction", e);
        } catch (Throwable t) {
            // One packet the proxy can not read must never cost its whole batch, let alone the
            // connection. Degrade it to a byte-exact passthrough, like an unregistered packet id.
            this.passThroughUndecodable(wrapper, "failed to decode", t);
        } finally {
            msg.release();
        }
    }

    /**
     * Replaces an undecodable packet with an {@link org.cloudburstmc.protocol.bedrock.packet.UnknownPacket}
     * so the batch still carries it and the encoder forwards it unchanged.
     * <p>
     * Warns once per packet id, then drops to debug: a malformed packet type can arrive every tick,
     * and packet ids are bounded so the set stays small. One bridge belongs to one connection and is
     * only used on its event loop, so no synchronization is needed.
     */
    private void passThroughUndecodable(BedrockPacketWrapper wrapper, String reason, Throwable error) {
        int packetId = wrapper.getPacketId();
        if (this.undecodablePacketIds.add(packetId)) {
            log.warn("Forwarding packet {} undecoded ({}): {}", packetId, reason, error.getMessage());
            if (log.isDebugEnabled()) {
                log.debug("Undecodable packet {}", packetId, error);
            }
        } else if (log.isDebugEnabled()) {
            log.debug("Forwarding packet {} undecoded ({})", packetId, reason, error);
        }
        wrapper.setPacket(BedrockPacketCodec.toUnknownPacket(wrapper));
    }

    public void sendProxiedBatch(BedrockBatchWrapper batch) {
        this.handler.sendProxiedBatch(batch);
    }

    public void setHandler(ProxyPacketHandler handler) {
        Preconditions.checkNotNull(handler, "Handler can not be null");
        this.handler = handler;
    }
}
