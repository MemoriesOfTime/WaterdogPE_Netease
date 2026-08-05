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

package dev.waterdog.waterdogpe.network.protocol.registry;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import lombok.Getter;

/**
 * Stores the item and block runtime ID mappings between a specific downstream server and the unified registry.
 * <p>
 * Items and blocks live in two independent id spaces (resolved by {@code itemDefinitions} and
 * {@code blockDefinitions} registries respectively), so each is mapped separately. A mapping may be
 * identity for one space and non-identity for the other (e.g. sequential block palette but unified items).
 * <p>
 * Instances are immutable and thread-safe after construction.
 */
public class ServerIdMapping {

    /**
     * Singleton for servers whose item AND block IDs are identical to the unified IDs.
     */
    public static final ServerIdMapping IDENTITY = new ServerIdMapping(
            new Int2IntOpenHashMap(), new Int2IntOpenHashMap(),
            new Int2IntOpenHashMap(), new Int2IntOpenHashMap(),
            true, true);

    private final Int2IntMap itemServerToUnified;
    private final Int2IntMap itemUnifiedToServer;
    private final Int2IntMap blockServerToUnified;
    private final Int2IntMap blockUnifiedToServer;
    @Getter
    private final boolean identity;
    /** True when block runtime ids need no translation (hash mode, or no custom blocks). */
    @Getter
    private final boolean blockIdentity;

    public ServerIdMapping(Int2IntMap itemServerToUnified, Int2IntMap itemUnifiedToServer) {
        this(itemServerToUnified, itemUnifiedToServer, new Int2IntOpenHashMap(), new Int2IntOpenHashMap(), false, true);
    }

    public ServerIdMapping(Int2IntMap itemServerToUnified, Int2IntMap itemUnifiedToServer,
                           Int2IntMap blockServerToUnified, Int2IntMap blockUnifiedToServer) {
        this(itemServerToUnified, itemUnifiedToServer, blockServerToUnified, blockUnifiedToServer, false, false);
    }

    private ServerIdMapping(Int2IntMap itemServerToUnified, Int2IntMap itemUnifiedToServer,
                            Int2IntMap blockServerToUnified, Int2IntMap blockUnifiedToServer,
                            boolean identity, boolean blockIdentity) {
        this.itemServerToUnified = itemServerToUnified;
        this.itemUnifiedToServer = itemUnifiedToServer;
        this.blockServerToUnified = blockServerToUnified;
        this.blockUnifiedToServer = blockUnifiedToServer;
        this.identity = identity;
        this.blockIdentity = blockIdentity;
    }

    /**
     * Translate a server item runtime ID to the unified ID.
     * Returns the original ID if no mapping exists.
     */
    public int translateItemId(int serverItemId) {
        return this.itemServerToUnified.getOrDefault(serverItemId, serverItemId);
    }

    /**
     * Translate a unified item runtime ID back to the server's ID.
     * Returns the original ID if no mapping exists.
     */
    public int reverseTranslateItemId(int unifiedItemId) {
        return this.itemUnifiedToServer.getOrDefault(unifiedItemId, unifiedItemId);
    }

    /**
     * Returns true if the given unified item runtime ID is known to this server's mapping.
     * For identity mapping, always returns true (all unified IDs are valid server IDs).
     */
    public boolean isKnownUnified(int unifiedItemId) {
        if (this.identity) return true;
        return this.itemUnifiedToServer.containsKey(unifiedItemId);
    }

    /**
     * Translate a server block runtime ID to the unified ID.
     * Returns the original ID if no mapping exists.
     */
    public int translateBlockId(int serverBlockId) {
        return this.blockServerToUnified.getOrDefault(serverBlockId, serverBlockId);
    }

    /**
     * Translate a unified block runtime ID back to the server's ID.
     * Returns the original ID if no mapping exists.
     */
    public int reverseTranslateBlockId(int unifiedBlockId) {
        return this.blockUnifiedToServer.getOrDefault(unifiedBlockId, unifiedBlockId);
    }

    /**
     * Returns true if the given unified block runtime ID is known to this server's mapping.
     * For identity/block-identity mapping, always returns true.
     */
    public boolean isKnownUnifiedBlock(int unifiedBlockId) {
        if (this.identity || this.blockIdentity) return true;
        return this.blockUnifiedToServer.containsKey(unifiedBlockId);
    }
}
