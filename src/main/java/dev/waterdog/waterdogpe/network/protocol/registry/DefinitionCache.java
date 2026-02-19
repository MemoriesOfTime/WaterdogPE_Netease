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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Handles persistence of definition snapshots to/from a JSON cache file.
 * Used to pre-populate the DefinitionAggregator on proxy startup so that
 * players don't need to discover definitions lazily.
 */
@Log4j2
public class DefinitionCache {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type CACHE_TYPE = new TypeToken<Map<String, CachedServerSnapshot>>() {}.getType();

    private final Path cacheFile;

    public DefinitionCache(Path dataPath) {
        this.cacheFile = dataPath.resolve("definition_cache.json");
    }

    /**
     * Load cached server definitions from disk.
     * Returns a map of server name -> (items, blockProperties).
     */
    public Map<String, LoadedSnapshot> load() {
        if (!Files.exists(this.cacheFile)) {
            return Collections.emptyMap();
        }

        try (Reader reader = Files.newBufferedReader(this.cacheFile, StandardCharsets.UTF_8)) {
            Map<String, CachedServerSnapshot> cached = GSON.fromJson(reader, CACHE_TYPE);
            if (cached == null) {
                return Collections.emptyMap();
            }

            Map<String, LoadedSnapshot> result = new LinkedHashMap<>();
            for (Map.Entry<String, CachedServerSnapshot> entry : cached.entrySet()) {
                try {
                    LoadedSnapshot snapshot = deserializeSnapshot(entry.getValue());
                    result.put(entry.getKey(), snapshot);
                } catch (Exception e) {
                    log.warn("Failed to deserialize cached definitions for server {}, skipping", entry.getKey(), e);
                }
            }

            log.info("Loaded definition cache: {} servers from {}", result.size(), this.cacheFile);
            return result;
        } catch (Exception e) {
            log.warn("Failed to load definition cache from {}", this.cacheFile, e);
            return Collections.emptyMap();
        }
    }

    /**
     * Save server definition snapshots to disk.
     * Accepts the per-server snapshots from the aggregator.
     */
    public void save(Map<String, DefinitionAggregator.ServerSnapshot> snapshots) {
        Map<String, CachedServerSnapshot> cached = new LinkedHashMap<>();
        for (Map.Entry<String, DefinitionAggregator.ServerSnapshot> entry : snapshots.entrySet()) {
            try {
                cached.put(entry.getKey(), serializeSnapshot(entry.getValue()));
            } catch (Exception e) {
                log.warn("Failed to serialize definitions for server {}, skipping", entry.getKey(), e);
            }
        }

        try {
            Files.createDirectories(this.cacheFile.getParent());
            try (Writer writer = Files.newBufferedWriter(this.cacheFile, StandardCharsets.UTF_8)) {
                GSON.toJson(cached, CACHE_TYPE, writer);
            }
            log.debug("Saved definition cache: {} servers to {}", cached.size(), this.cacheFile);
        } catch (IOException e) {
            log.warn("Failed to save definition cache to {}", this.cacheFile, e);
        }
    }

    private static CachedServerSnapshot serializeSnapshot(DefinitionAggregator.ServerSnapshot snapshot) {
        CachedServerSnapshot cached = new CachedServerSnapshot();
        cached.items = new ArrayList<>();
        cached.blockProperties = new ArrayList<>();

        for (ItemDefinition def : snapshot.getItemDefinitions()) {
            CachedItemDef item = new CachedItemDef();
            item.identifier = def.getIdentifier();
            item.runtimeId = def.getRuntimeId();
            item.componentBased = def.isComponentBased();
            cached.items.add(item);
        }

        for (BlockPropertyData bp : snapshot.getBlockProperties()) {
            CachedBlockProperty block = new CachedBlockProperty();
            block.name = bp.getName();
            block.nbt = nbtToBase64(bp.getProperties());
            cached.blockProperties.add(block);
        }
        return cached;
    }

    private static LoadedSnapshot deserializeSnapshot(CachedServerSnapshot cached) {
        List<ItemDefinition> items = new ArrayList<>();
        if (cached.items != null) {
            for (CachedItemDef item : cached.items) {
                items.add(new SimpleItemDefinition(item.identifier, item.runtimeId, item.componentBased));
            }
        }

        List<BlockPropertyData> blocks = new ArrayList<>();
        if (cached.blockProperties != null) {
            for (CachedBlockProperty block : cached.blockProperties) {
                NbtMap nbt = base64ToNbt(block.nbt);
                if (nbt != null) {
                    blocks.add(new BlockPropertyData(block.name, nbt));
                }
            }
        }

        return new LoadedSnapshot(items, blocks);
    }

    private static String nbtToBase64(NbtMap nbtMap) {
        if (nbtMap == null) {
            return null;
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             NBTOutputStream nbtOut = NbtUtils.createWriterLE(baos)) {
            nbtOut.writeTag(nbtMap);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) {
            log.warn("Failed to serialize NbtMap", e);
            return null;
        }
    }

    private static NbtMap base64ToNbt(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return NbtMap.EMPTY;
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(Base64.getDecoder().decode(base64));
             NBTInputStream nbtIn = NbtUtils.createReaderLE(bais)) {
            return (NbtMap) nbtIn.readTag();
        } catch (IOException e) {
            log.warn("Failed to deserialize NbtMap from base64", e);
            return NbtMap.EMPTY;
        }
    }

    /**
     * Loaded snapshot ready for use with DefinitionAggregator.registerServer().
     */
    public record LoadedSnapshot(List<ItemDefinition> items, List<BlockPropertyData> blockProperties) {
    }

    // JSON data model
    private static class CachedServerSnapshot {
        List<CachedItemDef> items;
        List<CachedBlockProperty> blockProperties;
    }

    private static class CachedItemDef {
        String identifier;
        int runtimeId;
        boolean componentBased;
    }

    private static class CachedBlockProperty {
        String name;
        String nbt;
    }
}
