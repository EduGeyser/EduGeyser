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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.geyser.scoreboard.network.util.EmptyGeyserLogger;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
            + "\"token\":\"A-B/c+d=\",\"tokenType\":\"PlayFab\"}}\n", body);
    }

    @Test
    void writesACustomIdAsAJsonString() {
        assertTrue(PlayFabTokenManager.loginBody("a\"b\\c", false).contains("   \"CustomId\" : \"a\\\"b\\\\c\",\n"));
    }

    @Test
    void createsTheAccountOnlyWhenPlayFabDoesNotKnowTheCustomId() {
        // PlayFab's answer to an unknown custom ID, as received on 2026-11-20
        RecordingPlayFab playFab = new RecordingPlayFab(400,
            "{\"code\":400,\"status\":\"BadRequest\",\"error\":\"AccountNotFound\",\"errorCode\":1001,\"errorMessage\":\"User not found\"}");

        assertNotNull(playFab.renew());

        assertEquals(2, playFab.loginBodies.size());
        assertTrue(playFab.loginBodies.get(0).contains("\"CreateAccount\" : false"));
        assertTrue(playFab.loginBodies.get(1).contains("\"CreateAccount\" : true"));
        assertEquals(List.of(
            "https://6955f.playfabapi.com/Client/LoginWithCustomID as libhttpclient/1.0.0.0",
            "https://6955f.playfabapi.com/Client/LoginWithCustomID as libhttpclient/1.0.0.0",
            "https://authorization.franchise.minecraft-services.net/api/v1.0/session/start as libhttpclient/1.0.0.0"),
            playFab.requests);
    }

    @Test
    void doesNotCreateAnAccountAfterAnotherAnswer() {
        List<RecordingPlayFab> refusals = List.of(
            // An account ban, as PlayFab's API documents it
            new RecordingPlayFab(400, "{\"code\":400,\"status\":\"BadRequest\",\"error\":\"AccountBanned\",\"errorCode\":1002,"
                + "\"errorMessage\":\"The account making this request is currently banned\"}"),
            new RecordingPlayFab(503, "{\"errorCode\":1001}"),
            new RecordingPlayFab(400, "{\"errorCode\":1001.5}"),
            new RecordingPlayFab(400, "{\"errorCode\":1001.00000000000001}"),
            new RecordingPlayFab(400, "{\"errorCode\":[1001]}"),
            new RecordingPlayFab(400, "<html>Bad Request</html>"));

        for (RecordingPlayFab playFab : refusals) {
            assertNull(playFab.renew());
            assertEquals(1, playFab.loginBodies.size());
        }
    }

    @Test
    void sendsOnlyTheHeadersOfTheClient() throws Exception {
        AtomicReference<HttpExchange> received = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();
        HttpServer server = answering(exchange -> {
            received.set(exchange);
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            return 200;
        });
        try {
            JsonObject answer = manager().postJson(url(server), "{\"a\":1}\n", "libhttpclient/1.0.0.0");

            assertEquals("{\"ok\":true}", answer.toString());
            HttpExchange exchange = received.get();
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("HTTP/1.1", exchange.getProtocol());
            // The server writes the names this way; the client sends no Accept and no upgrade to HTTP/2
            assertEquals(Set.of("Content-type", "Content-length", "User-agent", "Host"), exchange.getRequestHeaders().keySet());
            assertEquals("application/json", exchange.getRequestHeaders().getFirst("Content-type"));
            assertEquals("libhttpclient/1.0.0.0", exchange.getRequestHeaders().getFirst("User-agent"));
            assertEquals("{\"a\":1}\n", receivedBody.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusesAnAnswerThatIsNoSuccess() throws Exception {
        // A redirect is not followed, so its body must not be read as the answer
        HttpServer server = answering(exchange -> 307);
        try {
            String url = url(server);
            PlayFabTokenManager.HttpStatusException refused = assertThrows(PlayFabTokenManager.HttpStatusException.class,
                () -> manager().postJson(url, "{}\n", null));

            assertEquals("HTTP 307: {\"ok\":true}", refused.getMessage());
        } finally {
            server.stop(0);
        }
    }

    private static PlayFabTokenManager manager() {
        return new PlayFabTokenManager(new EmptyGeyserLogger(), CUSTOM_ID, "device-id");
    }

    /** The address the server is bound to, which is ::1 on a machine that prefers IPv6. */
    private static String url(HttpServer server) throws URISyntaxException {
        InetSocketAddress bound = server.getAddress();
        return new URI("http", null, bound.getAddress().getHostAddress(), bound.getPort(), "/", null, null).toString();
    }

    /** A server on the loopback address that answers every request with the status its handler gives. */
    private static HttpServer answering(StatusHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] answer = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(handler.handle(exchange), answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        return server;
    }

    @FunctionalInterface
    private interface StatusHandler {
        int handle(HttpExchange exchange) throws IOException;
    }

    /** Refuses the first login with the given answer. */
    private static final class RecordingPlayFab extends PlayFabTokenManager {
        final List<String> loginBodies = new ArrayList<>();
        /** Every request, as "URL as user agent". */
        final List<String> requests = new ArrayList<>();
        private final int refusalStatus;
        private final String refusalBody;

        RecordingPlayFab(int refusalStatus, String refusalBody) {
            super(new EmptyGeyserLogger(), CUSTOM_ID, "device-id");
            this.refusalStatus = refusalStatus;
            this.refusalBody = refusalBody;
        }

        @Override
        JsonObject postJson(String url, String jsonBody, @Nullable String userAgent) throws IOException {
            JsonObject response = new JsonObject();
            requests.add(url + " as " + userAgent);
            if (url.endsWith("/Client/LoginWithCustomID")) {
                loginBodies.add(jsonBody);
                if (loginBodies.size() == 1) {
                    throw new HttpStatusException(refusalStatus, refusalBody);
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
