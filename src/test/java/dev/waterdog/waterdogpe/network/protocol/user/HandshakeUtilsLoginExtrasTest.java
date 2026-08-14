package dev.waterdog.waterdogpe.network.protocol.user;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandshakeUtilsLoginExtrasTest {

    @Test
    void unverifiedLoginCannotForwardOrSpoofAuthenticatedIdentity() {
        JsonObject clientData = spoofedClientData();

        HandshakeUtils.applyLoginExtras(clientData, true, false, "spoofed-xuid", "spoofed-mid", "203.0.113.10");

        assertEquals("203.0.113.10", clientData.get("Waterdog_IP").getAsString());
        assertFalse(clientData.has("Waterdog_Auth"));
        assertFalse(clientData.has("Waterdog_XUID"));
        assertFalse(clientData.has("Waterdog_MID"));
    }

    @Test
    void verifiedLoginForwardsTrustedIdentityExtras() {
        JsonObject clientData = spoofedClientData();

        HandshakeUtils.applyLoginExtras(clientData, true, true, "123456789", "minecraft-id", "203.0.113.11");

        assertTrue(clientData.get("Waterdog_Auth").getAsBoolean());
        assertEquals("123456789", clientData.get("Waterdog_XUID").getAsString());
        assertEquals("minecraft-id", clientData.get("Waterdog_MID").getAsString());
        assertEquals("203.0.113.11", clientData.get("Waterdog_IP").getAsString());
    }

    @Test
    void disabledLoginExtrasStillRemoveClientSuppliedProxyClaims() {
        JsonObject clientData = spoofedClientData();

        HandshakeUtils.applyLoginExtras(clientData, false, true, "123456789", "minecraft-id", "203.0.113.12");

        assertFalse(clientData.has("Waterdog_Auth"));
        assertFalse(clientData.has("Waterdog_XUID"));
        assertFalse(clientData.has("Waterdog_MID"));
        assertFalse(clientData.has("Waterdog_IP"));
    }

    private JsonObject spoofedClientData() {
        JsonObject clientData = new JsonObject();
        clientData.addProperty("Waterdog_Auth", true);
        clientData.addProperty("Waterdog_XUID", "client-controlled");
        clientData.addProperty("Waterdog_MID", "client-controlled");
        clientData.addProperty("Waterdog_IP", "198.51.100.1");
        return clientData;
    }
}
