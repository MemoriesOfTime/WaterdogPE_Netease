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

package dev.waterdog.waterdogpe.network.protocol.user;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.mot.protocol.extension.BedrockCryptoUtils;
import dev.mot.protocol.extension.NetEaseEncryptionUtils;
import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.network.protocol.ProtocolVersion;
import dev.waterdog.waterdogpe.utils.config.proxy.ProxyConfig;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.protocol.bedrock.BedrockSession;
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket;
import org.cloudburstmc.protocol.bedrock.util.ChainValidationResult;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;

import javax.crypto.SecretKey;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.text.ParseException;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Various utilities for parsing Handshake data
 */
@Log4j2
public class HandshakeUtils {

    @Getter
    private static final KeyPair privateKeyPair;

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(Curve.P_384.toECParameterSpec());
            privateKeyPair = generator.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Unable to generate private keyPair!", e);
        }
    }

    // Certificate chain
    public static JsonObject createChainExtraData(String displayName, String xuid, UUID uuid) {
        JsonObject extraData = new JsonObject();
        extraData.addProperty("displayName", displayName);
        extraData.addProperty("XUID", xuid);
        extraData.addProperty("identity", uuid.toString());
        return extraData;
    }

    public static SignedJWT createClientDataChain(KeyPair pair, JsonObject extraData) {
        String publicKeyBase64 = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        long timestamp = System.currentTimeMillis() / 1000;

        JsonObject dataChain = new JsonObject();
        dataChain.addProperty("nbf", timestamp - 3600);
        dataChain.addProperty("exp", timestamp + 24 * 3600);
        dataChain.addProperty("iat", timestamp);
        dataChain.addProperty("iss", "self");
        dataChain.addProperty("certificateAuthority", true);
        dataChain.add("extraData", extraData);
        dataChain.addProperty("randomNonce", UUID.randomUUID().getLeastSignificantBits());
        dataChain.addProperty("identityPublicKey", publicKeyBase64);
        return encodeJWT(pair, dataChain);
    }

    // Token
    public static SignedJWT createClientDataToken(KeyPair pair, String displayName, String xuid, UUID uuid, String minecraftId) {
        String publicKeyBase64 = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        long timestamp = System.currentTimeMillis() / 1000;

        JsonObject dataChain = new JsonObject();
        dataChain.addProperty("cpk", publicKeyBase64);
        dataChain.addProperty("leguuid", uuid.toString());
        dataChain.addProperty("iat", timestamp);
        dataChain.addProperty("xname", displayName);
        dataChain.addProperty("exp", timestamp + 24 * 3600);
        dataChain.addProperty("mid", minecraftId);
        dataChain.addProperty("ap", 7);
        dataChain.addProperty("iss", "self");
        // PMMP seem to require this, but CloudburstMC/Protocol does not.
        dataChain.addProperty("aud", "api://auth-minecraft-services/multiplayer");
        // Normally "xid" is sent as empty string for self-signed certificates, but we include it anyway
        dataChain.addProperty("xid", xuid);
        return encodeJWT(pair, dataChain);
    }

    public static SignedJWT encodeJWT(KeyPair pair, JsonObject payload) {
        String publicKeyBase64 = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        URI x5u = URI.create(publicKeyBase64);
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES384).x509CertURL(x5u).build();
        try {
            SignedJWT jwt = new SignedJWT(header, JWTClaimsSet.parse(payload.toString()));
            signJwt(jwt, (ECPrivateKey) pair.getPrivate());
            return jwt;
        } catch (JOSEException | ParseException e) {
            throw new RuntimeException(e);
        }
    }

    public static void signJwt(JWSObject jws, ECPrivateKey key) throws JOSEException {
        jws.sign(new ECDSASigner(key, Curve.P_384));
    }

    public static boolean verifyJwt(JWSObject jws, ECPublicKey key) throws JOSEException {
        return jws.verify(new ECDSAVerifier(key));
    }

    public static HandshakeEntry processHandshake(BedrockSession session, LoginPacket packet, ProtocolVersion protocol, boolean strict) throws Exception {
        return processHandshake(session, packet, protocol, strict, false);
    }

    public static HandshakeEntry processHandshake(BedrockSession session, LoginPacket packet, ProtocolVersion protocol, boolean strict, boolean neteaseClient) throws Exception {
        ChainValidationResult result;
        if (neteaseClient && packet.getAuthPayload() instanceof CertificateChainPayload chainPayload) {
            result = NetEaseEncryptionUtils.validateChain(chainPayload);
        } else {
            result = EncryptionUtils.validatePayload(packet.getAuthPayload());
        }
        boolean xboxAuth = result.signed();
        ChainValidationResult.IdentityClaims identityClaims = result.identityClaims();
        ChainValidationResult.IdentityData identityData = identityClaims.extraData;
        // Do NOT use identityClaims.parsedIdentityPublicKey() here: it delegates to
        // EncryptionUtils.parseKey and would trigger its static Mojang endpoint fetch.
        ECPublicKey identityPublicKey = BedrockCryptoUtils.parseKey(identityClaims.identityPublicKey);
        String xuid = identityData.xuid;
        UUID uuid = identityData.identity;
        String minecraftId = identityData.minecraftId;

        SignedJWT clientDataJwt = SignedJWT.parse(packet.getClientJwt());
        JsonObject clientData = HandshakeUtils.parseClientData(clientDataJwt, xuid, session);
        if (!verifyJwt(clientDataJwt, identityPublicKey) && strict) {
            xboxAuth = false;
        }
        String displayName;
        if (ProxyServer.getInstance().getConfiguration().isReplaceUsernameSpaces()) {
            displayName = identityData.displayName
                    .replaceAll(" ", "_");
        } else {
            displayName = identityData.displayName;
        }

        ProxyConfig config = ProxyServer.getInstance().getConfiguration();
        applyLoginExtras(
                clientData,
                config.useLoginExtras(),
                xboxAuth,
                identityData.xuid,
                identityData.minecraftId,
                ((InetSocketAddress) session.getSocketAddress()).getAddress().getHostAddress()
        );
        // Before 1.26.20, client sends CertificateChainPayload in LoginPacket instead of TokenPayload
        // We are trying to replicate that behavior.
        boolean shouldSendCertificateChain = packet.getAuthPayload() instanceof CertificateChainPayload ||
                protocol.isBefore(ProtocolVersion.MINECRAFT_PE_1_26_20);

        LoginData.NetEaseData netEaseData = null;
        if (neteaseClient) {
            netEaseData = extractNetEaseData(result.rawIdentityClaims());
        }

        return new HandshakeEntry(identityPublicKey, clientData, xuid, uuid, displayName, minecraftId, xboxAuth, protocol,
                shouldSendCertificateChain,
                packet.getAuthPayload() instanceof CertificateChainPayload, neteaseClient, netEaseData);
    }

    @SuppressWarnings("unchecked")
    private static LoginData.NetEaseData extractNetEaseData(Map<String, Object> rawClaims) {
        try {
            Map<String, Object> extraData = (Map<String, Object>) rawClaims.get("extraData");
            if (extraData == null) {
                return null;
            }

            long uid = extraData.containsKey("uid") ? ((Number) extraData.get("uid")).longValue() : 0L;
            String sessionId = (String) extraData.get("netease_sid");
            String platform = (String) extraData.get("platform");
            String osName = (String) extraData.get("os_name");
            String env = (String) extraData.get("env");
            String engineVersion = (String) extraData.get("engineVersion");
            String patchVersion = (String) extraData.get("patchVersion");
            String bit = (String) extraData.get("bit");

            return new LoginData.NetEaseData(uid, sessionId, platform, osName, env, engineVersion, patchVersion, bit);
        } catch (Exception e) {
            log.warn("Failed to extract NetEase data from login chain", e);
            return null;
        }
    }

    public static JsonObject parseClientData(JWSObject clientJwt, String xuid, BedrockSession session) {
        JsonObject clientData = (JsonObject) JsonParser.parseString(clientJwt.getPayload().toString());
        clearLoginExtras(clientData);
        return clientData;
    }

    static void applyLoginExtras(JsonObject clientData, boolean enabled, boolean authenticated,
                                 String xuid, String minecraftId, String address) {
        clearLoginExtras(clientData);
        if (!enabled) {
            return;
        }
        if (address != null && !address.isBlank()) {
            clientData.addProperty("Waterdog_IP", address);
        }
        if (!authenticated) {
            return;
        }
        clientData.addProperty("Waterdog_Auth", true);
        if (xuid != null) {
            clientData.addProperty("Waterdog_XUID", xuid);
        }
        if (minecraftId != null) {
            clientData.addProperty("Waterdog_MID", minecraftId);
        }
    }

    private static void clearLoginExtras(JsonObject clientData) {
        // These fields are proxy-owned trust claims and must never survive from client-controlled JWT data.
        clientData.remove("Waterdog_Auth");
        clientData.remove("Waterdog_XUID");
        clientData.remove("Waterdog_MID");
        clientData.remove("Waterdog_IP");
    }

    public static void processEncryption(BedrockSession session, PublicKey key) throws Exception {
        byte[] token = BedrockCryptoUtils.generateRandomToken();
        SecretKey encryptionKey = BedrockCryptoUtils.getSecretKey(privateKeyPair.getPrivate(), key, token);

        ServerToClientHandshakePacket packet = new ServerToClientHandshakePacket();
        packet.setJwt(BedrockCryptoUtils.createHandshakeJwt(privateKeyPair, token));

        session.getPeer().getChannel().eventLoop().execute(() -> {
            session.sendPacketImmediately(packet);
            session.enableEncryption(encryptionKey);
        });
    }

}
