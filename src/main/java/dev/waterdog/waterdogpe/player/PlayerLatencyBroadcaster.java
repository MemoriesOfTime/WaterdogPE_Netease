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

package dev.waterdog.waterdogpe.player;

import com.google.gson.JsonObject;
import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.network.serverinfo.ServerInfo;
import org.cloudburstmc.protocol.bedrock.packet.ScriptMessagePacket;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Broadcasts each connected player's RakNet ping to Java Edition clients on the
 * same downstream. ViaBedrock consumes {@code easecation:player_latency_v1} and
 * writes those values into Java {@code PLAYER_INFO_UPDATE}, which is the only
 * place Java TAB can show another Bedrock player's latency. Vanilla
 * {@code PlayerListPacket} has no ping field, so without this snapshot Java
 * clients keep showing unknown latency ("X").
 */
public final class PlayerLatencyBroadcaster {

    public static final String MESSAGE_ID = "easecation:player_latency_v1";
    static final int MAX_ENTRIES = 4_096;

    private PlayerLatencyBroadcaster() {
    }

    public static void tick(ProxyServer proxy) {
        if (proxy == null || !proxy.getConfiguration().isBroadcastPlayerLatency()) {
            return;
        }

        final Map<ServerInfo, List<ProxiedPlayer>> byServer = new LinkedHashMap<>();
        for (ProxiedPlayer player : proxy.getPlayers().values()) {
            if (player == null || !player.isConnected()) {
                continue;
            }
            final ServerInfo serverInfo = player.getServerInfo();
            if (serverInfo == null) {
                continue;
            }
            byServer.computeIfAbsent(serverInfo, ignored -> new ArrayList<>()).add(player);
        }

        for (List<ProxiedPlayer> players : byServer.values()) {
            broadcast(players);
        }
    }

    public static void broadcast(Collection<ProxiedPlayer> players) {
        if (players == null || players.isEmpty()) {
            return;
        }

        final List<ProxiedPlayer> online = new ArrayList<>();
        boolean hasJavaClient = false;
        for (ProxiedPlayer player : players) {
            if (player == null || !player.isConnected()) {
                continue;
            }
            online.add(player);
            hasJavaClient = hasJavaClient || player.isJavaClient();
        }
        // Native Bedrock TAB does not need this snapshot. Skip the extra ScriptMessage
        // unless a ViaProxy / Java client is on the same downstream.
        if (online.isEmpty() || !hasJavaClient) {
            return;
        }

        final String payload = encodeSnapshot(online);
        final ScriptMessagePacket packet = createPacket(payload);
        for (ProxiedPlayer recipient : online) {
            if (recipient.isJavaClient()) {
                recipient.sendPacket(packet);
            }
        }
    }

    static String encodeSnapshot(Collection<ProxiedPlayer> players) {
        final JsonObject object = new JsonObject();
        if (players == null) {
            return object.toString();
        }
        for (ProxiedPlayer player : players) {
            if (object.size() >= MAX_ENTRIES) {
                break;
            }
            if (player == null) {
                continue;
            }
            final UUID uuid = player.getUniqueId();
            if (uuid == null) {
                continue;
            }
            final long ping = Math.max(0L, player.getPing());
            if (ping > Integer.MAX_VALUE) {
                continue;
            }
            object.addProperty(uuid.toString(), ping);
        }
        return object.toString();
    }

    static ScriptMessagePacket createPacket(String payload) {
        final ScriptMessagePacket packet = new ScriptMessagePacket();
        packet.setChannel(MESSAGE_ID);
        packet.setMessage(payload == null ? "{}" : payload);
        return packet;
    }
}
