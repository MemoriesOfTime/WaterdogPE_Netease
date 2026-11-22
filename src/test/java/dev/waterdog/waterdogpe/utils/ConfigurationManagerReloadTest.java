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
import dev.waterdog.waterdogpe.event.EventManager;
import dev.waterdog.waterdogpe.logger.MainLogger;
import dev.waterdog.waterdogpe.network.connection.handler.IReconnectHandler;
import dev.waterdog.waterdogpe.network.serverinfo.ServerInfo;
import dev.waterdog.waterdogpe.network.serverinfo.ServerInfoMap;
import dev.waterdog.waterdogpe.player.ProxiedPlayer;
import dev.waterdog.waterdogpe.utils.config.ServerEntry;
import dev.waterdog.waterdogpe.utils.config.ServerList;
import dev.waterdog.waterdogpe.utils.config.proxy.ProxyConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class ConfigurationManagerReloadTest {

    private static final String SERVER_NAME = "lobby";
    private static final int PORT = 19132;

    private ProxyServer proxy;
    private ProxyConfig proxyConfig;
    private ServerList serverList;
    private ServerInfoMap serverInfoMap;
    private ConfigurationManager manager;

    @BeforeEach
    void setUp() throws Exception {
        this.proxy = mock(ProxyServer.class);
        this.proxyConfig = mock(ProxyConfig.class);
        this.serverList = new ServerList();
        this.serverInfoMap = spy(new ServerInfoMap());
        this.manager = new ConfigurationManager(this.proxy);

        Field field = ConfigurationManager.class.getDeclaredField("proxyConfig");
        field.setAccessible(true);
        field.set(this.manager, this.proxyConfig);
        when(this.proxyConfig.getServerList()).thenReturn(this.serverList);
        when(this.proxy.getReconnectHandler()).thenReturn(mock(IReconnectHandler.class));
        when(this.proxy.getServerInfoMap()).thenReturn(this.serverInfoMap);
        when(this.proxy.getEventManager()).thenReturn(mock(EventManager.class));
        when(this.proxy.getLogger()).thenReturn(mock(MainLogger.class));
    }

    @Test
    void reloadKeepsCurrentServerUntilReplacementIsReady() throws Exception {
        ServerInfo current = server("127.0.0.1");
        this.serverInfoMap.put(SERVER_NAME, current);
        ServerEntry updatedEntry = entry("127.0.0.2");
        this.serverList.put(SERVER_NAME, updatedEntry);
        ServerInfo replacement = mock(ServerInfo.class);
        CompletableFuture<ServerInfo> ready = new CompletableFuture<>();
        doReturn(ready).when(this.serverInfoMap).fromServerEntryAsync(updatedEntry);

        ConfigurationManager.ReloadResult result = this.manager.reloadServerInfos(this.serverInfoMap);
        assertEquals(1, result.getUpdated());
        assertFalse(result.getReady().isDone());
        assertSame(current, this.serverInfoMap.get(SERVER_NAME));

        ready.complete(replacement);
        result.getReady().join();
        assertEquals(1, result.getUpdated());
        assertSame(replacement, this.serverInfoMap.get(SERVER_NAME));
    }

    @Test
    void newerReloadInvalidatesAnOlderPublication() throws Exception {
        ServerEntry firstEntry = entry("192.0.2.1");
        ServerEntry secondEntry = entry("192.0.2.2");
        this.serverList.put(SERVER_NAME, firstEntry);
        CompletableFuture<ServerInfo> firstReady = new CompletableFuture<>();
        CompletableFuture<ServerInfo> secondReady = new CompletableFuture<>();
        doReturn(firstReady, secondReady).when(this.serverInfoMap).fromServerEntryAsync(any(ServerEntry.class));

        ConfigurationManager.ReloadResult firstResult = this.manager.reloadServerInfos(this.serverInfoMap);
        this.serverList.put(SERVER_NAME, secondEntry);
        ConfigurationManager.ReloadResult secondResult = this.manager.reloadServerInfos(this.serverInfoMap);

        assertFalse(firstResult.getReady().isDone());
        firstReady.complete(mock(ServerInfo.class));
        // The stale publication aborts on the generation check instead of publishing.
        assertTrue(firstResult.getReady().isCompletedExceptionally());
        assertNull(this.serverInfoMap.get(SERVER_NAME));
        ServerInfo second = mock(ServerInfo.class);
        secondReady.complete(second);
        secondResult.getReady().join();
        assertSame(second, this.serverInfoMap.get(SERVER_NAME));
    }

    @Test
    void replacementDefersIfPlayersJoinWhileResolving() throws Exception {
        ServerInfo current = server("127.0.0.1");
        Set<ProxiedPlayer> players = Set.of(mock(ProxiedPlayer.class));
        when(current.getPlayers()).thenReturn(Set.of(), players);
        this.serverInfoMap.put(SERVER_NAME, current);
        ServerEntry updatedEntry = entry("127.0.0.2");
        this.serverList.put(SERVER_NAME, updatedEntry);
        CompletableFuture<ServerInfo> ready = new CompletableFuture<>();
        doReturn(ready).when(this.serverInfoMap).fromServerEntryAsync(updatedEntry);

        ConfigurationManager.ReloadResult result = this.manager.reloadServerInfos(this.serverInfoMap);
        ready.complete(mock(ServerInfo.class));
        result.getReady().join();

        assertSame(current, this.serverInfoMap.get(SERVER_NAME));
        assertEquals(0, result.getUpdated());
        assertTrue(result.getPendingUpdates().contains(SERVER_NAME));
        assertTrue(this.manager.getPendingActions().containsKey(SERVER_NAME));
        assertEquals(updatedEntry, this.manager.getPendingActions().get(SERVER_NAME).getNewEntry());
    }

    @Test
    void deferredUpdateIsPublishedOnlyAfterResolution() throws Exception {
        ServerInfo current = server("127.0.0.1");
        Set<ProxiedPlayer> online = new HashSet<>();
        online.add(mock(ProxiedPlayer.class));
        when(current.getPlayers()).thenAnswer(invocation -> online);
        this.serverInfoMap.put(SERVER_NAME, current);
        ServerEntry updatedEntry = entry("127.0.0.2");
        this.serverList.put(SERVER_NAME, updatedEntry);

        ConfigurationManager.ReloadResult result = this.manager.reloadServerInfos(this.serverInfoMap);
        assertEquals(List.of(SERVER_NAME), result.getPendingUpdates());
        assertTrue(this.manager.getPendingActions().containsKey(SERVER_NAME));

        ServerInfo replacement = mock(ServerInfo.class);
        CompletableFuture<ServerInfo> ready = new CompletableFuture<>();
        doReturn(ready).when(this.serverInfoMap).fromServerEntryAsync(updatedEntry);

        online.clear(); // the last player leaves and the deferred update is applied
        Method applyPendingAction = ConfigurationManager.class.getDeclaredMethod("applyPendingAction", String.class);
        applyPendingAction.setAccessible(true);
        applyPendingAction.invoke(this.manager, SERVER_NAME);

        assertSame(current, this.serverInfoMap.get(SERVER_NAME));
        ready.complete(replacement);
        assertSame(replacement, this.serverInfoMap.get(SERVER_NAME));
    }

    @Test
    void reloadReadyFailsWhenResolutionFails() throws Exception {
        ServerEntry entry = entry("192.0.2.1");
        this.serverList.put(SERVER_NAME, entry);
        CompletableFuture<ServerInfo> ready = new CompletableFuture<>();
        doReturn(ready).when(this.serverInfoMap).fromServerEntryAsync(entry);

        ConfigurationManager.ReloadResult result = this.manager.reloadServerInfos(this.serverInfoMap);
        assertFalse(result.getReady().isDone());
        ready.completeExceptionally(new IllegalStateException("DNS failed"));

        assertTrue(result.getReady().isCompletedExceptionally());
        assertNull(this.serverInfoMap.get(SERVER_NAME));
    }

    private static ServerEntry entry(String host) {
        InetSocketAddress address = InetSocketAddress.createUnresolved(host, PORT);
        return new ServerEntry(SERVER_NAME, address, address, ServerInfoMap.DEFAULT_TYPE.getIdentifier());
    }

    private static ServerInfo server(String host) {
        ServerInfo server = mock(ServerInfo.class);
        InetSocketAddress address = InetSocketAddress.createUnresolved(host, PORT);
        when(server.getAddress()).thenReturn(address);
        when(server.getPublicAddress()).thenReturn(address);
        when(server.getPlayers()).thenReturn(Set.of());
        return server;
    }
}
