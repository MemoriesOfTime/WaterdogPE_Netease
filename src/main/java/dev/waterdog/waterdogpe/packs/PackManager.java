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

import io.netty.buffer.Unpooled;
import lombok.Getter;
import org.cloudburstmc.protocol.bedrock.data.ResourcePackType;
import org.cloudburstmc.protocol.bedrock.packet.*;
import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.event.defaults.ResourcePacksRebuildEvent;
import dev.waterdog.waterdogpe.packs.types.ResourcePack;
import dev.waterdog.waterdogpe.packs.types.ZipResourcePack;
import dev.waterdog.waterdogpe.utils.FileUtils;
import org.cloudburstmc.protocol.common.util.Preconditions;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PackManager {

    private static final long CHUNK_SIZE = 8192;

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

        // Detect NetEase mod behavior pack by checking for pack_manifest.json
        pack.setSupportType(this.detectPackSupportType(pack));

        File contentKeyFile = new File(packPath.getParent().toFile(), packPath.toFile().getName() + ".key");
        pack.setContentKey(contentKeyFile.exists() ? Files.readString(contentKeyFile.toPath(), StandardCharsets.UTF_8).replace("\n", "") : "");

        if (this.proxy.getConfiguration().getPackCacheSize() >= (pack.getPackSize() / FileUtils.INT_MEGABYTE)) {
            pack.saveToCache();
        }
        return pack;
    }

    /**
     * Detect the support type of a resource pack.
     * NetEase mod behavior packs typically use pack_manifest.json instead of manifest.json.
     */
    private ResourcePack.SupportType detectPackSupportType(ResourcePack pack) {
        try {
            // Check if pack_manifest.json exists (NetEase specific)
            Path packManifestPath = Paths.get("pack_manifest.json");
            if (pack.getStream(packManifestPath) != null) {
                this.proxy.getLogger().info("Detected NetEase mod pack: " + pack.getPackPath().getFileName());
                return ResourcePack.SupportType.NETEASE;
            }
        } catch (IOException e) {
            // Ignore, will default to UNIVERSAL
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
        // Use all-zeros UUID (no world template), matching Nukkit-MOT's default behavior.
        // A random UUID could be misinterpreted as referencing a world template pack.
        this.packsInfoPacket.setWorldTemplateId(new UUID(0, 0));
        this.packsInfoPacket.setWorldTemplateVersion("");
        this.stackPacket.setForcedToAccept(this.proxy.getConfiguration().isOverwriteClientPacks());

        this.packsInfoPacket.getBehaviorPackInfos().clear();
        this.packsInfoPacket.getResourcePackInfos().clear();

        this.stackPacket.getBehaviorPacks().clear();
        this.stackPacket.getResourcePacks().clear();

        this.stackPacket.setGameVersion("");

        boolean hasAddonPacks = false;
        for (ResourcePack pack : this.packs.values()) {
            boolean isAddonPack = pack.getType().equals(ResourcePack.TYPE_DATA);
            if (isAddonPack) {
                hasAddonPacks = true;
            }
            
            ResourcePacksInfoPacket.Entry infoEntry = new ResourcePacksInfoPacket.Entry(
                    pack.getPackId(), 
                    pack.getVersion().toString(),
                    pack.getPackSize(), 
                    pack.getContentKey(), 
                    "", // subPackName
                    pack.getContentKey().equals("") ? "" : pack.getPackId().toString(), // contentId
                    false, // scripting
                    true,  // raytracingCapable
                    isAddonPack, // addonPack
                    null // cdnUrl
            );
            ResourcePackStackPacket.Entry stackEntry = new ResourcePackStackPacket.Entry(pack.getPackId().toString(), pack.getVersion().toString(), "");
            if (pack.getType().equals(ResourcePack.TYPE_RESOURCES)) {
                this.packsInfoPacket.getResourcePackInfos().add(infoEntry);
                this.stackPacket.getResourcePacks().add(stackEntry);
            } else if (pack.getType().equals(ResourcePack.TYPE_DATA)) {
                this.packsInfoPacket.getBehaviorPackInfos().add(infoEntry);
                this.stackPacket.getBehaviorPacks().add(stackEntry);
            }
        }

        // Set hasAddonPacks flag (since v662 1.20.70)
        this.packsInfoPacket.setHasAddonPacks(hasAddonPacks);

        if (this.proxy.getConfiguration().enableEducationFeatures()) {
            this.stackPacket.getBehaviorPacks().add(EDU_PACK);
        }
        
        // Debug logging
        this.proxy.getLogger().debug("[PackManager] Rebuilt packs: {} resource, {} behavior, hasAddonPacks={}",
            this.packsInfoPacket.getResourcePackInfos().size(),
            this.packsInfoPacket.getBehaviorPackInfos().size(),
            hasAddonPacks);
        
        ResourcePacksRebuildEvent event = new ResourcePacksRebuildEvent(this.packsInfoPacket, this.stackPacket);
        this.proxy.getEventManager().callEvent(event);
    }

    /**
     * Look up a ResourcePack by either UUID_VERSION or UUID-only key.
     * Tries packsByIdVer first (UUID_VERSION), then falls back to packs map (UUID).
     * This is needed because NetEase clients receive UUID-only pack IDs (no version suffix)
     * in ResourcePackDataInfoPacket, so they send UUID-only in subsequent requests.
     */
    private ResourcePack lookupPack(String key) {
        ResourcePack pack = this.packsByIdVer.get(key);
        if (pack != null) {
            return pack;
        }
        // Fallback: try parsing as UUID for direct lookup
        try {
            pack = this.packs.get(UUID.fromString(key));
        } catch (IllegalArgumentException ignored) {
            // Not a valid UUID, try extracting UUID from "UUID_VERSION" format
            int idx = key.indexOf('_');
            if (idx > 0) {
                try {
                    pack = this.packs.get(UUID.fromString(key.substring(0, idx)));
                } catch (IllegalArgumentException ignored2) {
                }
            }
        }
        return pack;
    }

    public ResourcePackDataInfoPacket packInfoFromIdVer(String idVersion) {
        ResourcePack resourcePack = this.lookupPack(idVersion);
        if (resourcePack == null) {
            return null;
        }

        ResourcePackDataInfoPacket packet = new ResourcePackDataInfoPacket();
        packet.setPackId(resourcePack.getPackId());
        // Don't set packVersion — the serializer will send just UUID without "_VERSION" suffix.
        // This matches Nukkit-MOT's behavior and is required for NetEase clients which use
        // the DataInfo pack ID as their cache key.
        packet.setMaxChunkSize(CHUNK_SIZE);
        packet.setChunkCount((resourcePack.getPackSize() - 1) / packet.getMaxChunkSize() + 1);
        packet.setCompressedPackSize(resourcePack.getPackSize());
        packet.setHash(resourcePack.getHash());
        // IMPORTANT: The Protocol library's TypeMap has different ID assignments than the
        // real Bedrock protocol. Real protocol: Resources=6, Behavior=4, Addon=1.
        // Protocol library TypeMap: RESOURCES→1, ADDON→4, CACHED→6.
        // To match Nukkit-MOT (which uses the real protocol IDs), we use CACHED(→6)
        // for resource packs and ADDON(→4) for behavior packs.
        if (resourcePack.getType().equals(ResourcePack.TYPE_RESOURCES)) {
            packet.setType(ResourcePackType.CACHED); // wire value 6 = real Resources
        } else if (resourcePack.getType().equals(ResourcePack.TYPE_DATA)) {
            packet.setType(ResourcePackType.ADDON);  // wire value 4 = real Behavior
        }
        return packet;
    }

    public ResourcePackChunkDataPacket packChunkDataPacket(String idVersion, ResourcePackChunkRequestPacket from) {
        ResourcePack resourcePack = this.lookupPack(idVersion);
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

    public ResourcePacksInfoPacket getPacksInfoPacket() {
        return this.packsInfoPacket;
    }

    public ResourcePackStackPacket getStackPacket() {
        return this.stackPacket;
    }

    public Map<UUID, ResourcePack> getPacks() {
        return this.packs;
    }

    public Map<String, ResourcePack> getPacksByIdVer() {
        return this.packsByIdVer;
    }

    /**
     * Returns a deep copy of the packs info packet.
     * Use this instead of getPacksInfoPacket() when the packet will be modified (e.g., by NetEasePackFilter).
     */
    public ResourcePacksInfoPacket copyPacksInfoPacket() {
        ResourcePacksInfoPacket copy = new ResourcePacksInfoPacket();
        copy.setForcedToAccept(this.packsInfoPacket.isForcedToAccept());
        copy.setHasAddonPacks(this.packsInfoPacket.isHasAddonPacks());
        copy.setScriptingEnabled(this.packsInfoPacket.isScriptingEnabled());
        copy.setForcingServerPacksEnabled(this.packsInfoPacket.isForcingServerPacksEnabled());
        copy.setWorldTemplateId(this.packsInfoPacket.getWorldTemplateId());
        copy.setWorldTemplateVersion(this.packsInfoPacket.getWorldTemplateVersion());
        copy.getResourcePackInfos().addAll(this.packsInfoPacket.getResourcePackInfos());
        copy.getBehaviorPackInfos().addAll(this.packsInfoPacket.getBehaviorPackInfos());
        return copy;
    }

    /**
     * Returns a deep copy of the stack packet.
     * Use this instead of getStackPacket() when the packet will be modified (e.g., by NetEasePackFilter).
     */
    public ResourcePackStackPacket copyStackPacket() {
        ResourcePackStackPacket copy = new ResourcePackStackPacket();
        copy.setForcedToAccept(this.stackPacket.isForcedToAccept());
        copy.setGameVersion(this.stackPacket.getGameVersion());
        copy.setExperimentsPreviouslyToggled(this.stackPacket.isExperimentsPreviouslyToggled());
        copy.setHasEditorPacks(this.stackPacket.isHasEditorPacks());
        copy.getResourcePacks().addAll(this.stackPacket.getResourcePacks());
        copy.getBehaviorPacks().addAll(this.stackPacket.getBehaviorPacks());
        copy.getExperiments().addAll(this.stackPacket.getExperiments());
        return copy;
    }
}
