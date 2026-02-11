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

package dev.waterdog.waterdogpe.packs;

import dev.waterdog.waterdogpe.event.defaults.PlayerResourcePackApplyEvent;
import dev.waterdog.waterdogpe.event.defaults.PlayerResourcePackInfoSendEvent;
import dev.waterdog.waterdogpe.network.protocol.ProtocolVersion;
import dev.waterdog.waterdogpe.packs.types.ResourcePack;
import dev.waterdog.waterdogpe.player.ProxiedPlayer;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * Filters resource packs based on client type (NetEase vs Microsoft).
 * This ensures NetEase mod behavior packs are only sent to NetEase clients.
 * Also handles protocol version differences (1.21.30+ merges behavior packs into resource packs).
 */
public class NetEasePackFilter {

    private static final int PROTOCOL_1_21_30 = 729;

    /**
     * Filter resource pack info entries based on client type and protocol version.
     * Called from PlayerResourcePackInfoSendEvent listener.
     */
    public static void filterPacksForClient(PlayerResourcePackInfoSendEvent event) {
        ProxiedPlayer player = event.getPlayer();
        boolean isNetEaseClient = player.getLoginData().isNetEaseClient();
        int protocol = player.getProtocol().getProtocol();
        
        player.getProxy().getLogger().info("=== Filtering packs for " + player.getName() + " ===");
        player.getProxy().getLogger().info("NetEase client: " + isNetEaseClient + ", Protocol: " + protocol);
        
        ResourcePacksInfoPacket packet = event.getPacket();
        
        // For 1.21.30+, merge behavior packs into resource packs
        if (protocol >= PROTOCOL_1_21_30) {
            player.getProxy().getLogger().info("Protocol >= 1.21.30, merging behavior packs into resource packs");
            // Filter and merge behavior packs into resource packs
            List<ResourcePacksInfoPacket.Entry> filteredBehaviorPacks = filterInfoEntries(
                packet.getBehaviorPackInfos(), 
                isNetEaseClient,
                player.getProxy().getPackManager()
            );
            
            // Filter resource packs
            List<ResourcePacksInfoPacket.Entry> filteredResourcePacks = filterInfoEntries(
                packet.getResourcePackInfos(), 
                isNetEaseClient,
                player.getProxy().getPackManager()
            );
            
            player.getProxy().getLogger().info("Filtered: " + filteredResourcePacks.size() + " resource packs, " + 
                filteredBehaviorPacks.size() + " behavior packs");
            
            // Merge: resource packs first, then behavior packs
            List<ResourcePacksInfoPacket.Entry> merged = new ArrayList<>();
            merged.addAll(filteredResourcePacks);
            merged.addAll(filteredBehaviorPacks);
            
            packet.getResourcePackInfos().clear();
            packet.getResourcePackInfos().addAll(merged);
            
            // Clear behavior packs for 1.21.30+
            packet.getBehaviorPackInfos().clear();
            
            player.getProxy().getLogger().info("After merge: " + packet.getResourcePackInfos().size() + " total packs");
        } else {
            player.getProxy().getLogger().info("Protocol < 1.21.30, keeping packs separate");
            // For older versions, filter separately
            List<ResourcePacksInfoPacket.Entry> filteredResourcePacks = filterInfoEntries(
                packet.getResourcePackInfos(), 
                isNetEaseClient,
                player.getProxy().getPackManager()
            );
            packet.getResourcePackInfos().clear();
            packet.getResourcePackInfos().addAll(filteredResourcePacks);
            
            List<ResourcePacksInfoPacket.Entry> filteredBehaviorPacks = filterInfoEntries(
                packet.getBehaviorPackInfos(),
                isNetEaseClient,
                player.getProxy().getPackManager()
            );
            packet.getBehaviorPackInfos().clear();
            packet.getBehaviorPackInfos().addAll(filteredBehaviorPacks);
            
            player.getProxy().getLogger().info("Filtered: " + filteredResourcePacks.size() + " resource packs, " + 
                filteredBehaviorPacks.size() + " behavior packs");
        }
    }

    /**
     * Filter resource pack stack entries based on client type and protocol version.
     * Called from PlayerResourcePackApplyEvent listener.
     */
    public static void filterStackForClient(PlayerResourcePackApplyEvent event) {
        ProxiedPlayer player = event.getPlayer();
        boolean isNetEaseClient = player.getLoginData().isNetEaseClient();
        int protocol = player.getProtocol().getProtocol();
        
        ResourcePackStackPacket packet = event.getStackPacket();
        
        // For 1.21.30+, merge behavior packs into resource packs
        if (protocol >= PROTOCOL_1_21_30) {
            // Filter and merge behavior packs into resource packs
            List<ResourcePackStackPacket.Entry> filteredBehaviorPacks = filterStackEntries(
                packet.getBehaviorPacks(), 
                isNetEaseClient,
                player.getProxy().getPackManager()
            );
            
            // Filter resource packs
            List<ResourcePackStackPacket.Entry> filteredResourcePacks = filterStackEntries(
                packet.getResourcePacks(), 
                isNetEaseClient,
                player.getProxy().getPackManager()
            );
            
            // Merge: resource packs first, then behavior packs
            List<ResourcePackStackPacket.Entry> merged = new ArrayList<>();
            merged.addAll(filteredResourcePacks);
            merged.addAll(filteredBehaviorPacks);
            
            packet.getResourcePacks().clear();
            packet.getResourcePacks().addAll(merged);
            
            // Clear behavior packs for 1.21.30+
            packet.getBehaviorPacks().clear();
        } else {
            // For older versions, filter separately
            List<ResourcePackStackPacket.Entry> filteredResourcePacks = filterStackEntries(
                packet.getResourcePacks(), 
                isNetEaseClient,
                player.getProxy().getPackManager()
            );
            packet.getResourcePacks().clear();
            packet.getResourcePacks().addAll(filteredResourcePacks);
            
            List<ResourcePackStackPacket.Entry> filteredBehaviorPacks = filterStackEntries(
                packet.getBehaviorPacks(),
                isNetEaseClient,
                player.getProxy().getPackManager()
            );
            packet.getBehaviorPacks().clear();
            packet.getBehaviorPacks().addAll(filteredBehaviorPacks);
        }
    }

    private static List<ResourcePacksInfoPacket.Entry> filterInfoEntries(
            List<ResourcePacksInfoPacket.Entry> entries,
            boolean isNetEaseClient,
            PackManager packManager) {
        
        List<ResourcePacksInfoPacket.Entry> filtered = new ArrayList<>();
        
        for (ResourcePacksInfoPacket.Entry entry : entries) {
            ResourcePack pack = packManager.getPacks().get(entry.getPackId());
            if (pack != null) {
                ResourcePack.SupportType supportType = pack.getSupportType();
                if (supportType.isCompatibleWith(isNetEaseClient)) {
                    filtered.add(entry);
                }
            } else {
                // If pack not found in manager, include it (might be from event modification)
                filtered.add(entry);
            }
        }
        
        return filtered;
    }

    private static List<ResourcePackStackPacket.Entry> filterStackEntries(
            List<ResourcePackStackPacket.Entry> entries,
            boolean isNetEaseClient,
            PackManager packManager) {
        
        List<ResourcePackStackPacket.Entry> filtered = new ArrayList<>();
        
        for (ResourcePackStackPacket.Entry entry : entries) {
            // Parse UUID from string
            try {
                java.util.UUID packId = java.util.UUID.fromString(entry.getPackId());
                ResourcePack pack = packManager.getPacks().get(packId);
                if (pack != null) {
                    ResourcePack.SupportType supportType = pack.getSupportType();
                    if (supportType.isCompatibleWith(isNetEaseClient)) {
                        filtered.add(entry);
                    }
                } else {
                    // If pack not found in manager, include it (might be from event modification)
                    filtered.add(entry);
                }
            } catch (IllegalArgumentException e) {
                // Invalid UUID, include it anyway
                filtered.add(entry);
            }
        }
        
        return filtered;
    }
}
