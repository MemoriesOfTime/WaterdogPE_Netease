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

package dev.waterdog.waterdogpe.network.protocol.handler.downstream;

import dev.waterdog.waterdogpe.command.Command;
import dev.waterdog.waterdogpe.event.defaults.FastTransferRequestEvent;
import dev.waterdog.waterdogpe.network.connection.client.ClientConnection;
import dev.waterdog.waterdogpe.network.protocol.ProtocolVersion;
import dev.waterdog.waterdogpe.network.protocol.Signals;
import dev.waterdog.waterdogpe.network.protocol.handler.ProxyPacketHandler;
import dev.waterdog.waterdogpe.network.protocol.handler.TransferCallback;
import dev.waterdog.waterdogpe.network.protocol.registry.DefinitionAggregator;
import dev.waterdog.waterdogpe.network.protocol.registry.FakeDefinitionRegistry;
import dev.waterdog.waterdogpe.network.protocol.registry.ServerIdMapping;
import dev.waterdog.waterdogpe.network.protocol.registry.TranslatingItemRegistry;
import dev.waterdog.waterdogpe.network.protocol.rewrite.RewriteMaps;
import dev.waterdog.waterdogpe.network.serverinfo.ServerInfo;
import dev.waterdog.waterdogpe.player.ProxiedPlayer;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.camera.CameraPreset;
import org.cloudburstmc.protocol.bedrock.data.command.CommandData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandEnumConstraint;
import org.cloudburstmc.protocol.bedrock.data.command.CommandEnumData;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleNamedDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.ContainerMixData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.MaterialReducer;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.PotionMixData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.FurnaceRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.RecipeData;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.NamedDefinition;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry;

import java.util.*;
import java.util.function.Consumer;

import static dev.waterdog.waterdogpe.network.protocol.Signals.mergeSignals;

public abstract class AbstractDownstreamHandler implements ProxyPacketHandler {

    protected final ClientConnection connection;
    protected final ProxiedPlayer player;

    public AbstractDownstreamHandler(ProxiedPlayer player, ClientConnection connection) {
        this.player = player;
        this.connection = connection;
    }

    @Override
    public PacketSignal handle(PlayStatusPacket packet) {
        if (packet.getStatus() != PlayStatusPacket.Status.PLAYER_SPAWN) {
            return PacketSignal.UNHANDLED;
        }
        TransferCallback transferCallback = player.getRewriteData().getTransferCallback();
        if (transferCallback != null && transferCallback.getConnection() == this.connection) {
            transferCallback.onPlayStatus();
        }
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(ItemComponentPacket packet) {
        if (!this.player.acceptItemComponentPacket()) {
            return Signals.CANCEL;
        }
        player.setAcceptItemComponentPacket(false);

        DefinitionAggregator aggregator = this.player.getProxy().getDefinitionAggregator(this.player.getProtocol(), this.player.isNetEaseClient());

        if (aggregator != null && this.player.getProtocol().isAfterOrEqual(ProtocolVersion.MINECRAFT_PE_1_21_60)) {
            // Registry aggregation: collect items, inject unified set
            String serverName = this.connection.getServerInfo().getServerName();
            aggregator.registerServer(serverName, packet.getItems(), null);

            // Set upstream helper with unified item registry
            BedrockCodecHelper upstreamHelper = this.player.getConnection().getPeer().getCodecHelper();
            upstreamHelper.setItemDefinitions(aggregator.buildUnifiedItemRegistry());

            // Replace packet items with unified definitions for client
            packet.getItems().clear();
            packet.getItems().addAll(aggregator.getUnifiedItemDefinitions());

            // Create mapping and setup downstream translating registry
            ServerIdMapping mapping = aggregator.createMapping(serverName);
            this.player.getRewriteData().setCurrentMapping(mapping);
            setupDownstreamTranslatingRegistry(mapping, serverName, aggregator);
        } else if (aggregator != null) {
            // ≤1.21.50 with aggregator: aggregate component data from all servers
            aggregator.registerComponentData(packet.getItems());
            packet.getItems().clear();
            packet.getItems().addAll(aggregator.getUnifiedComponentItems());
        } else if (this.player.getProtocol().isAfterOrEqual(ProtocolVersion.MINECRAFT_PE_1_21_60)) {
            setItemDefinitions(packet.getItems());
        }

        return aggregator != null ? PacketSignal.HANDLED : PacketSignal.UNHANDLED;
    }

    /**
     * Intercepting a downstream-initiated transfer must not depend on which handler happens to be
     * installed. {@link SwitchDownstreamHandler} claims the new connection as the player's active
     * downstream while handling StartGame, but {@link ConnectedDownstreamHandler} is only installed
     * once {@link TransferCallback} finishes phase 2. A TransferPacket arriving in between used to
     * be forwarded verbatim, which hands the player to another server behind the proxy's back and
     * detaches them from the proxy for good.
     * <p>
     * Every path that forwards the packet instead of acting on it is logged: losing a player this
     * way is otherwise completely invisible.
     */
    @Override
    public PacketSignal handle(TransferPacket packet) {
        if (this.connection != this.player.getDownstreamConnection()
                && this.connection != this.player.getPendingConnection()) {
            // A discarded downstream must never be able to redirect the player.
            this.player.getLogger().debug("[{}] Ignored TransferPacket from a stale connection to {}",
                    this.player.getName(), this.connection.getServerInfo().getServerName());
            return Signals.CANCEL;
        }

        if (!this.player.getProxy().getConfiguration().useFastTransfer()) {
            this.player.getLogger().debug("[{}] Passing TransferPacket to {}:{} through: fast transfer is disabled",
                    this.player.getName(), packet.getAddress(), packet.getPort());
            return PacketSignal.UNHANDLED;
        }

        ServerInfo serverInfo = this.player.getProxy().getServerInfo(packet.getAddress());
        if (serverInfo == null) {
            serverInfo = this.player.getProxy().getServerInfo(packet.getAddress(), packet.getPort());
        }

        FastTransferRequestEvent event = new FastTransferRequestEvent(serverInfo, this.player, packet.getAddress(), packet.getPort());
        this.player.getProxy().getEventManager().callEvent(event);

        if (event.isCancelled()) {
            this.player.getLogger().debug("[{}] Passing TransferPacket to {}:{} through: FastTransferRequestEvent was cancelled",
                    this.player.getName(), packet.getAddress(), packet.getPort());
            return PacketSignal.UNHANDLED;
        }

        if (event.getServerInfo() == null) {
            this.player.getLogger().warning("[{}] Passing TransferPacket to {}:{} through: no downstream server matches this address, the player will leave the proxy",
                    this.player.getName(), packet.getAddress(), packet.getPort());
            return PacketSignal.UNHANDLED;
        }

        TransferCallback activeTransfer = this.player.getRewriteData().getTransferCallback();
        if (activeTransfer != null && activeTransfer.getPhase() != TransferCallback.TransferPhase.RESET) {
            // connect() denies overlapping transfers, so this request is about to be dropped. Say so:
            // this is the point where a downstream-initiated redirect silently disappears.
            this.player.getLogger().info("[{}] TransferPacket to {} arrived while the transfer to {} is still in progress, dropping the request",
                    this.player.getName(), event.getServerInfo().getServerName(), activeTransfer.getTargetServer().getServerName());
        }

        this.player.connect(event.getServerInfo());
        return Signals.CANCEL;
    }

    @Override
    public void sendProxiedBatch(BedrockBatchWrapper batch) {
        ClientConnection current = this.player.getDownstreamConnection();
        if (current != null && this.connection != current) {
            // Noop. Drop batches from a downstream that is no longer the player's active one.
            // Null check is for the initial connection.
            return;
        }
        if (this.player.getConnection().isConnected()) {
            this.player.getConnection().sendPacket(batch.retain());
        }
    }

    @Override
    public PacketSignal doPacketRewrite(BedrockPacket packet) {
        RewriteMaps rewriteMaps = this.player.getRewriteMaps();
        if (rewriteMaps.getBlockMap() != null) {
            return mergeSignals(rewriteMaps.getBlockMap().doRewrite(packet),
                    ProxyPacketHandler.super.doPacketRewrite(packet));
        }
        return ProxyPacketHandler.super.doPacketRewrite(packet);
    }

    @Override
    public PacketSignal handle(AvailableCommandsPacket packet) {
        if (!this.player.getProxy().getConfiguration().injectCommands()) {
            return PacketSignal.UNHANDLED;
        }
        int sizeBefore = packet.getCommands().size();

        for (Command command : this.player.getProxy().getCommandMap().getCommands().values()) {
            if (command.getPermission() == null || this.player.hasPermission(command.getPermission())) {
                packet.getCommands().add(command.getCommandData());
            }
        }

        if (packet.getCommands().size() == sizeBefore) {
            return PacketSignal.UNHANDLED;
        }

        // Some server commands are missing aliases, which protocol lib doesn't like
        ListIterator<CommandData> iterator = packet.getCommands().listIterator();
        while (iterator.hasNext()) {
            CommandData command = iterator.next();
            if (command.getAliases() != null) {
                continue;
            }

            Map<String, Set<CommandEnumConstraint>> aliases = new LinkedHashMap<>();
            aliases.put(command.getName(), EnumSet.of(CommandEnumConstraint.ALLOW_ALIASES));

            iterator.set(new CommandData(command.getName(),
                    command.getDescription(),
                    command.getFlags(),
                    command.getPermission(),
                    new CommandEnumData(command.getName() + "_aliases", aliases, false),
                    Collections.emptyList(),
                    command.getOverloads()));
        }
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ChunkRadiusUpdatedPacket packet) {
        this.player.getLoginData().getChunkRadius().setRadius(packet.getRadius());
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(ChangeDimensionPacket packet) {
        this.player.getRewriteData().setDimension(packet.getDimension());
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(ClientCacheMissResponsePacket packet) {
        if (this.player.getProtocol().isBefore(ProtocolVersion.MINECRAFT_PE_1_18_30)) {
            this.player.getChunkBlobs().removeAll(packet.getBlobs().keySet());
        }
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(CameraPresetsPacket packet) {
        setCameraPresetDefinitions(packet.getPresets());
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(AvailableEntityIdentifiersPacket packet) {
        DefinitionAggregator aggregator = this.player.getProxy().getDefinitionAggregator(this.player.getProtocol(), this.player.isNetEaseClient());
        if (aggregator == null) {
            return PacketSignal.UNHANDLED;
        }

        String serverName = this.connection.getServerInfo().getServerName();
        aggregator.registerEntityIdentifiers(serverName, packet.getIdentifiers());

        // Replace with merged entity identifiers
        NbtMap merged = aggregator.getMergedEntityIdentifiers();
        if (merged != null) {
            packet.setIdentifiers(merged);
            return PacketSignal.HANDLED;
        }
        return PacketSignal.UNHANDLED;
    }

    @Override
    public PacketSignal handle(CreativeContentPacket packet) {
        ServerIdMapping mapping = this.player.getRewriteData().getCurrentMapping();
        if (mapping == null || mapping.isIdentity()) {
            return PacketSignal.UNHANDLED;
        }
        // Force re-encoding with unified item IDs
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(InventoryContentPacket packet) {
        ServerIdMapping mapping = this.player.getRewriteData().getCurrentMapping();
        if (mapping == null || mapping.isIdentity()) {
            return PacketSignal.UNHANDLED;
        }
        // Force re-encoding with unified item IDs
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(InventorySlotPacket packet) {
        ServerIdMapping mapping = this.player.getRewriteData().getCurrentMapping();
        if (mapping == null || mapping.isIdentity()) {
            return PacketSignal.UNHANDLED;
        }
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(CraftingDataPacket packet) {
        ServerIdMapping mapping = this.player.getRewriteData().getCurrentMapping();
        if (mapping == null || mapping.isIdentity()) {
            return PacketSignal.UNHANDLED;
        }

        // Translate FurnaceRecipeData raw inputId (FURNACE / FURNACE_DATA types)
        List<RecipeData> craftingData = packet.getCraftingData();
        for (int i = 0; i < craftingData.size(); i++) {
            RecipeData recipe = craftingData.get(i);
            if (recipe instanceof FurnaceRecipeData furnace) {
                int newInputId = mapping.translateItemId(furnace.getInputId());
                if (newInputId != furnace.getInputId()) {
                    craftingData.set(i, FurnaceRecipeData.of(furnace.getType(), newInputId,
                            furnace.getInputData(), furnace.getResult(), furnace.getTag()));
                }
            }
        }

        // Translate PotionMixData raw IDs
        List<PotionMixData> potionMixData = packet.getPotionMixData();
        for (int i = 0; i < potionMixData.size(); i++) {
            PotionMixData potion = potionMixData.get(i);
            int newInputId = mapping.translateItemId(potion.getInputId());
            int newReagentId = mapping.translateItemId(potion.getReagentId());
            int newOutputId = mapping.translateItemId(potion.getOutputId());
            if (newInputId != potion.getInputId() || newReagentId != potion.getReagentId() || newOutputId != potion.getOutputId()) {
                potionMixData.set(i, new PotionMixData(newInputId, potion.getInputMeta(),
                        newReagentId, potion.getReagentMeta(), newOutputId, potion.getOutputMeta()));
            }
        }

        // Translate ContainerMixData raw IDs
        List<ContainerMixData> containerMixData = packet.getContainerMixData();
        for (int i = 0; i < containerMixData.size(); i++) {
            ContainerMixData container = containerMixData.get(i);
            int newInputId = mapping.translateItemId(container.getInputId());
            int newReagentId = mapping.translateItemId(container.getReagentId());
            int newOutputId = mapping.translateItemId(container.getOutputId());
            if (newInputId != container.getInputId() || newReagentId != container.getReagentId() || newOutputId != container.getOutputId()) {
                containerMixData.set(i, new ContainerMixData(newInputId, newReagentId, newOutputId));
            }
        }

        // Translate MaterialReducer raw inputId
        List<MaterialReducer> materialReducers = packet.getMaterialReducers();
        for (int i = 0; i < materialReducers.size(); i++) {
            MaterialReducer reducer = materialReducers.get(i);
            int newInputId = mapping.translateItemId(reducer.getInputId());
            if (newInputId != reducer.getInputId()) {
                materialReducers.set(i, new MaterialReducer(newInputId, reducer.getItemCounts()));
            }
        }

        return PacketSignal.HANDLED;
    }

    protected PacketSignal onPlayStatus(PlayStatusPacket packet, Consumer<String> failedTask, ClientConnection connection) {
        String message;
        switch (packet.getStatus()) {
            case LOGIN_SUCCESS -> {
                if (this.player.getProtocol().isAfterOrEqual(ProtocolVersion.MINECRAFT_PE_1_12)) {
                    connection.sendPacket(this.player.getLoginData().getCachePacket());
                }
                return Signals.CANCEL;
            }
            case LOGIN_FAILED_CLIENT_OLD, LOGIN_FAILED_SERVER_OLD -> message = "Incompatible version";
            case FAILED_SERVER_FULL_SUB_CLIENT -> message = "Server is full";
            default -> {
                return PacketSignal.UNHANDLED;
            }
        }

        failedTask.accept(message);
        return Signals.CANCEL;
    }

    @Override
    public RewriteMaps getRewriteMaps() {
        return this.player.getRewriteMaps();
    }

    @Override
    public ClientConnection getConnection() {
        return connection;
    }

    protected static final String BLOCKING_ID = "minecraft:shield";

    protected void setItemDefinitions(Collection<ItemDefinition> definitions) {
        SimpleDefinitionRegistry.Builder<ItemDefinition> itemRegistry = SimpleDefinitionRegistry.builder();
        IntSet runtimeIds = new IntOpenHashSet();
        for (ItemDefinition definition : definitions) {
            if (runtimeIds.add(definition.getRuntimeId())) {
                itemRegistry.add(definition);
            } else {
                player.getLogger().warning("[{}|{}] has duplicate item definition: {}", this.player.getName(), this.connection.getServerInfo().getServerName(), definition);
            }
        }
        SimpleDefinitionRegistry<ItemDefinition> registry = itemRegistry.build();
        this.player.getConnection().getPeer().getCodecHelper().setItemDefinitions(registry);
        this.connection.getCodecHelper().setItemDefinitions(registry);
    }

    protected void setCameraPresetDefinitions(Collection<CameraPreset> presets) {
        BedrockCodecHelper codecHelper = this.player.getConnection()
                .getPeer()
                .getCodecHelper();
        SimpleDefinitionRegistry.Builder<NamedDefinition> registry = SimpleDefinitionRegistry.builder();
        int id = 0;
        for (CameraPreset preset : presets) {
            registry.add(new SimpleNamedDefinition(preset.getIdentifier(), id++));
        }
        codecHelper.setCameraPresetDefinitions(registry.build());
    }

    /**
     * Creates a separate BedrockCodecHelper for the downstream connection with TranslatingItemRegistry.
     * Shared across InitialHandler and SwitchDownstreamHandler.
     */
    protected void setupDownstreamTranslatingRegistry(ServerIdMapping mapping, String serverName, DefinitionAggregator aggregator) {
        if (mapping.isIdentity()) {
            this.connection.getCodecHelper().setItemDefinitions(aggregator.buildUnifiedItemRegistry());
            return;
        }

        TranslatingItemRegistry translatingRegistry = aggregator.buildTranslatingRegistry(serverName);
        if (translatingRegistry == null) {
            this.connection.getCodecHelper().setItemDefinitions(aggregator.buildUnifiedItemRegistry());
            return;
        }

        ProtocolVersion protocol = this.player.getProtocol();
        BedrockCodec codec = this.player.isNetEaseClient() ? protocol.getNetEaseCodec() : protocol.getCodec();
        BedrockCodecHelper downstreamHelper = codec.createHelper();
        downstreamHelper.setBlockDefinitions(FakeDefinitionRegistry.createBlockRegistry());
        downstreamHelper.setItemDefinitions(translatingRegistry);

        // Copy camera preset definitions if already set
        BedrockCodecHelper upstreamHelper = this.player.getConnection().getPeer().getCodecHelper();
        if (upstreamHelper.getCameraPresetDefinitions() != null) {
            downstreamHelper.setCameraPresetDefinitions(upstreamHelper.getCameraPresetDefinitions());
        }

        this.connection.setCodecHelper(codec, downstreamHelper);
    }
}
