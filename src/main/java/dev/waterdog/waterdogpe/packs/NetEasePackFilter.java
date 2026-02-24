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
import dev.waterdog.waterdogpe.packs.types.ResourcePack;
import dev.waterdog.waterdogpe.player.ProxiedPlayer;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Filters resource packs based on client type (NetEase vs Microsoft).
 * This ensures NetEase mod behavior packs are only sent to NetEase clients.
 */
public class NetEasePackFilter {

    /**
     * Filter resource pack info entries based on client type.
     * Called from ProxiedPlayer.sendResourcePacks() via PlayerResourcePackInfoSendEvent.
     */
    public static void filterPacksForClient(PlayerResourcePackInfoSendEvent event) {
        ProxiedPlayer player = event.getPlayer();
        boolean isNetEaseClient = player.getLoginData().isNetEaseClient();
        PackManager packManager = player.getProxy().getPackManager();

        ResourcePacksInfoPacket packet = event.getPacket();

        List<ResourcePacksInfoPacket.Entry> filteredResource = filterInfoEntries(
                packet.getResourcePackInfos(), isNetEaseClient, packManager);
        packet.getResourcePackInfos().clear();
        packet.getResourcePackInfos().addAll(filteredResource);

        // behaviorPackInfos: for v729+ this is already empty (merged by buildPacksInfoPacket).
        // For older protocols it may still have entries; filter those too.
        List<ResourcePacksInfoPacket.Entry> filteredBehavior = filterInfoEntries(
                packet.getBehaviorPackInfos(), isNetEaseClient, packManager);
        packet.getBehaviorPackInfos().clear();
        packet.getBehaviorPackInfos().addAll(filteredBehavior);

        player.getProxy().getLogger().debug(
                "[NetEasePackFilter] {} (netease={}, proto={}): {} resource, {} behavior pack infos",
                player.getName(), isNetEaseClient, player.getProtocol().getProtocol(),
                filteredResource.size(), filteredBehavior.size());
    }

    /**
     * Filter resource pack stack entries based on client type.
     * Called from ResourcePacksHandler via PlayerResourcePackApplyEvent.
     */
    public static void filterStackForClient(PlayerResourcePackApplyEvent event) {
        ProxiedPlayer player = event.getPlayer();
        boolean isNetEaseClient = player.getLoginData().isNetEaseClient();
        PackManager packManager = player.getProxy().getPackManager();

        ResourcePackStackPacket packet = event.getStackPacket();

        List<ResourcePackStackPacket.Entry> filteredResource = filterStackEntries(
                packet.getResourcePacks(), isNetEaseClient, packManager);
        packet.getResourcePacks().clear();
        packet.getResourcePacks().addAll(filteredResource);

        // behaviorPacks: for v898+ this is already empty (merged by buildStackPacket).
        // For older protocols it may still have entries; filter those too.
        List<ResourcePackStackPacket.Entry> filteredBehavior = filterStackEntries(
                packet.getBehaviorPacks(), isNetEaseClient, packManager);
        packet.getBehaviorPacks().clear();
        packet.getBehaviorPacks().addAll(filteredBehavior);
    }

    private static List<ResourcePacksInfoPacket.Entry> filterInfoEntries(
            List<ResourcePacksInfoPacket.Entry> entries,
            boolean isNetEaseClient,
            PackManager packManager) {

        List<ResourcePacksInfoPacket.Entry> filtered = new ArrayList<>();
        for (ResourcePacksInfoPacket.Entry entry : entries) {
            ResourcePack pack = packManager.getPacks().get(entry.getPackId());
            if (pack == null || pack.getSupportType().isCompatibleWith(isNetEaseClient)) {
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
            try {
                UUID packId = UUID.fromString(entry.getPackId());
                ResourcePack pack = packManager.getPacks().get(packId);
                if (pack == null || pack.getSupportType().isCompatibleWith(isNetEaseClient)) {
                    filtered.add(entry);
                }
            } catch (IllegalArgumentException e) {
                filtered.add(entry);
            }
        }
        return filtered;
    }
}
