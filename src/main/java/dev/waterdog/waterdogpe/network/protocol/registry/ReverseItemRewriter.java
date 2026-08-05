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

import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.common.NamedDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryActionData;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket;
import org.cloudburstmc.protocol.common.PacketSignal;

import java.util.List;

/**
 * Handles reverse translation of item IDs in client → server packets.
 * When the client sends packets using unified item IDs, this rewriter converts them
 * back to the downstream server's runtime IDs before forwarding.
 */
public class ReverseItemRewriter implements BedrockPacketHandler {

    private final ServerIdMapping mapping;

    public ReverseItemRewriter(ServerIdMapping mapping) {
        this.mapping = mapping;
    }

    /**
     * Attempt to rewrite the packet. Returns HANDLED if the packet was modified.
     */
    public PacketSignal doRewrite(BedrockPacket packet) {
        if (this.mapping == null || this.mapping.isIdentity()) {
            return PacketSignal.UNHANDLED;
        }
        return packet.handle(this);
    }

    @Override
    public PacketSignal handle(InventoryTransactionPacket packet) {
        boolean modified = false;

        // Rewrite actions list: InventoryActionData is immutable (@Value), must replace elements
        List<InventoryActionData> actions = packet.getActions();
        for (int i = 0; i < actions.size(); i++) {
            InventoryActionData action = actions.get(i);
            ItemData newFrom = reverseRewriteItemData(action.getFromItem());
            ItemData newTo = reverseRewriteItemData(action.getToItem());
            if (newFrom != null || newTo != null) {
                actions.set(i, new InventoryActionData(
                        action.getSource(),
                        action.getSlot(),
                        newFrom != null ? newFrom : action.getFromItem(),
                        newTo != null ? newTo : action.getToItem(),
                        action.getStackNetworkId()
                ));
                modified = true;
            }
        }

        // Rewrite itemInHand
        ItemData newHand = reverseRewriteItemData(packet.getItemInHand());
        if (newHand != null) {
            packet.setItemInHand(newHand);
            modified = true;
        }

        return modified ? PacketSignal.HANDLED : PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(MobEquipmentPacket packet) {
        ItemData newItem = reverseRewriteItemData(packet.getItem());
        if (newItem != null) {
            packet.setItem(newItem);
            return PacketSignal.HANDLED;
        }
        return PacketSignal.UNHANDLED;
    }

    /**
     * Creates a new ItemData with the server's runtime ID if translation is needed.
     * Returns null if no translation was necessary.
     * Returns ItemData.AIR if the item is exclusive to another server (unknown to this one).
     */
    private ItemData reverseRewriteItemData(ItemData item) {
        if (item == null || item.getDefinition() == null || item.getDefinition() == ItemDefinition.AIR) {
            return null;
        }

        ItemDefinition def = item.getDefinition();
        int unifiedId = def.getRuntimeId();

        // Item is exclusive to another server — replace with AIR to avoid sending a wrong ID
        if (!this.mapping.isKnownUnified(unifiedId)) {
            return ItemData.AIR;
        }

        int serverId = this.mapping.reverseTranslateItemId(unifiedId);

        // Block-runtime-id translation (sequential palette mode). Block-items (dirt, stone, planks, ...)
        // carry a separate blockRuntimeId that must be remapped independently of the item runtimeId.
        // Use a "changed" flag rather than a null sentinel, because the drop-to-AIR case itself maps to
        // null (ItemData.AIR.getBlockDefinition() == null), which would be indistinguishable from "no change".
        BlockDefinition blockDef = item.getBlockDefinition();
        BlockDefinition newBlockDef = blockDef;
        boolean blockChanged = false;
        if (blockDef != null && blockDef != ItemData.AIR.getBlockDefinition()
                && !this.mapping.isBlockIdentity()) {
            int unifiedBlockId = blockDef.getRuntimeId();
            if (!this.mapping.isKnownUnifiedBlock(unifiedBlockId)) {
                // Block exclusive to another server; drop its block binding so the server does not receive
                // a wrong blockRuntimeId. The item runtimeId still identifies the item.
                newBlockDef = ItemData.AIR.getBlockDefinition();
                blockChanged = true;
            } else {
                int serverBlockId = this.mapping.reverseTranslateBlockId(unifiedBlockId);
                if (serverBlockId != unifiedBlockId) {
                    newBlockDef = new SimpleBlockDefinition(
                            blockDef instanceof NamedDefinition ? ((NamedDefinition) blockDef).getIdentifier() : "unknown",
                            serverBlockId,
                            blockDef instanceof SimpleBlockDefinition ? ((SimpleBlockDefinition) blockDef).getState() : null);
                    blockChanged = true;
                }
            }
        }

        if (serverId == unifiedId && !blockChanged) {
            return null;
        }

        ItemData.Builder builder = item.toBuilder();
        if (serverId != unifiedId) {
            ItemDefinition serverDef = new SimpleItemDefinition(
                    def.getIdentifier(), serverId, def.isComponentBased());
            builder.definition(serverDef);
        }
        if (blockChanged) {
            builder.blockDefinition(newBlockDef);
        }
        return builder.build();
    }
}
