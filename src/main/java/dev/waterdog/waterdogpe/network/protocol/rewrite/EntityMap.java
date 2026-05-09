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

package dev.waterdog.waterdogpe.network.protocol.rewrite;

import dev.waterdog.waterdogpe.network.protocol.rewrite.types.RewriteData;
import dev.waterdog.waterdogpe.network.protocol.user.PlayerRewriteUtils;
import dev.waterdog.waterdogpe.player.ProxiedPlayer;
import it.unimi.dsi.fastutil.longs.LongListIterator;
import org.cloudburstmc.protocol.bedrock.data.camera.CameraAttachToEntityInstruction;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataType;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData;
import org.cloudburstmc.protocol.bedrock.data.primitiveshape.*;
import org.cloudburstmc.protocol.bedrock.packet.*;

import java.util.Arrays;
import java.util.Collection;
import java.util.ListIterator;
import java.util.function.LongConsumer;

import static dev.waterdog.waterdogpe.network.protocol.Signals.mergeSignals;

/**
 * Class to map the proper entityIds to entity-related packets.
 */
public class EntityMap implements BedrockPacketHandler {
    private static final Collection<EntityDataType<Long>> ENTITY_DATA_FIELDS = Arrays.asList(
            EntityDataTypes.OWNER_EID,
            EntityDataTypes.TARGET_EID,
            EntityDataTypes.LEASH_HOLDER,
            EntityDataTypes.WITHER_TARGET_A,
            EntityDataTypes.WITHER_TARGET_B,
            EntityDataTypes.WITHER_TARGET_C,
            EntityDataTypes.TRADE_TARGET_EID,
            EntityDataTypes.BALLOON_ANCHOR_EID,
            EntityDataTypes.AGENT_EID
    );

    private final ProxiedPlayer player;
    private final RewriteData rewrite;

    public EntityMap(ProxiedPlayer player) {
        this.player = player;
        this.rewrite = player.getRewriteData();
    }

    public PacketSignal doRewrite(BedrockPacket packet) {
        return this.player.canRewrite() ? packet.handle(this) : PacketSignal.UNHANDLED;
    }

    private PacketSignal rewriteId(long from, LongConsumer setter) {
        long rewriteId = PlayerRewriteUtils.rewriteId(from, this.rewrite.getEntityId(), this.rewrite.getOriginalEntityId());
        if (rewriteId == from) {
            return PacketSignal.UNHANDLED;
        }
        setter.accept(rewriteId);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(MoveEntityAbsolutePacket packet) {
        return rewriteId(packet.getRuntimeEntityId(), packet::setRuntimeEntityId);
    }

    @Override
    public PacketSignal handle(EntityEventPacket packet) {
        return rewriteId(packet.getRuntimeEntityId(), packet::setRuntimeEntityId);
    }

    @Override
    public PacketSignal handle(MobEffectPacket packet) {
        return rewriteId(packet.getRuntimeEntityId(), packet::setRuntimeEntityId);
    }

    @Override
    public PacketSignal handle(UpdateAttributesPacket packet) {
        return rewriteId(packet.getRuntimeEntityId(), packet::setRuntimeEntityId);
    }

    @Override
    public PacketSignal handle(MobEquipmentPacket packet) {
        return rewriteId(packet.getRuntimeEntityId(), packet::setRuntimeEntityId);
    }

    @Override
    public PacketSignal handle(MobArmorEquipmentPacket packet) {
        return rewriteId(packet.getRuntimeEntityId(), packet::setRuntimeEntityId);
    }

    @Override
    public PacketSignal handle(PlayerActionPacket packet) {
        return rewriteId(packet.getRuntimeEntityId(), packet::setRuntimeEntityId);
    }

    @Override
    public PacketSignal handle(SetEntityDataPacket packet) {
        PacketSignal signal = rewriteId(packet.getRuntimeEntityId(), packet::setRuntimeEntityId);
        PacketSignal metaSignal = this.rewriteMetadata(packet.getMetadata());
        return mergeSignals(signal, metaSignal);
    }

    @Override
    public PacketSignal handle(SetEntityMotionPacket packet) {
        return rewriteId(packet.getRuntimeEntityId(), packet::setRuntimeEntityId);
    }

    @Override
    public PacketSignal handle(MoveEntityDeltaPacket packet) {
        return rewriteId(packet.getRuntimeEntityId(), packet::setRuntimeEntityId);
    }

    @Override
    public PacketSignal handle(SetLocalPlayerAsInitializedPacket packet) {
        return rewriteId(packet.getRuntimeEntityId(), packet::setRuntimeEntityId);
    }

    @Override
    public PacketSignal handle(AddPlayerPacket packet) {
        PacketSignal signal0 = rewriteId(packet.getRuntimeEntityId(), packet::setRuntimeEntityId);
        PacketSignal signal1 = rewriteId(packet.getUniqueEntityId(), packet::setUniqueEntityId);

        PacketSignal signal2 = PacketSignal.UNHANDLED;

        ListIterator<EntityLinkData> iterator = packet.getEntityLinks().listIterator();
        while (iterator.hasNext()) {
            EntityLinkData entityLink = iterator.next();
            long from = PlayerRewriteUtils.rewriteId(entityLink.from(), this.rewrite.getEntityId(), this.rewrite.getOriginalEntityId());
            long to = PlayerRewriteUtils.rewriteId(entityLink.to(), this.rewrite.getEntityId(), this.rewrite.getOriginalEntityId());
            if (entityLink.from() != from || entityLink.to() != to) {
                iterator.set(new EntityLinkData(from, to, entityLink.type(), entityLink.immediate(), entityLink.riderInitiated(), entityLink.vehicleAngularVelocity()));
                signal2 = PacketSignal.HANDLED;
            }
        }

        PacketSignal signal3 = this.rewriteMetadata(packet.getMetadata());
        return (signal0 == PacketSignal.HANDLED || signal1 == PacketSignal.HANDLED || signal2 == PacketSignal.HANDLED || signal3 == PacketSignal.HANDLED) ?
                PacketSignal.HANDLED : PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(AddEntityPacket packet) {
        PacketSignal signal0 = rewriteId(packet.getRuntimeEntityId(), packet::setRuntimeEntityId);
        PacketSignal signal1 = rewriteId(packet.getUniqueEntityId(), packet::setUniqueEntityId);

        PacketSignal signal2 = PacketSignal.UNHANDLED;

        ListIterator<EntityLinkData> iterator = packet.getEntityLinks().listIterator();
        while (iterator.hasNext()) {
            EntityLinkData entityLink = iterator.next();
            long from = PlayerRewriteUtils.rewriteId(entityLink.from(), this.rewrite.getEntityId(), this.rewrite.getOriginalEntityId());
            long to = PlayerRewriteUtils.rewriteId(entityLink.to(), this.rewrite.getEntityId(), this.rewrite.getOriginalEntityId());
            if (entityLink.from() != from || entityLink.to() != to) {
                iterator.set(new EntityLinkData(from, to, entityLink.type(), entityLink.immediate(), entityLink.riderInitiated(), entityLink.vehicleAngularVelocity()));
                signal2 = PacketSignal.HANDLED;
            }
        }

        PacketSignal signal4 = this.rewriteMetadata(packet.getMetadata());
        return (signal0 == PacketSignal.HANDLED || signal1 == PacketSignal.HANDLED || signal2 == PacketSignal.HANDLED || signal4 == PacketSignal.HANDLED) ?
                PacketSignal.HANDLED : PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(AddItemEntityPacket packet) {
        PacketSignal signal0 = rewriteId(packet.getRuntimeEntityId(), packet::setRuntimeEntityId);
        PacketSignal signal1 = rewriteId(packet.getUniqueEntityId(), packet::setUniqueEntityId);
        PacketSignal signal2 = this.rewriteMetadata(packet.getMetadata());
        return (signal0 == PacketSignal.HANDLED || signal1 == PacketSignal.HANDLED || signal2 == PacketSignal.HANDLED) ?
                PacketSignal.HANDLED : PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(AddPaintingPacket packet) {
        PacketSignal signal0 = rewriteId(packet.getRuntimeEntityId(), packet::setRuntimeEntityId);
        PacketSignal signal1 = rewriteId(packet.getUniqueEntityId(), packet::setUniqueEntityId);
        return mergeSignals(signal0, signal1);
    }

    @Override
    public PacketSignal handle(RemoveEntityPacket packet) {
        return rewriteId(packet.getUniqueEntityId(), packet::setUniqueEntityId);
    }

    @Override
    public PacketSignal handle(BossEventPacket packet) {
        PacketSignal signal0 = rewriteId(packet.getBossUniqueEntityId(), packet::setBossUniqueEntityId);
        PacketSignal signal1 = rewriteId(packet.getPlayerUniqueEntityId(), packet::setPlayerUniqueEntityId);
        return mergeSignals(signal0, signal1);
    }

    @Override
    public PacketSignal handle(TakeItemEntityPacket packet) {
        PacketSignal signal0 = rewriteId(packet.getRuntimeEntityId(), packet::setRuntimeEntityId);
        PacketSignal signal1 = rewriteId(packet.getItemRuntimeEntityId(), packet::setItemRuntimeEntityId);
        return mergeSignals(signal0, signal1);
    }

    @Override
    public PacketSignal handle(MovePlayerPacket packet) {
        PacketSignal signal0 = rewriteId(packet.getRuntimeEntityId(), packet::setRuntimeEntityId);
        PacketSignal signal1 = rewriteId(packet.getRidingRuntimeEntityId(), packet::setRidingRuntimeEntityId);
        return mergeSignals(signal0, signal1);
    }

    @Override
    public PacketSignal handle(InteractPacket packet) {
        return rewriteId(packet.getRuntimeEntityId(), packet::setRuntimeEntityId);
    }

    @Override
    public PacketSignal handle(PlayerLocationPacket packet) {
        return rewriteId(packet.getTargetEntityId(), packet::setTargetEntityId);
    }

    @Override
    public PacketSignal handle(SetEntityLinkPacket packet) {
        EntityLinkData entityLink = packet.getEntityLink();
        long from = PlayerRewriteUtils.rewriteId(entityLink.from(), this.rewrite.getEntityId(), this.rewrite.getOriginalEntityId());
        long to = PlayerRewriteUtils.rewriteId(entityLink.to(), this.rewrite.getEntityId(), this.rewrite.getOriginalEntityId());

        if (from != entityLink.from() || to != entityLink.to()) {
            packet.setEntityLink(new EntityLinkData(from, to, entityLink.type(), entityLink.immediate(), entityLink.riderInitiated(), entityLink.vehicleAngularVelocity()));
            return PacketSignal.HANDLED;
        }
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(AnimatePacket packet) {
        return rewriteId(packet.getRuntimeEntityId(), packet::setRuntimeEntityId);
    }

    @Override
    public PacketSignal handle(AdventureSettingsPacket packet) {
        return rewriteId(packet.getUniqueEntityId(), packet::setUniqueEntityId);
    }

    @Override
    public PacketSignal handle(PlayerListPacket packet) {
        if (packet.getAction() != PlayerListPacket.Action.ADD) {
            return PacketSignal.UNHANDLED;
        }

        PacketSignal signal = PacketSignal.UNHANDLED;

        for (PlayerListPacket.Entry entry : packet.getEntries()) {
            long rewriteId = PlayerRewriteUtils.rewriteId(entry.getEntityId(), this.rewrite.getEntityId(), this.rewrite.getOriginalEntityId());
            if (rewriteId != entry.getEntityId()) {
                signal = PacketSignal.HANDLED;
                entry.setEntityId(rewriteId);
            }
        }
        return signal;
    }

    @Override
    public PacketSignal handle(UpdateTradePacket packet) {
        PacketSignal signal0 = rewriteId(packet.getPlayerUniqueEntityId(), packet::setPlayerUniqueEntityId);
        PacketSignal signal1 = rewriteId(packet.getTraderUniqueEntityId(), packet::setTraderUniqueEntityId);
        return mergeSignals(signal0, signal1);
    }

    @Override
    public PacketSignal handle(RespawnPacket packet) {
        return rewriteId(packet.getRuntimeEntityId(), packet::setRuntimeEntityId);
    }

    @Override
    public PacketSignal handle(EmoteListPacket packet) {
        return rewriteId(packet.getRuntimeEntityId(), packet::setRuntimeEntityId);
    }

    public PacketSignal handle(NpcDialoguePacket packet) {
        return rewriteId(packet.getUniqueEntityId(), packet::setUniqueEntityId);
    }

    public PacketSignal handle(NpcRequestPacket packet) {
        return rewriteId(packet.getRuntimeEntityId(), packet::setRuntimeEntityId);
    }

    @Override
    public PacketSignal handle(EmotePacket packet) {
        return rewriteId(packet.getRuntimeEntityId(), packet::setRuntimeEntityId);
    }

    @Override
    public PacketSignal handle(SpawnParticleEffectPacket packet) {
        return rewriteId(packet.getUniqueEntityId(), packet::setUniqueEntityId);
    }

    @Override
    public PacketSignal handle(EntityPickRequestPacket packet) {
        return rewriteId(packet.getRuntimeEntityId(), packet::setRuntimeEntityId);
    }

    @Override
    public PacketSignal handle(EventPacket packet) {
        return rewriteId(packet.getUniqueEntityId(), packet::setUniqueEntityId);
    }

    @Override
    public PacketSignal handle(UpdatePlayerGameTypePacket packet) {
        return rewriteId(packet.getEntityId(), packet::setEntityId);
    }

    @Override
    public PacketSignal handle(UpdateAbilitiesPacket packet) {
        return rewriteId(packet.getUniqueEntityId(), packet::setUniqueEntityId);
    }

    @Override
    public PacketSignal handle(ClientCheatAbilityPacket packet) {
        return rewriteId(packet.getUniqueEntityId(), packet::setUniqueEntityId);
    }

    @Override
    public PacketSignal handle(PlayerUpdateEntityOverridesPacket packet) {
        return rewriteId(packet.getEntityUniqueId(), packet::setEntityUniqueId);
    }

    @Override
    public PacketSignal handle(LevelSoundEventPacket packet) {
        return rewriteId(packet.getEntityUniqueId(), packet::setEntityUniqueId);
    }

    @Override
    public PacketSignal handle(AnimateEntityPacket packet) {
        PacketSignal signal = PacketSignal.UNHANDLED;
        LongListIterator iterator = packet.getRuntimeEntityIds().listIterator();
        while (iterator.hasNext()) {
            PacketSignal returnedSignal = rewriteId(iterator.nextLong(), iterator::set);
            signal = mergeSignals(signal, returnedSignal);
        }
        return signal;
    }

    @Override
    public PacketSignal handle(MovementEffectPacket packet) {
        return rewriteId(packet.getEntityRuntimeId(), packet::setEntityRuntimeId);
    }

    @Override
    public PacketSignal handle(MovementPredictionSyncPacket packet) {
        return rewriteId(packet.getRuntimeEntityId(), packet::setRuntimeEntityId);
    }

    @Override
    public PacketSignal handle(UpdateEquipPacket packet) {
        return rewriteId(packet.getUniqueEntityId(), packet::setUniqueEntityId);
    }

    @Override
    public PacketSignal handle(CameraInstructionPacket packet) {
        PacketSignal signal = PacketSignal.UNHANDLED;
        CameraAttachToEntityInstruction attachInstruction = packet.getAttachInstruction();
        if (attachInstruction != null) {
            PacketSignal returnedSignal = rewriteId(attachInstruction.getUniqueEntityId(), attachInstruction::setUniqueEntityId);
            signal = mergeSignals(signal, returnedSignal);
        }
        return signal;
    }

    @Override
    public PacketSignal handle(PrimitiveShapesPacket packet) {
        PacketSignal signal = PacketSignal.UNHANDLED;
        ListIterator<PrimitiveShape> iterator = packet.getShapes().listIterator();
        while (iterator.hasNext()) {
            PrimitiveShape shape = iterator.next();
            Long attachedEntityId = shape.getAttachedToEntityId();
            if (attachedEntityId != null) {
                PacketSignal returnedSignal = rewritePrimitiveShapeAttachedEntityId(iterator, shape, attachedEntityId);
                signal = mergeSignals(signal, returnedSignal);
            }
        }
        return signal;
    }

    private PacketSignal rewritePrimitiveShapeAttachedEntityId(ListIterator<PrimitiveShape> iterator, PrimitiveShape shape, long attachedEntityId) {
        long rewriteId = PlayerRewriteUtils.rewriteId(attachedEntityId, this.rewrite.getEntityId(), this.rewrite.getOriginalEntityId());
        if (rewriteId == attachedEntityId) {
            return PacketSignal.UNHANDLED;
        }

        iterator.set(copyPrimitiveShape(shape, rewriteId));
        return PacketSignal.HANDLED;
    }

    private static PrimitiveShape copyPrimitiveShape(PrimitiveShape shape, Long attachedToEntityId) {
        PrimitiveShape.Type type = shape.getType();
        if (type == null) {
            return new PrimitiveShape(shape.getId(), shape.getDimension(), shape.getPosition(), shape.getScale(), shape.getRotation(),
                    shape.getTotalTimeLeft(), shape.getColor(), shape.getMaximumRenderDistance(), attachedToEntityId);
        }

        return switch (type) {
            case ARROW -> {
                PrimitiveArrow arrow = (PrimitiveArrow) shape;
                yield new PrimitiveArrow(shape.getId(), shape.getDimension(), shape.getPosition(), shape.getScale(), shape.getRotation(),
                        shape.getTotalTimeLeft(), shape.getColor(), shape.getMaximumRenderDistance(), arrow.getArrowEndPosition(),
                        arrow.getArrowHeadLength(), arrow.getArrowHeadRadius(), arrow.getArrowHeadSegments(), attachedToEntityId);
            }
            case BOX -> {
                PrimitiveBox box = (PrimitiveBox) shape;
                yield new PrimitiveBox(shape.getId(), shape.getDimension(), shape.getPosition(), shape.getScale(), shape.getRotation(),
                        shape.getTotalTimeLeft(), shape.getColor(), shape.getMaximumRenderDistance(), box.getBoxBounds(), attachedToEntityId);
            }
            case CIRCLE -> {
                PrimitiveCircle circle = (PrimitiveCircle) shape;
                yield new PrimitiveCircle(shape.getId(), shape.getDimension(), shape.getPosition(), shape.getScale(), shape.getRotation(),
                        shape.getTotalTimeLeft(), shape.getColor(), shape.getMaximumRenderDistance(), circle.getSegments(), attachedToEntityId);
            }
            case LINE -> {
                PrimitiveLine line = (PrimitiveLine) shape;
                yield new PrimitiveLine(shape.getId(), shape.getDimension(), shape.getPosition(), shape.getScale(), shape.getRotation(),
                        shape.getTotalTimeLeft(), shape.getColor(), shape.getMaximumRenderDistance(), line.getLineEndPosition(), attachedToEntityId);
            }
            case SPHERE -> {
                PrimitiveSphere sphere = (PrimitiveSphere) shape;
                yield new PrimitiveSphere(shape.getId(), shape.getDimension(), shape.getPosition(), shape.getScale(), shape.getRotation(),
                        shape.getTotalTimeLeft(), shape.getColor(), shape.getMaximumRenderDistance(), sphere.getSegments(), attachedToEntityId);
            }
            case TEXT -> {
                PrimitiveText text = (PrimitiveText) shape;
                yield new PrimitiveText(shape.getId(), shape.getDimension(), shape.getPosition(), shape.getScale(), shape.getRotation(),
                        shape.getTotalTimeLeft(), shape.getColor(), text.getText(), text.isUseRotation(), text.getBackgroundColor(),
                        text.isDepthTest(), text.isShowBackface(), text.isShowTextBackface(), shape.getMaximumRenderDistance(), attachedToEntityId);
            }
        };
    }

    private PacketSignal rewriteMetadata(EntityDataMap metadata) {
        PacketSignal signal = PacketSignal.UNHANDLED;
        for (EntityDataType<Long> data : ENTITY_DATA_FIELDS) {
            Long id = metadata.get(data);
            if (id != null) {
                long rewriteId = PlayerRewriteUtils.rewriteId(id, this.rewrite.getEntityId(), this.rewrite.getOriginalEntityId());
                if (rewriteId != id) {
                    metadata.put(data, rewriteId);
                    signal = PacketSignal.HANDLED;
                }
            }
        }
        return signal;
    }
}
