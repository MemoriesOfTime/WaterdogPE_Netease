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
     * From v898 (1.21.130), the ResourcePackStackPacket serializer removes the
     * separate behavior packs array and only has a single resource packs list.
     */
    private static final int PROTOCOL_1_21_130 = 898;

    /**
     * Filter resource pack info entries based on client type and protocol version.
     * Called from PlayerResourcePackInfoSendEvent listener.
     */
    public static void filterPacksForClient(PlayerResourcePackInfoSendEvent event) {
        ProxiedPlayer player = event.getPlayer();
        boolean isNetEaseClient = player.getLoginData().isNetEaseClient();
        int protocol = player.getProtocol().getProtocol();
        
        ResourcePacksInfoPacket packet = event.getPacket();
        
        // For 1.21.30+, merge behavior packs into resource packs
        if (protocol >= PROTOCOL_1_21_30) {
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
            
            // Merge: resource packs first, then behavior packs
            List<ResourcePacksInfoPacket.Entry> merged = new ArrayList<>();
            merged.addAll(filteredResourcePacks);
            merged.addAll(filteredBehaviorPacks);
            
            packet.getResourcePackInfos().clear();
            packet.getResourcePackInfos().addAll(merged);
            
            // Clear behavior packs for 1.21.30+
            packet.getBehaviorPackInfos().clear();
            
            // Set hasAddonPacks if any behavior packs are present
            if (!filteredBehaviorPacks.isEmpty()) {
                packet.setHasAddonPacks(true);
            }
            
            player.getProxy().getLogger().debug("[NetEasePackFilter] {} (netease={}, proto={}): {} resource + {} behavior packs merged",
                player.getName(), isNetEaseClient, protocol, filteredResourcePacks.size(), filteredBehaviorPacks.size());
        } else {
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
            
            player.getProxy().getLogger().debug("[NetEasePackFilter] {} (netease={}, proto={}): {} resource, {} behavior packs",
                player.getName(), isNetEaseClient, protocol, filteredResourcePacks.size(), filteredBehaviorPacks.size());
        }
    }

    /**
     * Filter resource pack stack entries based on client type and protocol version.
     * Called from PlayerResourcePackApplyEvent listener.
     * 
     * NOTE: The stack packet merge threshold differs from the info packet!
     * - Info packet: v729+ serializer only has resourcePackInfos → merge at 729
     * - Stack packet: v898+ serializer only has resourcePacks → merge at 898
     * For v729-v897 (e.g. v766), the stack serializer still writes behaviorPacks
     * and resourcePacks as separate arrays, so we must NOT merge them.
     */
    public static void filterStackForClient(PlayerResourcePackApplyEvent event) {
        ProxiedPlayer player = event.getPlayer();
        boolean isNetEaseClient = player.getLoginData().isNetEaseClient();
        int protocol = player.getProtocol().getProtocol();
        
        ResourcePackStackPacket packet = event.getStackPacket();
        
        // For 1.21.130+ (v898), the stack serializer only has resourcePacks list
        if (protocol >= PROTOCOL_1_21_130) {
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
            
            // Clear behavior packs for 1.21.130+
            packet.getBehaviorPacks().clear();
        } else {
            // For older versions (including v766), filter both lists separately
            // keeping behavior packs in their own list
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
