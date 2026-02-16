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

package dev.waterdog.waterdogpe.network.protocol.handler.upstream;

import org.cloudburstmc.protocol.bedrock.packet.*;
import dev.waterdog.waterdogpe.event.defaults.PlayerResourcePackApplyEvent;
import dev.waterdog.waterdogpe.packs.PackManager;
import dev.waterdog.waterdogpe.player.ProxiedPlayer;
import org.cloudburstmc.protocol.common.PacketSignal;

import java.util.LinkedList;
import java.util.Queue;

/**
 * Upstream handler handling proxy manager resource packs.
 */
public class ResourcePacksHandler extends AbstractUpstreamHandler {

    private final Queue<ResourcePackDataInfoPacket> pendingPacks = new LinkedList<>();
    private ResourcePackDataInfoPacket sendingPack;

    public ResourcePacksHandler(ProxiedPlayer player) {
        super(player);
    }

    @Override
    public PacketSignal handle(ResourcePackClientResponsePacket packet) {
        PackManager packManager = this.player.getProxy().getPackManager();

        switch (packet.getStatus()) {
            case REFUSED:
                this.player.disconnect("disconnectionScreen.noReason");
                break;
            case SEND_PACKS:
                for (String packIdVer : packet.getPackIds()) {
                    ResourcePackDataInfoPacket response = packManager.packInfoFromIdVer(packIdVer);
                    if (response == null) {
                        this.player.disconnect("disconnectionScreen.resourcePack");
                        break;
                    }
                    // Debug: log DataInfo packet details
                    StringBuilder hashHex = new StringBuilder();
                    if (response.getHash() != null) {
                        for (byte b : response.getHash()) {
                            hashHex.append(String.format("%02x", b));
                        }
                    }
                    this.player.getProxy().getLogger().info(
                        "[PackDebug] DataInfo: packId={} packVer={} maxChunkSize={} chunkCount={} compressedSize={} hashLen={} hash={} premium={} type={}",
                        response.getPackId(), response.getPackVersion(),
                        response.getMaxChunkSize(), response.getChunkCount(),
                        response.getCompressedPackSize(), 
                        response.getHash() != null ? response.getHash().length : 0,
                        hashHex.toString(),
                        response.isPremium(), response.getType());
                    this.pendingPacks.offer(response);
                }
                this.sendNextPacket();
                break;
            case HAVE_ALL_PACKS:
                PlayerResourcePackApplyEvent event = new PlayerResourcePackApplyEvent(this.player, packManager.copyStackPacket());
                
                // Filter stack based on client type and protocol version
                dev.waterdog.waterdogpe.packs.NetEasePackFilter.filterStackForClient(event);
                
                this.player.getProxy().getEventManager().callEvent(event);
                // Debug: log stack packet contents
                ResourcePackStackPacket stackPkt = event.getStackPacket();
                // Set gameVersion to match the player's protocol version (e.g., "1.21.50")
                // MOT uses Utils.getVersionByProtocol(protocol) which returns the version string.
                // WaterdogPE's ProtocolVersion enum name is like MINECRAFT_PE_1_21_50, and
                // the client sends its version in the login e.g. "1.21.50".
                stackPkt.setGameVersion(this.player.getProtocol().getDefaultCodec().getMinecraftVersion());
                this.player.getProxy().getLogger().info("[PackDebug] Sending ResourcePackStackPacket to {} (proto={}):",
                    this.player.getName(), this.player.getProtocol().getProtocol());
                this.player.getProxy().getLogger().info("[PackDebug]   resourcePacks={}, behaviorPacks={}, gameVersion={}",
                    stackPkt.getResourcePacks().size(), stackPkt.getBehaviorPacks().size(), stackPkt.getGameVersion());
                for (var e2 : stackPkt.getResourcePacks()) {
                    this.player.getProxy().getLogger().info("[PackDebug]   resource-stack: id={} ver={}", e2.getPackId(), e2.getPackVersion());
                }
                for (var e2 : stackPkt.getBehaviorPacks()) {
                    this.player.getProxy().getLogger().info("[PackDebug]   behavior-stack: id={} ver={}", e2.getPackId(), e2.getPackVersion());
                }
                this.player.getConnection().sendPacket(stackPkt);
                break;
            case COMPLETED:
                if (!this.player.hasUpstreamBridge()) {
                    this.player.initialConnect(); // First connection
                }
                break;
        }

        return this.cancel();
    }

    @Override
    public PacketSignal handle(ResourcePackChunkRequestPacket packet) {
        PackManager packManager = this.player.getProxy().getPackManager();
        String version = packet.getPackVersion();
        String idVersion = (version != null && !version.isEmpty())
            ? packet.getPackId() + "_" + version
            : packet.getPackId().toString();
        ResourcePackChunkDataPacket response = packManager.packChunkDataPacket(idVersion, packet);
        if (response == null) {
            this.player.disconnect("Unknown resource pack!");
        } else {
            this.player.sendPacket(response);
            if (this.sendingPack != null && (packet.getChunkIndex() + 1) >= this.sendingPack.getChunkCount()) {
                this.sendNextPacket();
            }
        }
        return this.cancel();
    }

    private void sendNextPacket() {
        ResourcePackDataInfoPacket infoPacket = this.pendingPacks.poll();
        if (infoPacket != null && this.player.isConnected()) {
            this.sendingPack = infoPacket;
            this.player.sendPacket(infoPacket);
        }
    }
}
