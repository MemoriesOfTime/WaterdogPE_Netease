/*
 * Copyright 2022 WaterdogTEAM
 * Licensed under the GNU General Public License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.gnu.org/old-licenses/gpl-2.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.waterdog.waterdogpe.network.protocol.registry;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition;
import org.cloudburstmc.protocol.common.DefinitionRegistry;

/**
 * A DefinitionRegistry that transparently translates server block runtime IDs to unified IDs
 * during packet deserialization (sequential block-palette mode only).
 * <p>
 * Used as the {@code blockDefinitions} registry on the downstream codec helper. When
 * {@code readNetworkItemStackDescriptor} / {@code UpdateBlockPacket} decode resolves a block runtime
 * id through this registry, it receives a {@link BlockDefinition} whose runtimeId is the unified id,
 * so the decoded data automatically carries the unified id. The reverse direction (unified → server)
 * is handled by {@link ReverseItemRewriter} via {@link ServerIdMapping#reverseTranslateBlockId(int)}.
 * <p>
 * Instances are immutable and thread-safe after construction.
 */
public class TranslatingBlockRegistry implements DefinitionRegistry<BlockDefinition> {

    private final Int2ObjectMap<BlockDefinition> mapping;

    /**
     * @param mapping key = server's block runtime ID, value = BlockDefinition with unified runtime ID
     */
    public TranslatingBlockRegistry(Int2ObjectMap<BlockDefinition> mapping) {
        this.mapping = mapping;
    }

    @Override
    public BlockDefinition getDefinition(int runtimeId) {
        BlockDefinition def = this.mapping.get(runtimeId);
        if (def != null) {
            return def;
        }
        // Unknown block — return a placeholder with the original runtime id so passthrough works
        return new SimpleBlockDefinition("unknown", runtimeId, null);
    }

    @Override
    public boolean isRegistered(BlockDefinition definition) {
        BlockDefinition mapped = this.mapping.get(definition.getRuntimeId());
        return mapped != null && mapped.equals(definition);
    }
}
