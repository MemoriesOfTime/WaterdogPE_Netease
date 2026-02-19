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

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.common.DefinitionRegistry;

/**
 * A DefinitionRegistry that transparently translates server item runtime IDs to unified IDs
 * during packet deserialization.
 * <p>
 * When protocol-lib's codec reads an ItemData from a downstream server packet, it calls
 * {@link #getDefinition(int)} with the server's runtime ID. This registry returns an
 * ItemDefinition whose {@code getRuntimeId()} is the unified ID, so the deserialized
 * ItemData automatically holds the unified ID.
 * <p>
 * Instances are immutable and thread-safe after construction.
 */
public class TranslatingItemRegistry implements DefinitionRegistry<ItemDefinition> {

    private static final ItemDefinition UNKNOWN = new SimpleItemDefinition("unknown", 0, false);

    private final Int2ObjectMap<ItemDefinition> mapping;

    /**
     * @param mapping key = server's runtime ID, value = ItemDefinition with unified runtime ID
     */
    public TranslatingItemRegistry(Int2ObjectMap<ItemDefinition> mapping) {
        this.mapping = mapping;
    }

    @Override
    public ItemDefinition getDefinition(int runtimeId) {
        ItemDefinition def = this.mapping.get(runtimeId);
        if (def != null) {
            return def;
        }
        // Unknown item — return a placeholder with the original runtime ID
        return new SimpleItemDefinition("unknown", runtimeId, false);
    }

    @Override
    public boolean isRegistered(ItemDefinition definition) {
        ItemDefinition mapped = this.mapping.get(definition.getRuntimeId());
        return mapped != null && mapped.equals(definition);
    }
}
