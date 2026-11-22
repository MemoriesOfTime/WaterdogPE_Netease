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

package dev.waterdog.waterdogpe.command.defaults;

import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.command.CommandSender;
import dev.waterdog.waterdogpe.logger.MainLogger;
import dev.waterdog.waterdogpe.network.serverinfo.ServerInfoMap;
import dev.waterdog.waterdogpe.utils.ConfigurationManager;
import dev.waterdog.waterdogpe.utils.types.TextContainer;
import dev.waterdog.waterdogpe.utils.types.TranslationContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class ReloadCommandTest {

    private Field proxyInstance;
    private ProxyServer previousProxy;
    private CommandSender sender;
    private ConfigurationManager.ReloadResult result;
    private CompletableFuture<Void> ready;

    @BeforeEach
    void setUp() throws Exception {
        this.proxyInstance = ProxyServer.class.getDeclaredField("instance");
        this.proxyInstance.setAccessible(true);
        this.previousProxy = (ProxyServer) this.proxyInstance.get(null);
        ProxyServer proxy = mock(ProxyServer.class);
        this.proxyInstance.set(null, proxy);

        this.sender = mock(CommandSender.class);
        when(this.sender.getProxy()).thenReturn(proxy);
        this.result = mock(ConfigurationManager.ReloadResult.class);
        this.ready = new CompletableFuture<>();
        ConfigurationManager manager = mock(ConfigurationManager.class);
        ServerInfoMap serverInfoMap = new ServerInfoMap();
        when(proxy.getConfigurationManager()).thenReturn(manager);
        when(proxy.getServerInfoMap()).thenReturn(serverInfoMap);
        when(proxy.getLogger()).thenReturn(mock(MainLogger.class));
        when(manager.reloadServerInfos(serverInfoMap)).thenReturn(this.result);
        when(this.result.getReady()).thenReturn(this.ready);
        when(this.result.getPendingUpdates()).thenReturn(List.of());
        when(this.result.getPendingRemovals()).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() throws Exception {
        this.proxyInstance.set(null, this.previousProxy);
    }

    @Test
    void successIsReportedOnlyAfterReloadIsReady() {
        new ReloadCommand().onExecute(this.sender, "wdreload", new String[0]);
        verifyNoInteractions(this.sender);

        this.ready.complete(null);

        ArgumentCaptor<TextContainer> message = ArgumentCaptor.forClass(TextContainer.class);
        verify(this.sender).sendMessage(message.capture());
        assertEquals("waterdog.command.reload.success", message.getValue().getMessage());
    }

    @Test
    void asynchronousFailureIsReportedAsReloadFailure() {
        new ReloadCommand().onExecute(this.sender, "wdreload", new String[0]);
        verifyNoInteractions(this.sender);

        this.ready.completeExceptionally(new IllegalStateException("DNS failed"));

        ArgumentCaptor<TextContainer> message = ArgumentCaptor.forClass(TextContainer.class);
        verify(this.sender).sendMessage(message.capture());
        assertEquals("waterdog.command.reload.failed", message.getValue().getMessage());
        assertArrayEquals(new String[]{"DNS failed"}, ((TranslationContainer) message.getValue()).getParams());
    }

    @Test
    void failureWrappedByAllOfIsUnwrapped() {
        new ReloadCommand().onExecute(this.sender, "wdreload", new String[0]);

        // CompletableFuture.allOf wraps the first failure in a CompletionException, matching the real path.
        this.ready.completeExceptionally(new CompletionException(new IllegalStateException("DNS failed")));

        ArgumentCaptor<TextContainer> message = ArgumentCaptor.forClass(TextContainer.class);
        verify(this.sender).sendMessage(message.capture());
        assertEquals("waterdog.command.reload.failed", message.getValue().getMessage());
        assertArrayEquals(new String[]{"DNS failed"}, ((TranslationContainer) message.getValue()).getParams());
    }

    @Test
    void supersededReloadIsNotReportedAsFailure() {
        new ReloadCommand().onExecute(this.sender, "wdreload", new String[0]);

        // The generation check in ConfigurationManager fails a stale publication this way.
        this.ready.completeExceptionally(new CancellationException("superseded"));

        verifyNoInteractions(this.sender);
    }
}
