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

package dev.waterdog.waterdogpe.event.defaults;

import com.google.gson.JsonObject;
import dev.waterdog.waterdogpe.event.Event;
import lombok.Getter;
import lombok.Setter;

import java.net.SocketAddress;
import java.util.UUID;

/**
 * Fired after chain validation but before Xbox authentication check during login.
 * Plugins can override the authentication result by calling {@link #setAuthenticated(boolean)}.
 * If setAuthenticated(true), Xbox auth check is skipped for this player.
 */
@Getter
public class PlayerPreAuthEvent extends Event {

    private final JsonObject clientData;
    private final String xuid;
    private final UUID uuid;
    private final String displayName;
    private final SocketAddress address;

    @Setter
    private boolean authenticated;

    @Setter
    private String kickMessage = "disconnectionScreen.notAuthenticated";

    public PlayerPreAuthEvent(JsonObject clientData, String xuid, UUID uuid,
                              String displayName, SocketAddress address, boolean authenticated) {
        this.clientData = clientData;
        this.xuid = xuid;
        this.uuid = uuid;
        this.displayName = displayName;
        this.address = address;
        this.authenticated = authenticated;
    }
}
