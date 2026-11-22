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

package dev.waterdog.waterdogpe.network.serverinfo;

import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.network.connection.client.ClientConnection;
import dev.waterdog.waterdogpe.player.ProxiedPlayer;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.Future;
import lombok.Getter;
import lombok.ToString;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base informative class for servers.
 * Every server registered to the Proxy has one instance of this class, holding its name, and it's address (ip&port)
 * Also holds a list of all ProxiedPlayers connected.
 */
@ToString(exclude = {"players", "publicAddressAliases"})
public abstract class ServerInfo {

    /**
     * How long a resolved downstream address is reused before a background re-resolve is triggered.
     * A miss only schedules an async refresh; callers always get the last-known-good address immediately.
     */
    private static final long DNS_CACHE_TTL_MS = TimeUnit.SECONDS.toMillis(30);

    /**
     * Single daemon thread that performs the (blocking) JDK name resolution OFF the Netty event loops.
     * Historically {@code connect()} was handed an unresolved InetSocketAddress, so Netty resolved it
     * inline on a worker event loop via blocking InetAddress.getByName, a slow/hung DNS lookup there
     * froze every player sharing that loop. Connect-address refreshes now happen on this thread instead,
     * and reloads construct their ServerInfo instances here so their lookups stay off the caller.
     */
    static final ExecutorService DNS_RESOLVER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ServerInfo DNS Resolver");
        thread.setDaemon(true);
        return thread;
    });

    @Getter
    private final String serverName;
    @Getter
    private final InetSocketAddress address;
    @Getter
    private final InetSocketAddress publicAddress;

    // Last successfully resolved connect target. Never null: seeded with the configured address so behavior
    // never regresses below the old "resolve inline" path even if DNS is down at startup.
    private volatile InetSocketAddress resolvedAddress;
    private volatile long resolvedAt;
    private final AtomicBoolean resolving = new AtomicBoolean(false);

    // Hosts this server answers to in a TransferPacket: the configured host plus the IP literals it
    // resolved to. Config loading keeps hostnames unresolved while downstreams commonly send the IP
    // literal, so both forms have to match. Resolved once here, where construction runs on the startup
    // or reload-resolver thread, so matchAddress() never has to perform DNS itself.
    private final Set<String> publicAddressAliases;

    private final Set<ClientConnection> connections = ConcurrentHashMap.newKeySet();
    private final Set<ProxiedPlayer> players = ConcurrentHashMap.newKeySet();

    public ServerInfo(String serverName, InetSocketAddress address, InetSocketAddress publicAddress) {
        this.serverName = serverName;
        this.address = address;
        if (publicAddress == null) {
            publicAddress = address;
        }
        this.publicAddress = publicAddress;

        // Seed the cache. Done synchronously here (constructed at startup/config-load, never on an event loop)
        // so the very first connect already has a resolved address and Netty never resolves inline.
        this.resolvedAddress = address;
        this.refreshResolvedAddress();
        // public_address only has to be resolvable by clients, and resolving it here keeps the aliases ready
        // before the instance is published; matchAddress() then never performs DNS on the packet path.
        this.publicAddressAliases = this.resolvePublicAddressAliases();
    }

    /**
     * Returns a resolved (non-blocking to use) connect target for this server. Always returns immediately
     * with the last-known-good address; if the cache is older than {@link #DNS_CACHE_TTL_MS} a re-resolve is
     * scheduled on the {@link #DNS_RESOLVER} thread so the event loop never performs DNS itself.
     */
    public InetSocketAddress getResolvedAddress() {
        if (System.currentTimeMillis() - this.resolvedAt > DNS_CACHE_TTL_MS && this.resolving.compareAndSet(false, true)) {
            DNS_RESOLVER.execute(() -> {
                try {
                    this.refreshResolvedAddress();
                } finally {
                    this.resolving.set(false);
                }
            });
        }
        return this.resolvedAddress;
    }

    protected void refreshResolvedAddress() {
        InetSocketAddress configured = this.address;
        try {
            // getByName on an IP literal is purely local (no network), so IP-configured servers resolve cheaply
            // and still short-circuit Netty's resolver because the result is a resolved InetSocketAddress.
            InetAddress resolved = InetAddress.getByName(configured.getHostString());
            // Keeps the configured name on the address: HTTPS signaling validates the certificate against it
            this.resolvedAddress = new InetSocketAddress(
                    InetAddress.getByAddress(configured.getHostString(), resolved.getAddress()), configured.getPort());
        } catch (UnknownHostException e) {
            // Keep the last-known-good (or the raw configured) address; a transient DNS failure must not break
            // connects. The next access past the TTL will retry.
            if (ProxyServer.getInstance() != null) {
                ProxyServer.getInstance().getLogger().debug("Failed to resolve address for server {} ({}): {}",
                        this.serverName, configured.getHostString(), e.getMessage());
            }
        } finally {
            this.resolvedAt = System.currentTimeMillis();
        }
    }

    private Set<String> resolvePublicAddressAliases() {
        Set<String> aliases = new HashSet<>(4);
        aliases.add(normalizeHost(this.publicAddress.getHostString()));
        try {
            InetAddress[] resolved = this.publicAddress.getAddress() != null
                    ? new InetAddress[]{this.publicAddress.getAddress()}
                    : InetAddress.getAllByName(this.publicAddress.getHostString());
            for (InetAddress address : resolved) {
                // Only the literal IP: getHostName() would fall back to a PTR query for addresses built
                // from raw bytes, and any name the address holds is already covered by getHostString().
                aliases.add(normalizeHost(address.getHostAddress()));
            }
        } catch (UnknownHostException e) {
            // Keep the literal host: an unresolvable name can still be sent verbatim in a TransferPacket.
            // IP-literal transfers will not match until the server entry is recreated by a reload.
            if (ProxyServer.getInstance() != null) {
                ProxyServer.getInstance().getLogger().debug("Failed to resolve public address for server {} ({}): {}",
                        this.serverName, this.publicAddress.getHostString(), e.getMessage());
            }
        }
        return Set.copyOf(aliases);
    }

    public abstract ServerInfoType getServerType();
    public abstract Future<ClientConnection> createConnection(ProxiedPlayer player);

    /**
     * Whether an address a downstream sent in a TransferPacket identifies this server's public address:
     * the configured host matches regardless of case, a trailing root dot or the IPv6 text form, and so
     * do the IP literals it resolved to when this server was created.
     * <p>
     * A public address built from an {@link InetAddress} that carries no name (one created from raw
     * bytes, for instance) only matches by IP, since recovering a name would take a PTR lookup. Servers
     * behind the same public IP and port therefore all match an IP-literal packet;
     * {@link ProxyServer#getServerInfo(String, int)} returns the first in server-name order.
     */
    public boolean matchAddress(String address, int port) {
        if (address == null || this.publicAddress.getPort() != port) {
            return false;
        }
        return this.publicAddressAliases.contains(normalizeHost(address));
    }

    /**
     * Folds the spellings that name the same host: DNS names are case-insensitive with an optional root dot,
     * and IP literals are canonicalized (brackets and the different IPv6 text forms included) while retaining
     * zone ids, so a downstream may send {@code ::1} for a configured {@code 0:0:0:0:0:0:0:1}.
     */
    private static String normalizeHost(String host) {
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        byte[] literal = NetUtil.createByteArrayFromIpAddressString(host);
        if (literal == null) {
            return host.toLowerCase(Locale.ROOT);
        }
        String zone = "";
        int zoneStart = host.indexOf('%');
        if (zoneStart >= 0) {
            int zoneEnd = host.endsWith("]") ? host.length() - 1 : host.length();
            zone = host.substring(zoneStart, zoneEnd);
        }
        return NetUtil.bytesToIpAddress(literal) + zone;
    }

    public void addConnection(ClientConnection connection) {
        if (connection != null && connection.isConnected()) {
            this.players.add(connection.getPlayer());
            this.connections.add(connection);
        }
    }

    public void removeConnection(ClientConnection connection) {
        if (connection != null) {
            this.connections.remove(connection);
            this.players.remove(connection.getPlayer());
        }
    }

    public Set<ProxiedPlayer> getPlayers() {
        return Collections.unmodifiableSet(this.players);
    }

    public Set<ClientConnection> getConnections() {
        return Collections.unmodifiableSet(this.connections);
    }

}
