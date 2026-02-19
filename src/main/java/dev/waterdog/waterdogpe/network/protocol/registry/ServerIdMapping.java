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
 * Stores the item runtime ID mapping between a specific downstream server and the unified registry.
 * Instances are immutable and thread-safe after construction.
 */
public class ServerIdMapping {

    /**
     * Singleton for servers whose IDs are identical to the unified IDs.
     */
    public static final ServerIdMapping IDENTITY = new ServerIdMapping(new Int2IntOpenHashMap(), new Int2IntOpenHashMap(), true);

    private final Int2IntMap itemServerToUnified;
    private final Int2IntMap itemUnifiedToServer;
    @Getter
    private final boolean identity;

    public ServerIdMapping(Int2IntMap itemServerToUnified, Int2IntMap itemUnifiedToServer) {
        this(itemServerToUnified, itemUnifiedToServer, false);
    }

    private ServerIdMapping(Int2IntMap itemServerToUnified, Int2IntMap itemUnifiedToServer, boolean identity) {
        this.itemServerToUnified = itemServerToUnified;
        this.itemUnifiedToServer = itemUnifiedToServer;
        this.identity = identity;
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
}
