package dev.waterdog.waterdogpe.network.serverinfo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

class ServerInfoMapTest {

    private ServerInfoMap serverInfoMap;

    @BeforeEach
    void setUp() {
        serverInfoMap = new ServerInfoMap();
    }

    @Test
    void testPutAndGet() {
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 19132);
        ServerInfo info = serverInfoMap.createServerInfo("lobby", address, null, ServerInfoType.BEDROCK);
        serverInfoMap.put("lobby", info);

        ServerInfo result = serverInfoMap.get("lobby");
        assertNotNull(result);
        assertEquals("lobby", result.getServerName());
        assertEquals(address, result.getAddress());
    }

    @Test
    void testGetReturnsNullForUnknown() {
        assertNull(serverInfoMap.get("nonexistent"));
    }

    @Test
    void testPutIfAbsent() {
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 19132);
        ServerInfo first = serverInfoMap.createServerInfo("lobby", address, null, ServerInfoType.BEDROCK);
        ServerInfo second = serverInfoMap.createServerInfo("lobby", address, null, ServerInfoType.BEDROCK);

        assertNull(serverInfoMap.putIfAbsent("lobby", first));
        assertSame(first, serverInfoMap.putIfAbsent("lobby", second));
        assertSame(first, serverInfoMap.get("lobby"));
    }

    @Test
    void testRemove() {
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 19132);
        ServerInfo info = serverInfoMap.createServerInfo("lobby", address, null, ServerInfoType.BEDROCK);
        serverInfoMap.put("lobby", info);

        ServerInfo removed = serverInfoMap.remove("lobby");
        assertSame(info, removed);
        assertNull(serverInfoMap.get("lobby"));
    }

    @Test
    void testCaseInsensitiveKeys() {
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 19132);
        ServerInfo info = serverInfoMap.createServerInfo("Lobby", address, null, ServerInfoType.BEDROCK);
        serverInfoMap.put("Lobby", info);

        assertNotNull(serverInfoMap.get("lobby"));
        assertNotNull(serverInfoMap.get("LOBBY"));
        assertSame(info, serverInfoMap.get("lObBy"));
    }

    @Test
    void testValuesAndKeySet() {
        InetSocketAddress addr1 = new InetSocketAddress("127.0.0.1", 19132);
        InetSocketAddress addr2 = new InetSocketAddress("127.0.0.1", 19133);
        serverInfoMap.put("lobby", serverInfoMap.createServerInfo("lobby", addr1, null, ServerInfoType.BEDROCK));
        serverInfoMap.put("survival", serverInfoMap.createServerInfo("survival", addr2, null, ServerInfoType.BEDROCK));

        assertEquals(2, serverInfoMap.values().size());
        assertEquals(2, serverInfoMap.keySet().size());
        assertTrue(serverInfoMap.keySet().contains("lobby"));
        assertTrue(serverInfoMap.keySet().contains("survival"));
    }
}
