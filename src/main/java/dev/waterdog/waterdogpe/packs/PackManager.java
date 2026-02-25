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

import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.event.defaults.ResourcePacksRebuildEvent;
import dev.waterdog.waterdogpe.network.protocol.ProtocolVersion;
import dev.waterdog.waterdogpe.packs.types.ResourcePack;
import dev.waterdog.waterdogpe.packs.types.ZipResourcePack;
import dev.waterdog.waterdogpe.utils.FileUtils;
import io.netty.buffer.Unpooled;
import lombok.Getter;
import org.cloudburstmc.protocol.bedrock.data.ResourcePackType;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.common.util.Preconditions;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PackManager {

    private static final long CHUNK_SIZE = 102400;

    private static final PathMatcher ZIP_PACK_MATCHER = FileSystems.getDefault().getPathMatcher("glob:**.{zip,mcpack}");
    private static final ResourcePackStackPacket.Entry EDU_PACK = new ResourcePackStackPacket.Entry("0fba4063-dba1-4281-9b89-ff9390653530", "1.0.0", "");

    private final ProxyServer proxy;
    @Getter
    private final Map<UUID, ResourcePack> packs = new HashMap<>();
    @Getter
    private final Map<String, ResourcePack> packsByIdVer = new HashMap<>();

    @Getter
    private final ResourcePacksInfoPacket packsInfoPacket = new ResourcePacksInfoPacket();
    @Getter
    private final ResourcePackStackPacket stackPacket = new ResourcePackStackPacket();

    public PackManager(ProxyServer proxy) {
        this.proxy = proxy;
    }

    public void loadPacks(Path packsDirectory) {
        Preconditions.checkNotNull(packsDirectory, "Packs directory can not be null!");
        Preconditions.checkArgument(Files.isDirectory(packsDirectory), packsDirectory + " must be directory!");
        this.proxy.getLogger().info("Loading resource packs!");

        try {
            DirectoryStream<Path> stream = Files.newDirectoryStream(packsDirectory);
            for (Path path : stream) {
                ResourcePack resourcePack = this.constructPack(path);
                if (resourcePack != null) {
                    String packIdVer = resourcePack.getPackId() + "_" + resourcePack.getPackManifest().getHeader().getVersion();
                    this.packsByIdVer.put(packIdVer, resourcePack);
                    this.packs.put(resourcePack.getPackId(), resourcePack);
                }
            }
        } catch (IOException e) {
            this.proxy.getLogger().error("Can not load packs!", e);
        }

        this.rebuildPackets();
        this.proxy.getLogger().info("Loaded " + this.packs.size() + " packs!");
    }

    private ResourcePack constructPack(Path packPath) {
        Class<? extends ResourcePack> loader = this.getPackLoader(packPath);
        if (loader == null) {
            return null;
        }

        try {
            ResourcePack pack = this.loadPack(packPath, loader);
            if (pack != null) {
                return pack;
            }
            this.proxy.getLogger().error("Resource pack manifest.json is invalid or was not found in " + packPath.getFileName() + ", please make sure that you zip the content of the pack and not the folder! Read more on troubleshooting here: https://docs.waterdog.dev/books/waterdogpe-setup/page/troubleshooting");
        } catch (Exception e) {
            this.proxy.getLogger().error("Can not load resource pack from: " + packPath.getFileName(), e);
        }
        return null;
    }

    private ResourcePack loadPack(Path packPath, Class<? extends ResourcePack> clazz) throws Exception {
        ResourcePack pack = clazz.getDeclaredConstructor(Path.class).newInstance(packPath);
        if (!pack.loadManifest() || !pack.getPackManifest().validate()) {
            return null;
        }

        pack.setSupportType(this.detectPackSupportType(pack));

        File contentKeyFile = new File(packPath.getParent().toFile(), packPath.toFile().getName() + ".key");
        pack.setContentKey(contentKeyFile.exists() ? Files.readString(contentKeyFile.toPath(), StandardCharsets.UTF_8).replace("\n", "") : "");

        if (this.proxy.getConfiguration().getPackCacheSize() >= (pack.getPackSize() / FileUtils.INT_MEGABYTE)) {
            pack.saveToCache();
        }
        return pack;
    }

    /**
     * Determine the SupportType of a resource pack based on its module type.
     * TYPE_DATA ("data") and TYPE_BEHAVIOR ("behavior") are behavior pack types which should not be sent to vanilla (Microsoft) clients.
     */
    private ResourcePack.SupportType detectPackSupportType(ResourcePack pack) {
        String packType = pack.getType();
        if (ResourcePack.TYPE_DATA.equals(packType) || ResourcePack.TYPE_BEHAVIOR.equals(packType)) {
            this.proxy.getLogger().info("Detected behavior pack (type=" + packType + ", NetEase only): " + pack.getPackPath().getFileName());
            return ResourcePack.SupportType.NETEASE;
        }
        return ResourcePack.SupportType.UNIVERSAL;
    }

    /**
     * We are currently supporting only zipped resource packs
     *
     * @param path to resource pack.
     * @return class which will be used to load pack.
     */
    public Class<? extends ResourcePack> getPackLoader(Path path) {
        if (ZIP_PACK_MATCHER.matches(path)) {
            return ZipResourcePack.class;
        }
        return null;
    }

    public boolean registerPack(ResourcePack resourcePack) {
        Preconditions.checkNotNull(resourcePack, "Resource pack can not be null!");
        Preconditions.checkArgument(resourcePack.getPackManifest().validate(), "Resource pack has invalid manifest!");

        if (this.packs.get(resourcePack.getPackId()) != null) {
            return false;
        }

        String packIdVer = resourcePack.getPackId() + "_" + resourcePack.getVersion();
        this.packsByIdVer.put(packIdVer, resourcePack);
        this.packs.put(resourcePack.getPackId(), resourcePack);
        this.rebuildPackets();
        return true;
    }

    public boolean unregisterPack(UUID packId) {
        ResourcePack resourcePack = this.packs.remove(packId);
        if (resourcePack == null) {
            return false;
        }

        String packIdVer = resourcePack.getPackId() + "_" + resourcePack.getVersion();
        this.packsByIdVer.remove(packIdVer);
        this.rebuildPackets();
        return true;
    }

    public void rebuildPackets() {
        this.packsInfoPacket.setForcedToAccept(this.proxy.getConfiguration().isForceServerPacks());
        this.packsInfoPacket.setWorldTemplateId(UUID.randomUUID());
        this.packsInfoPacket.setWorldTemplateVersion("");
        this.stackPacket.setForcedToAccept(this.proxy.getConfiguration().isOverwriteClientPacks());

        this.packsInfoPacket.getBehaviorPackInfos().clear();
        this.packsInfoPacket.getResourcePackInfos().clear();

        this.stackPacket.getBehaviorPacks().clear();
        this.stackPacket.getResourcePacks().clear();

        this.stackPacket.setGameVersion("");

        boolean hasAddonPacks = false;
        for (ResourcePack pack : this.packs.values()) {
            String packType = pack.getType();
            boolean isBehaviorPack = ResourcePack.TYPE_DATA.equals(packType) || ResourcePack.TYPE_BEHAVIOR.equals(packType);
            if (isBehaviorPack) {
                hasAddonPacks = true;
            }

            ResourcePacksInfoPacket.Entry infoEntry = new ResourcePacksInfoPacket.Entry(
                    pack.getPackId(),
                    pack.getVersion().toString(),
                    pack.getPackSize(),
                    pack.getContentKey(),
                    "", // subPackName
                    pack.getContentKey().isEmpty() ? "" : pack.getPackId().toString(), // contentId
                    false, // scripting
                    false,  // raytracingCapable
                    isBehaviorPack, // addonPack
                    null // cdnUrl
            );
            ResourcePackStackPacket.Entry stackEntry = new ResourcePackStackPacket.Entry(pack.getPackId().toString(), pack.getVersion().toString(), "");
            if (isBehaviorPack) {
                this.packsInfoPacket.getBehaviorPackInfos().add(infoEntry);
                this.stackPacket.getBehaviorPacks().add(stackEntry);
            } else {
                this.packsInfoPacket.getResourcePackInfos().add(infoEntry);
                this.stackPacket.getResourcePacks().add(stackEntry);
            }
        }
        // Set hasAddonPacks flag (since v662 1.20.70)
        this.packsInfoPacket.setHasAddonPacks(hasAddonPacks);

        if (this.proxy.getConfiguration().enableEducationFeatures()) {
            this.stackPacket.getBehaviorPacks().add(EDU_PACK);
        }
        ResourcePacksRebuildEvent event = new ResourcePacksRebuildEvent(this.packsInfoPacket, this.stackPacket);
        this.proxy.getEventManager().callEvent(event);
    }

    /**
     * Build protocol-version-aware ResourcePacksInfoPacket.
     * v729+: behaviorPackInfos is no longer serialized, merge behavior packs into resourcePackInfos.
     */
    public ResourcePacksInfoPacket buildPacksInfoPacket(ProtocolVersion protocol) {
        ResourcePacksInfoPacket packet = new ResourcePacksInfoPacket();
        packet.setForcedToAccept(this.packsInfoPacket.isForcedToAccept());
        packet.setWorldTemplateId(this.packsInfoPacket.getWorldTemplateId());
        packet.setWorldTemplateVersion(this.packsInfoPacket.getWorldTemplateVersion());
        packet.setHasAddonPacks(this.packsInfoPacket.isHasAddonPacks());
        packet.setScriptingEnabled(this.packsInfoPacket.isScriptingEnabled());
        packet.setForcingServerPacksEnabled(this.packsInfoPacket.isForcingServerPacksEnabled());

        if (protocol.isAfterOrEqual(ProtocolVersion.MINECRAFT_PE_1_21_30)) {
            // v729+: merge behavior packs into resource pack list
            packet.getResourcePackInfos().addAll(this.packsInfoPacket.getResourcePackInfos());
            packet.getResourcePackInfos().addAll(this.packsInfoPacket.getBehaviorPackInfos());
        } else {
            packet.getResourcePackInfos().addAll(this.packsInfoPacket.getResourcePackInfos());
            packet.getBehaviorPackInfos().addAll(this.packsInfoPacket.getBehaviorPackInfos());
        }
        return packet;
    }

    /**
     * Build protocol-version-aware ResourcePackStackPacket.
     * v898+: behaviorPacks is no longer serialized, merge behavior packs into resourcePacks.
     */
    public ResourcePackStackPacket buildStackPacket(ProtocolVersion protocol) {
        ResourcePackStackPacket packet = new ResourcePackStackPacket();
        packet.setForcedToAccept(this.stackPacket.isForcedToAccept());
        packet.setGameVersion(this.stackPacket.getGameVersion());
        packet.getExperiments().addAll(this.stackPacket.getExperiments());
        packet.setExperimentsPreviouslyToggled(this.stackPacket.isExperimentsPreviouslyToggled());

        if (protocol.isAfterOrEqual(ProtocolVersion.MINECRAFT_PE_1_21_130)) {
            // v898+: merge behavior packs into resource pack list
            packet.getResourcePacks().addAll(this.stackPacket.getResourcePacks());
            packet.getResourcePacks().addAll(this.stackPacket.getBehaviorPacks());
        } else {
            packet.getResourcePacks().addAll(this.stackPacket.getResourcePacks());
            packet.getBehaviorPacks().addAll(this.stackPacket.getBehaviorPacks());
        }
        return packet;
    }

    public ResourcePackDataInfoPacket packInfoFromIdVer(String idVersion) {
        ResourcePack resourcePack = this.packsByIdVer.get(idVersion);
        if (resourcePack == null) {
            return null;
        }

        ResourcePackDataInfoPacket packet = new ResourcePackDataInfoPacket();
        packet.setPackId(resourcePack.getPackId());
        packet.setPackVersion(resourcePack.getVersion().toString());
        packet.setMaxChunkSize(CHUNK_SIZE);
        packet.setChunkCount((resourcePack.getPackSize() - 1) / packet.getMaxChunkSize() + 1);
        packet.setCompressedPackSize(resourcePack.getPackSize());
        packet.setHash(resourcePack.getHash());

        String packType = resourcePack.getType();
        if (ResourcePack.TYPE_RESOURCES.equals(packType)) {
            packet.setType(ResourcePackType.RESOURCES);   // wire 6
        } else if (ResourcePack.TYPE_DATA.equals(packType) || ResourcePack.TYPE_BEHAVIOR.equals(packType)) {
            packet.setType(ResourcePackType.DATA_ADD_ON); // wire 4
        } else {
            packet.setType(ResourcePackType.RESOURCES);   // fallback
        }
        return packet;
    }

    public ResourcePackChunkDataPacket packChunkDataPacket(String idVersion, ResourcePackChunkRequestPacket from) {
        ResourcePack resourcePack = this.packsByIdVer.get(idVersion);
        if (resourcePack == null) {
            return null;
        }

        ResourcePackChunkDataPacket packet = new ResourcePackChunkDataPacket();
        packet.setPackId(from.getPackId());
        packet.setPackVersion(from.getPackVersion());
        packet.setChunkIndex(from.getChunkIndex());
        packet.setData(Unpooled.wrappedBuffer(resourcePack.getChunk((int) CHUNK_SIZE * from.getChunkIndex(), (int) CHUNK_SIZE)));
        packet.setProgress(CHUNK_SIZE * from.getChunkIndex());
        return packet;
    }

}
