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

package dev.waterdog.waterdogpe.network.protocol.rewrite.types;

import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.network.protocol.handler.TransferCallback;
import dev.waterdog.waterdogpe.network.protocol.registry.ServerIdMapping;
import lombok.Getter;
import lombok.Setter;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.GameRuleData;

import java.util.List;

/**
 * Rewrite data of a present player.
 * Holds both the client-known entityId and the downstream-known clientId.
 * Important when interacting with packets, as different packet targets might want different entityIds.
 */
@Setter
public class RewriteData {

    /**
     * The original entityId known to the client
     */
    @Getter
    private long entityId;
    /**
     * The downstream-known entityId
     */
    @Getter
    private long originalEntityId;

    @Getter
    private BlockPalette blockPalette;
    @Getter
    private BlockPaletteRewrite blockPaletteRewrite;
    @Getter
    private List<BlockPropertyData> blockProperties;

    /**
     * A list of GameRules currently known to the client.
     */
    @Getter
    private List<GameRuleData<?>> gameRules;
    /**
     * The dimensionId the player is currently in
     */
    @Getter
    private int dimension = 0;
    @Getter
    private TransferCallback transferCallback;

    @Getter
    private Vector3f spawnPosition;
    @Getter
    private Vector2f rotation;
    /**
     * Server known value of immobile flag
     * Actually applied value may be different during server transfer
     */
    private boolean immobileFlag;

    /**
     * The name that is shown up in the player list (or pause menu)
     */
    @Getter
    private String proxyName;


    @Getter
    private BedrockCodecHelper codecHelper;

    /**
     * Current item ID mapping between the connected downstream server and the unified registry.
     * Null when registry aggregation is disabled.
     */
    @Getter
    private ServerIdMapping currentMapping;

    /**
     * The item definition version the client received during initial connect.
     * Used to detect stale item definitions for <= 1.21.50 clients.
     */
    @Getter
    private int clientItemDefinitionVersion;

    /**
     * The block definition version the client received during initial connect.
     * Used to detect stale block definitions for all clients.
     */
    @Getter
    private int clientBlockDefinitionVersion;

    /**
     * The blockNetworkIdsHashed flag the client received in its StartGamePacket (v582+).
     * Used to detect hash mode mismatch when switching servers.
     */
    @Getter
    private boolean clientBlockNetworkIdsHashed;

    /**
     * For sequential block ID mode (blockNetworkIdsHashed = false):
     * the server name whose block list the client received.
     * Null in hash mode (client has the unified block list).
     * Used to detect block staleness when switching servers in sequential mode.
     */
    @Getter
    private String clientBlockListServer;

    /**
     * When true, LevelChunkPacket from the current downstream is suppressed (not forwarded to client).
     * Set during pendingTarget mode-conflict reconnect: the client was told sequential mode but the
     * current server (bridge server) uses hash mode. Suppressing its chunks prevents the client
     * from interpreting hash block IDs as sequential indices (which would cause all blocks to display wrong).
     * Cleared when the actual target server's StartGamePacket is processed.
     */
    @Getter
    private boolean suppressChunkTransfer;

    public RewriteData() {
        this.proxyName = ProxyServer.getInstance().getConfiguration().getName();
    }

    public boolean hasImmobileFlag() {
        return this.immobileFlag;
    }

}
