/*
 * Copyright (c) 2026 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.network.nethernet;

import com.google.gson.JsonObject;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.geyser.scoreboard.network.util.EmptyGeyserLogger;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayFabTokenManagerTest {

    /** The format of a real custom ID, not a real one: the ID alone signs in to its account. */
    private static final String CUSTOM_ID = "MCPF0123456789ABCDEF0123456789ABCDEF";

    @Test
    void writesTheLoginRequestAsTheClientDoes() {
        String body = PlayFabTokenManager.loginBody(CUSTOM_ID, false);

        assertEquals("{\n"
            + "   \"CreateAccount\" : false,\n"
            + "   \"CustomId\" : \"MCPF0123456789ABCDEF0123456789ABCDEF\",\n"
            + "   \"InfoRequestParameters\" : {\n"
            + "      \"GetPlayerProfile\" : true,\n"
            + "      \"GetUserAccountInfo\" : true\n"
            + "   },\n"
            + "   \"TitleId\" : \"6955F\"\n"
            + "}\n", body);
        // The captured request's Content-Length
        assertEquals(215, body.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void writesTheSessionStartRequestWithTheClientsDeviceBlock() {
        // A ticket holds characters JSON may write plainly or escaped; the client writes them plainly
        String body = PlayFabTokenManager.sessionStartBody("0123456789abcdef0123456789abcdef", "A-B/c+d=");

        assertEquals("{\"device\":{\"applicationType\":\"MinecraftEdu\",\"capabilities\":[\"RayTracing\"],"
            + "\"gameVersion\":\"1.26.33\",\"hardwareMemoryTier\":5,\"id\":\"0123456789abcdef0123456789abcdef\","
            + "\"isPreview\":false,\"memory\":\"29687091200\",\"platform\":\"Win32\",\"playFabTitleId\":\"6955F\","
            + "\"storePlatform\":\"uwp.store\",\"treatmentOverrides\":null,\"type\":\"Win32\"},"
            + "\"user\":{\"language\":\"en\",\"languageCode\":\"en-US\",\"regionCode\":\"US\","
            + "\"token\":\"A-B/c+d=\",\"tokenType\":\"PlayFab\"}}", body);
    }

    @Test
    void createsTheAccountOnlyWhenPlayFabDoesNotKnowTheCustomId() {
        RecordingPlayFab playFab = new RecordingPlayFab(true);

        assertNotNull(playFab.renew());

        assertEquals(2, playFab.loginBodies.size());
        assertTrue(playFab.loginBodies.get(0).contains("\"CreateAccount\" : false"));
        assertTrue(playFab.loginBodies.get(1).contains("\"CreateAccount\" : true"));
    }

    @Test
    void doesNotCreateAnAccountAfterAnotherError() {
        RecordingPlayFab playFab = new RecordingPlayFab(false);

        assertNull(playFab.renew());

        assertEquals(1, playFab.loginBodies.size());
    }

    /** Refuses the first login: as an unknown custom ID, or with another PlayFab error. */
    private static final class RecordingPlayFab extends PlayFabTokenManager {
        final List<String> loginBodies = new ArrayList<>();
        private final boolean accountNotFound;

        RecordingPlayFab(boolean accountNotFound) {
            super(new EmptyGeyserLogger(), CUSTOM_ID, "device-id");
            this.accountNotFound = accountNotFound;
        }

        @Override
        JsonObject postJson(String url, String jsonBody, @Nullable String userAgent) throws IOException {
            JsonObject response = new JsonObject();
            if (url.endsWith("/Client/LoginWithCustomID")) {
                loginBodies.add(jsonBody);
                if (loginBodies.size() == 1) {
                    // PlayFab's answers, as received on 2026-11-20 and as its API documents account bans
                    throw new HttpStatusException(400, accountNotFound
                        ? "{\"code\":400,\"status\":\"BadRequest\",\"error\":\"AccountNotFound\",\"errorCode\":1001,\"errorMessage\":\"User not found\"}"
                        : "{\"code\":400,\"status\":\"BadRequest\",\"error\":\"AccountBanned\",\"errorCode\":1002,\"errorMessage\":\"The account making this request is currently banned\"}");
                }
                JsonObject data = new JsonObject();
                data.addProperty("SessionTicket", "ticket");
                response.add("data", data);
            } else {
                JsonObject result = new JsonObject();
                result.addProperty("authorizationHeader", "MCToken token");
                result.addProperty("validUntil", Instant.now().plus(Duration.ofHours(4)).toString());
                response.add("result", result);
            }
            return response;
        }
    }
}
