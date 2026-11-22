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

package dev.waterdog.waterdogpe.network.serverinfo;

import dev.waterdog.waterdogpe.network.connection.client.ClientConnection;
import dev.waterdog.waterdogpe.player.ProxiedPlayer;
import dev.waterdog.waterdogpe.utils.config.ServerEntry;
import io.netty.util.concurrent.Future;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A downstream commonly puts the IPv4 literal of the target into a TransferPacket, while the
 * proxy's public_address stays a hostname that config loading keeps unresolved (#376). Matching
 * therefore has to compare against the resolved address too, without doing DNS on the packet path.
 */
public class ServerInfoMatchAddressTest {

    private static final int PORT = 19132;

    private static final class TestServerInfo extends ServerInfo {

        TestServerInfo(InetSocketAddress address, InetSocketAddress publicAddress) {
            super("test", address, publicAddress);
        }

        @Override
        public ServerInfoType getServerType() {
            return ServerInfoType.RAKNET;
        }

        @Override
        public Future<ClientConnection> createConnection(ProxiedPlayer player) {
            throw new UnsupportedOperationException();
        }
    }

    private static TestServerInfo server(String publicHost) {
        return new TestServerInfo(
                InetSocketAddress.createUnresolved("127.0.0.1", PORT),
                InetSocketAddress.createUnresolved(publicHost, PORT));
    }

    private static ServerEntry localhostEntry() {
        return new ServerEntry("test",
                InetSocketAddress.createUnresolved("127.0.0.1", PORT),
                InetSocketAddress.createUnresolved("localhost", PORT),
                ServerInfoType.RAKNET.getIdentifier());
    }

    @Test
    void unresolvedHostnameMatchesItsLiteral() {
        TestServerInfo info = server("localhost");
        assertTrue(info.matchAddress("localhost", PORT));
    }

    @Test
    void unresolvedHostnameMatchesResolvedIp() throws UnknownHostException {
        TestServerInfo info = server("localhost");
        for (InetAddress address : InetAddress.getAllByName("localhost")) {
            assertTrue(info.matchAddress(address.getHostAddress(), PORT), address.getHostAddress());
        }
    }

    @Test
    void loadingResolvesPublicAddressBeforeReturning() throws UnknownHostException {
        ServerInfo info = new ServerInfoMap().fromServerEntry(localhostEntry());
        for (InetAddress address : InetAddress.getAllByName("localhost")) {
            assertTrue(info.matchAddress(address.getHostAddress(), PORT), address.getHostAddress());
        }
    }

    @Test
    void asyncLoadingResolvesPublicAddressBeforeCompleting() throws Exception {
        ServerInfo info = new ServerInfoMap().fromServerEntryAsync(localhostEntry()).get(5, TimeUnit.SECONDS);
        for (InetAddress address : InetAddress.getAllByName("localhost")) {
            assertTrue(info.matchAddress(address.getHostAddress(), PORT), address.getHostAddress());
        }
    }

    @Test
    void asyncLoadingConstructsTheServerOffTheCallingThread() throws Exception {
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> factoryThread = new AtomicReference<>();
        ServerInfoMap map = new ServerInfoMap() {
            @Override
            public ServerInfo createServerInfo(String serverName, InetSocketAddress address,
                                               InetSocketAddress publicAddress, ServerInfoType serverType) {
                factoryThread.set(Thread.currentThread());
                return super.createServerInfo(serverName, address, publicAddress, serverType);
            }
        };

        // The constructor resolves the connect address, so it must not run on the reload caller either.
        map.fromServerEntryAsync(localhostEntry()).get(5, TimeUnit.SECONDS);

        assertNotSame(caller, factoryThread.get());
    }

    @Test
    void hostnameMatchIgnoresCaseAndTrailingDot() {
        TestServerInfo info = server("localhost");
        assertTrue(info.matchAddress("LocalHost", PORT));
        assertTrue(info.matchAddress("localhost.", PORT));
    }

    @Test
    void resolvedPublicAddressMatchesItsIp() {
        TestServerInfo info = new TestServerInfo(
                InetSocketAddress.createUnresolved("127.0.0.1", PORT),
                new InetSocketAddress("localhost", PORT));
        String ip = info.getPublicAddress().getAddress().getHostAddress();
        assertTrue(info.matchAddress(ip, PORT), ip);
    }

    @Test
    void publicAddressBuiltFromUnnamedInetAddressMatchesByIpOnly() throws UnknownHostException {
        // getByAddress carries no hostname, and recovering one would take a PTR lookup, which the
        // packet path deliberately does not do.
        TestServerInfo info = new TestServerInfo(
                InetSocketAddress.createUnresolved("127.0.0.1", PORT),
                new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), PORT));
        assertTrue(info.matchAddress("127.0.0.1", PORT));
        assertFalse(info.matchAddress("localhost", PORT));
    }

    @Test
    void ipLiteralPublicAddressMatches() {
        TestServerInfo info = server("192.0.2.7");
        assertTrue(info.matchAddress("192.0.2.7", PORT));
        assertFalse(info.matchAddress("192.0.2.8", PORT));
    }

    @Test
    void ipv6LiteralMatchesRegardlessOfTextForm() {
        TestServerInfo info = server("0:0:0:0:0:0:0:1");
        assertTrue(info.matchAddress("::1", PORT));
        assertTrue(info.matchAddress("[::1]", PORT));
    }

    @Test
    void ipv6ZoneIdsArePreserved() {
        TestServerInfo info = server("fe80::1%en0");
        assertTrue(info.matchAddress("[FE80:0:0:0:0:0:0:1%en0]", PORT));
        assertFalse(info.matchAddress("fe80::1%en1", PORT));
        assertFalse(info.matchAddress("fe80::1", PORT));
    }

    @Test
    void unresolvablePublicAddressStillMatchesItsLiteral() {
        // A bracketed non-IPv6 literal makes InetAddress.getAllByName fail locally, so this exercises the
        // resolution failure path without depending on DNS.
        TestServerInfo info = server("[not-ipv6]");
        assertTrue(info.matchAddress("[not-ipv6]", PORT));
    }

    @Test
    void portMustMatch() {
        TestServerInfo info = server("localhost");
        assertFalse(info.matchAddress("localhost", PORT + 1));
    }

    @Test
    void nullAddressDoesNotMatch() {
        TestServerInfo info = server("localhost");
        assertFalse(info.matchAddress(null, PORT));
    }

    @Test
    void unrelatedHostDoesNotMatch() {
        TestServerInfo info = server("localhost");
        assertFalse(info.matchAddress("192.0.2.7", PORT));
    }
}
