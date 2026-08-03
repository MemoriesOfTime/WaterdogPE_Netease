/*
 * Copyright 2026 WaterdogTEAM
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

package dev.waterdog.waterdogpe.utils;

import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.logger.MainLogger;
import dev.waterdog.waterdogpe.utils.config.LangConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link ConfigurationManager#loadLanguage()} backfills translation keys present in
 * the bundled jar resource but missing from the on-disk file, while preserving existing entries.
 *
 * <p>This is what makes new commands' lang keys visible after an upgrade without requiring the
 * operator to delete {@code lang.ini}.</p>
 */
public class LanguageBackfillTest {

    @TempDir
    Path dataDir;

    private ProxyServer proxy;
    private ConfigurationManager manager;

    @BeforeEach
    void setUp() {
        this.proxy = mock(ProxyServer.class);
        when(this.proxy.getDataPath()).thenReturn(this.dataDir);
        when(this.proxy.getLogger()).thenReturn(mock(MainLogger.class));
        this.manager = new ConfigurationManager(this.proxy);
    }

    @Test
    void missingKeysAreAppendedFromBundledResource() throws Exception {
        // Disk file predates the reloadplugin command: it has an old key plus a user override,
        // but none of the new reloadplugin.* keys.
        Path langFile = this.dataDir.resolve("lang.ini");
        String oldContent = "waterdog.command.reload.description=OLD custom desc\n"
                + "waterdog.command.list.usage=wdlist <server:optional>\n";
        Files.writeString(langFile, oldContent);

        this.manager.loadLanguage();

        String after = Files.readString(langFile);
        assertTrue(after.contains("waterdog.command.reloadplugin.list"),
                "new bundled key must be appended to the disk file");
        assertTrue(after.contains("waterdog.command.reloadplugin.usage"),
                "all new reloadplugin keys must be backfilled");
        // User's override on the old key must be preserved (not overwritten by the bundled value)
        assertTrue(after.contains("waterdog.command.reload.description=OLD custom desc"),
                "existing operator customization must be preserved verbatim");
    }

    @Test
    void backfilledKeysAreActuallyTranslatable() throws Exception {
        // Start from an empty-ish file with only one unrelated key
        Path langFile = this.dataDir.resolve("lang.ini");
        Files.writeString(langFile, "waterdog.command.info.usage=wdinfo\n");

        this.manager.loadLanguage();
        LangConfig lang = new LangConfig(langFile.toFile());

        // A key that only existed in the jar resource must now resolve from the backfilled disk file
        String translated = lang.translateString("waterdog.command.reloadplugin.list", "DemoPlugin");
        assertNotNull(translated);
        // translateString returns the raw key when the value is missing; the backfilled value is
        // "§eLoaded plugins: §b{%0}" so the result must NOT equal the key and must contain the param.
        assertFalse(translated.equals("waterdog.command.reloadplugin.list"),
                "backfilled key must resolve to its value, not the raw key: " + translated);
        assertTrue(translated.contains("DemoPlugin"),
                "param substitution must work on the backfilled value: " + translated);
    }

    @Test
    void noBackfillWhenFileAlreadyComplete() throws Exception {
        // Seed the disk file from the bundled resource so nothing is missing
        Path langFile = this.dataDir.resolve("lang.ini");
        try (var in = ConfigurationManager.class.getClassLoader().getResourceAsStream("lang.ini")) {
            assertNotNull(in);
            Files.copy(in, langFile);
        }
        long sizeBefore = Files.size(langFile);

        this.manager.loadLanguage();

        // File should not gain the "# appended by the proxy" section since nothing was missing
        String after = Files.readString(langFile);
        assertFalse(after.contains("# ---- appended by the proxy on startup ----"),
                "no appendage should be written when the file is already complete");
        assertEquals(sizeBefore, Files.size(langFile),
                "file size must be unchanged when nothing was backfilled");
    }

    @Test
    void missingFileIsCreatedFromResourceFirst() throws Exception {
        // No disk file at all: the original saveFromResources path creates it, then backfill
        // finds nothing missing (file == resource), so no appendage.
        Path langFile = this.dataDir.resolve("lang.ini");
        assertTrue(!Files.exists(langFile));

        this.manager.loadLanguage();

        assertTrue(Files.exists(langFile), "lang.ini must be created when absent");
        LangConfig lang = new LangConfig(langFile.toFile());
        assertNotNull(lang.getTransaction("waterdog.command.reloadplugin.list"),
                "newly created file must contain all bundled keys including reloadplugin");
    }
}
