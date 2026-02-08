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

package dev.waterdog.waterdogpe.network.connection.codec.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import org.cloudburstmc.netty.channel.raknet.RakServerChannel;
import org.cloudburstmc.netty.channel.raknet.config.RakServerChannelConfig;
import org.cloudburstmc.netty.handler.codec.raknet.server.RakServerOfflineHandler;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.*;

/**
 * Overrides acceptInboundMessage to support NetEase clients that send
 * shorter unconnected ping packets without magic bytes.
 *
 * @see <a href="https://github.com/mc-dreamland/Network/commit/3f4f8b352ca1aa461f7d58bca6b53871b13a7dd3">mc-dreamland fix</a>
 */
public class WdpeRakServerOfflineHandler extends RakServerOfflineHandler {

    public WdpeRakServerOfflineHandler(RakServerChannel channel) {
        super(channel);
    }

    @Override
    protected boolean acceptInboundMessage(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof DatagramPacket)) {
            return false;
        }

        DatagramPacket packet = (DatagramPacket) msg;
        ByteBuf buf = packet.content();
        if (!buf.isReadable()) {
            return false;
        }

        int startIndex = buf.readerIndex();
        try {
            int packetId = buf.readUnsignedByte();
            boolean isGamePing = buf.isReadable(24);
            switch (packetId) {
                case ID_UNCONNECTED_PING:
                    if (isGamePing) {
                        buf.readLong(); // Ping time
                    }
                case ID_OPEN_CONNECTION_REQUEST_1:
                case ID_OPEN_CONNECTION_REQUEST_2:
                    if (isGamePing) {
                        ByteBuf magicBuf = ((RakServerChannelConfig) ctx.channel().config()).getUnconnectedMagic();
                        int size = magicBuf.readableBytes();
                        return buf.isReadable(size) && ByteBufUtil.equals(buf.readSlice(size), magicBuf);
                    } else {
                        return true;
                    }
                default:
                    return false;
            }
        } finally {
            buf.readerIndex(startIndex);
        }
    }
}
