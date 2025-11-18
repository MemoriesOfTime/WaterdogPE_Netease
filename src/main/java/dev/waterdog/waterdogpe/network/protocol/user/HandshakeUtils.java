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

import com.google.gson.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.network.netease.protocol.NeteaseEncryptionUtils;
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
import org.cloudburstmc.protocol.bedrock.util.JsonUtils;

import javax.crypto.SecretKey;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.*;
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

    public static SignedJWT createClientDataToken(KeyPair pair, String displayName, String xuid) {
        String publicKeyBase64 = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        long timestamp = System.currentTimeMillis() / 1000;

        JsonObject dataChain = new JsonObject();
        dataChain.addProperty("iat", timestamp);
        dataChain.addProperty("exp", timestamp + 24 * 3600);
        dataChain.addProperty("iss", "self");
        dataChain.addProperty("cpk", publicKeyBase64);
        dataChain.addProperty("xid", xuid);
        dataChain.addProperty("xname", displayName);
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

    public static HandshakeEntry processHandshake(BedrockSession session, LoginPacket packet, ProtocolVersion protocol, boolean strict, boolean isNeteaseClient) throws Exception {
        ChainValidationResult result;
        if (isNeteaseClient && packet.getAuthPayload() instanceof CertificateChainPayload chainPayload) {
            result = NeteaseEncryptionUtils.validatePayload(chainPayload);
        } else {
            result = EncryptionUtils.validatePayload(packet.getAuthPayload());
        }
        boolean xboxAuth = result.signed();
        ChainValidationResult.IdentityClaims identityClaims = result.identityClaims();
        ChainValidationResult.IdentityData identityData = identityClaims.extraData;
        ECPublicKey identityPublicKey = (ECPublicKey) identityClaims.parsedIdentityPublicKey();
        String xuid = identityData.xuid;
        //UUID uuid = UUID.nameUUIDFromBytes(("pocket-auth-1-xuid:" + xuid).getBytes(StandardCharsets.UTF_8));
        UUID uuid = identityData.identity;

        // 将extraData中网易相关字段全部提出来，合并到转发登录包的extraData中
        JsonObject neteaseExtraData = new JsonObject();
        try {
            var rawIdentityClaims = result.rawIdentityClaims();
            Map<String, Object> extraData = JsonUtils.childAsType(rawIdentityClaims, "extraData", Map.class);
            neteaseExtraData = pickKeysToJson(
                    extraData,
                    "uid",
                    "netease_sid",
                    "platform",
                    "os_name",
                    "env",
                    "engineVersion",
                    "patchVersion",
                    "bit"
            );
        } catch (Exception ignored) {}

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

        if (xboxAuth) {
            ProxyConfig config = ProxyServer.getInstance().getConfiguration();
            if (config.useLoginExtras()) {
                clientData.addProperty("Waterdog_Auth", true);
            }
        }
        return new HandshakeEntry(identityPublicKey, clientData, xuid, uuid, displayName, xboxAuth, protocol,
                packet.getAuthPayload() instanceof CertificateChainPayload, neteaseExtraData);
    }

    public static JsonObject parseClientData(JWSObject clientJwt, String xuid, BedrockSession session) throws Exception {
        JsonObject clientData = (JsonObject) JsonParser.parseString(clientJwt.getPayload().toString());
        ProxyConfig config = ProxyServer.getInstance().getConfiguration();
        if (config.useLoginExtras()) {
            // Add waterdog attributes
            clientData.addProperty("Waterdog_XUID", xuid);
            clientData.addProperty("Waterdog_IP", ((InetSocketAddress) session.getSocketAddress()).getAddress().getHostAddress());
        }
        return clientData;
    }

    public static void processEncryption(BedrockSession session, PublicKey key) throws Exception {
        byte[] token = EncryptionUtils.generateRandomToken();
        SecretKey encryptionKey = EncryptionUtils.getSecretKey(privateKeyPair.getPrivate(), key, token);

        ServerToClientHandshakePacket packet = new ServerToClientHandshakePacket();
        packet.setJwt(EncryptionUtils.createHandshakeJwt(privateKeyPair, token));

        session.getPeer().getChannel().eventLoop().execute(() -> {
            session.sendPacketImmediately(packet);
            session.enableEncryption(encryptionKey);
        });
    }

    public static JsonObject createChainExtraData(String displayName, String xuid, UUID uuid, JsonObject neteaseExtraData) {
        JsonObject extraData = new JsonObject();
        extraData.addProperty("displayName", displayName);
        extraData.addProperty("XUID", xuid);
        extraData.addProperty("identity", uuid.toString());
        for (String key : neteaseExtraData.keySet()) {
            extraData.add(key, neteaseExtraData.get(key));
        }
        return extraData;
    }

    public static JsonObject pickKeysToJson(Map<String, Object> map, String... keys) {
        JsonObject json = new JsonObject();
        if (map == null || keys == null) return json;
        for (String key : keys) {
            if (!map.containsKey(key)) continue;
            Object val = map.get(key);
            if (val == null) {
                json.add(key, JsonNull.INSTANCE);
            } else if (val instanceof Number) {
                json.addProperty(key, (Number) val);
            } else if (val instanceof String) {
                json.addProperty(key, (String) val);
            } else if (val instanceof Boolean) {
                json.addProperty(key, (Boolean) val);
            } else {
                json.addProperty(key, val.toString());
            }
        }
        return json;
    }
}
