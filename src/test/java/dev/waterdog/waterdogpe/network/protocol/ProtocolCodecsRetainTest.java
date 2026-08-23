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

package dev.waterdog.waterdogpe.network.protocol;

import dev.mot.protocol.extension.codec.v686.Bedrock_v686_NetEase;
import dev.mot.protocol.extension.packet.*;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.packet.ScriptMessagePacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regression test for a NullPointerException thrown when encoding a NetEase-aliased packet
 * (e.g. {@link NetEaseTextPacket}) after the fast-codec build.
 * <p>
 * {@code BedrockCodec.Builder#retainPackets} keys its internal map by exact {@link Class}, so an
 * {@code aliasPacket(NetEaseTextPacket.class, TextPacket.class)} entry is a distinct key from
 * {@code TextPacket.class}. {@link ProtocolCodecs#buildCodec} retained only the classes in
 * {@code HANDLED_PACKETS}, which did not include the alias classes, so the alias definitions were
 * silently dropped and {@code codec.getPacketDefinition(NetEaseTextPacket.class)} returned null —
 * causing {@code null.getId()} NPEs in {@code BedrockPacketCodec.getPacketId}.
 * <p>
 * These tests share the static {@code HANDLED_PACKETS} list, so order is pinned via
 * {@link TestMethodOrder}: the "dropped" assertion runs first (list still pristine), then the
 * registration test populates the list.
 * <p>
 * Real flow mirrored from {@code ProxyServer.boot()}: the NetEase codec is built from
 * {@code Bedrock_v686_NetEase.CODEC}, which registers the aliases via {@code aliasPacket}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ProtocolCodecsRetainTest {

    /**
     * Documents the pre-fix root cause. Runs first, before any registration pollutes the shared
     * {@code HANDLED_PACKETS} list: an aliased extension packet not in the retain set is dropped
     * by {@code retainPackets}, so its definition is null after {@code buildCodec}.
     */
    @Test
    @Order(1)
    void unregisteredAliasPacketIsDroppedByBuildCodec() {
        BedrockCodec built = ProtocolCodecs.buildCodec(Bedrock_v686_NetEase.CODEC);
        assertNull(built.getPacketDefinition(NetEaseTextPacket.class),
                "Without addHandledPacket, the aliased class is dropped by retainPackets");
    }

    @Test
    @Order(2)
    void vanillaTextPacketAlwaysSurvivesBuildCodec() {
        BedrockCodec built = ProtocolCodecs.buildCodec(Bedrock_v686_NetEase.CODEC);
        assertNotNull(built.getPacketDefinition(TextPacket.class),
                "TextPacket is in HANDLED_PACKETS and must always survive buildCodec");
        assertNotNull(built.getPacketDefinition(ScriptMessagePacket.class),
                "ScriptMessagePacket is constructed by PlayerLatencyBroadcaster and must survive buildCodec");
    }

    /**
     * Mirrors the registration {@code ProxyServer.boot()} performs. After this, every extension
     * packet must survive {@code buildCodec} — i.e. its definition is queryable by exact class.
     * Idempotent (guarded against duplicates), so safe across runs.
     */
    @Test
    @Order(3)
    void addHandledPacketKeepsAllNetEaseExtensionDefinitions() {
        ProtocolCodecs.addHandledPacket(NetEaseTextPacket.class);
        ProtocolCodecs.addHandledPacket(NetEasePlayerAuthInputPacket.class);
        ProtocolCodecs.addHandledPacket(PyRpcPacket.class);
        ProtocolCodecs.addHandledPacket(StoreBuySuccessPacket.class);
        ProtocolCodecs.addHandledPacket(NetEaseJsonPacket.class);
        ProtocolCodecs.addHandledPacket(ConfirmSkinPacket.class);

        BedrockCodec built = ProtocolCodecs.buildCodec(Bedrock_v686_NetEase.CODEC);

        assertNotNull(built.getPacketDefinition(NetEaseTextPacket.class),
                "NetEaseTextPacket must survive buildCodec after addHandledPacket");
        assertNotNull(built.getPacketDefinition(NetEasePlayerAuthInputPacket.class));
        assertNotNull(built.getPacketDefinition(PyRpcPacket.class));
        assertNotNull(built.getPacketDefinition(StoreBuySuccessPacket.class));
        assertNotNull(built.getPacketDefinition(NetEaseJsonPacket.class));
        assertNotNull(built.getPacketDefinition(ConfirmSkinPacket.class));
    }
}
