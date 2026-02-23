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

package dev.waterdog.waterdogpe.packs.types;

import lombok.Getter;
import lombok.Setter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Getter
public abstract class ResourcePack {

    public static final String TYPE_RESOURCES = "resources";
    public static final String TYPE_DATA = "data";

    protected final Path packPath;
    protected PackManifest packManifest;
    @Setter
    protected String contentKey;
    protected SupportType supportType = SupportType.UNIVERSAL;

    public ResourcePack(Path packPath) {
        this.packPath = packPath;
    }

    /**
     * Defines which Minecraft editions a resource pack supports
     */
    public enum SupportType {
        /**
         * Resource pack only supports Microsoft (International) version
         */
        MICROSOFT,

        /**
         * Resource pack only supports NetEase (Chinese) version
         */
        NETEASE,

        /**
         * Resource pack supports all versions (both Microsoft and NetEase)
         */
        UNIVERSAL;

        /**
         * Check if this support type is compatible with the given client type
         *
         * @param isNetEaseClient whether the client is NetEase version
         * @return true if compatible, false otherwise
         */
        public boolean isCompatibleWith(boolean isNetEaseClient) {
            if (this == UNIVERSAL) {
                return true;
            }
            return isNetEaseClient ? this == NETEASE : this == MICROSOFT;
        }
    }

    public abstract long getPackSize();

    public abstract byte[] getHash();

    public abstract byte[] getChunk(int off, int len);

    public abstract void saveToCache() throws IOException;

    public abstract ByteBuffer getCachedPack();

    public abstract InputStream getStream(Path path) throws IOException;

    public boolean loadManifest() throws IOException {
        // Try standard manifest.json first
        try (InputStream stream = this.getStream(PackManifest.MANIFEST_PATH)) {
            if (stream != null) {
                this.packManifest = PackManifest.fromStream(stream);
                return true;
            }
        }
        
        // Try NetEase pack_manifest.json
        try (InputStream stream = this.getStream(Paths.get("pack_manifest.json"))) {
            if (stream != null) {
                this.packManifest = PackManifest.fromStream(stream);
                return true;
            }
        }
        
        return false;
    }

    public String getPackName() {
        return this.packManifest.getHeader().getName();
    }

    public UUID getPackId() {
        return this.packManifest.getHeader().getUuid();
    }

    public PackedVersion getVersion() {
        return this.packManifest.getHeader().getVersion();
    }

    public String getType() {
        return this.packManifest.getModules().get(0).getType();
    }

    public Path getPackPath() {
        return this.packPath;
    }

    public String getContentKey() {
        return contentKey;
    }

    public void setContentKey(String contentKey) {
        this.contentKey = contentKey;
    }

    public SupportType getSupportType() {
        return this.supportType;
    }

    public void setSupportType(SupportType supportType) {
        this.supportType = supportType;
    }
}
