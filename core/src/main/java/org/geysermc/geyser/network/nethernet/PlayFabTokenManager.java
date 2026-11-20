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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.geyser.GeyserLogger;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Handles PlayFab authentication and MCToken acquisition.
 * The MCToken is required for Nethernet signaling WebSocket authentication.
 *
 * Auth chain: PlayFab LoginWithCustomID -> SessionTicket -> session/start -> MCToken
 * <p>
 * The Education client signs in the same way: anonymously, with a custom ID it keeps. Both
 * requests follow those of an Education 26.33 client captured on 2026-11-20, with two differences:
 * <ul>
 *     <li>The PlayFab login is not signed. The client adds x-playfab-signature and
 *     x-playfab-timestamp, made with a secret of its own account, and PlayFab accepts the login
 *     without them.</li>
 *     <li>session/start names the token type "PlayFab". The client names "Edu" and adds the token
 *     its Education account got from Minecraft Education Services, which a server does not have;
 *     the service refuses "Edu" without it.</li>
 * </ul>
 */
public class PlayFabTokenManager {

    private static final String PLAYFAB_BASE = "https://6955f.playfabapi.com";
    private static final String SESSION_START_URL = "https://authorization.franchise.minecraft-services.net/api/v1.0/session/start";
    private static final String LOGIN_URL = PLAYFAB_BASE + "/Client/LoginWithCustomID";
    private static final String TITLE_ID = "6955F";
    /** The client's HTTP library names itself so on every request. */
    private static final String CLIENT_USER_AGENT = "libhttpclient/1.0.0.0";
    /** PlayFab's error code for a custom ID that has no account yet. */
    private static final int ACCOUNT_NOT_FOUND = 1001;
    /**
     * The game version session/start is told, as the captured Education client wrote it. Only
     * change it to a value seen in a real client's request: the service knows those, and the
     * versions EduGeyser lists elsewhere are written for players, not for this service.
     */
    private static final String GAME_VERSION = "1.26.33";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    /** Unlike HttpURLConnection, this client adds no Accept header, which the game client does not send either. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(HTTP_TIMEOUT)
            .build();
    /** Cached tokens within this margin of expiry are treated as expired. */
    private static final long TOKEN_EXPIRY_MARGIN_MILLIS = 10 * 60 * 1000;

    private final GeyserLogger logger;
    private final String customId;
    private final String deviceId;

    private volatile @Nullable String sessionTicket;
    private volatile @Nullable String mcToken;
    private volatile @Nullable String mcTokenExpiry;
    private volatile @Nullable String pmsgId;

    /**
     * The customId and deviceId come persisted from connection-id.yml: the pmid
     * is bound to the anonymous PlayFab account behind the customId, so reusing
     * the same identity keeps the connection ID clients type stable across
     * restarts.
     */
    public PlayFabTokenManager(GeyserLogger logger, String customId, String deviceId) {
        this.logger = logger;
        this.customId = customId;
        this.deviceId = deviceId;
    }

    /**
     * Returns a valid MCToken, reusing the cached one while it is still well
     * within its validity window and performing the full auth chain
     * (PlayFab login -> MCToken acquisition) otherwise. Frequent signaling
     * reconnects therefore do not hammer PlayFab. If the service rejects a
     * token that looks valid by expiry, call {@link #invalidate()} first to
     * force a full re-auth.
     *
     * @return the MCToken authorization header (e.g. "MCToken eyJ..."), or null on failure
     */
    public @Nullable String authenticate() {
        String cached = mcToken;
        if (cached != null && tokenStillValid()) {
            return cached;
        }
        return renew();
    }

    /**
     * Performs the full auth chain whatever the cache holds. On failure the
     * cached token stays, so a valid one keeps serving.
     *
     * @return the new MCToken authorization header, or null on failure
     */
    public @Nullable String renew() {
        try {
            loginToPlayFab();
            obtainMCToken();
            return mcToken;
        } catch (Exception e) {
            logger.error("[Nethernet] PlayFab authentication failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Drops the cached token so the next {@link #authenticate()} performs a
     * full re-auth. Used when the signaling service refuses a token that has
     * not yet reached its recorded expiry.
     */
    public void invalidate() {
        mcToken = null;
        mcTokenExpiry = null;
        sessionTicket = null;
    }

    private boolean tokenStillValid() {
        String expiry = mcTokenExpiry;
        if (expiry == null) {
            return false;
        }
        try {
            long expiresAt;
            try {
                expiresAt = Instant.parse(expiry).toEpochMilli();
            } catch (Exception e) {
                expiresAt = OffsetDateTime.parse(expiry).toInstant().toEpochMilli();
            }
            return expiresAt - TOKEN_EXPIRY_MARGIN_MILLIS > System.currentTimeMillis();
        } catch (Exception e) {
            // Unparseable expiry: play it safe and re-auth.
            return false;
        }
    }

    public @Nullable String getMCToken() {
        return mcToken;
    }

    public @Nullable String getPmsgId() {
        return pmsgId;
    }

    private @Nullable String extractPmsgId(String token) {
        // MCToken format: "MCToken eyJ..." — decode the JWT payload
        String[] parts = token.split(" ", 2);
        if (parts.length < 2) return null;
        String[] jwtParts = parts[1].split("\\.");
        if (jwtParts.length < 2) return null;
        try {
            String payload = new String(java.util.Base64.getUrlDecoder().decode(jwtParts[1]), StandardCharsets.UTF_8);
            JsonObject claims = JsonParser.parseString(payload).getAsJsonObject();
            return claims.has("pmid") ? claims.get("pmid").getAsString() : null;
        } catch (Exception e) {
            logger.debug("[Nethernet] Failed to extract pmid from MCToken: " + e.getMessage());
            return null;
        }
    }

    private void loginToPlayFab() throws IOException {
        JsonObject response;
        try {
            response = postJson(LOGIN_URL, loginBody(customId, false), CLIENT_USER_AGENT);
        } catch (HttpStatusException e) {
            if (!e.isPlayFabError(ACCOUNT_NOT_FOUND)) {
                throw e;
            }
            // The first sign in of this custom ID, on a new install
            response = postJson(LOGIN_URL, loginBody(customId, true), CLIENT_USER_AGENT);
        }

        if (!response.has("data") || !response.getAsJsonObject("data").has("SessionTicket")) {
            throw new IOException("PlayFab login response missing SessionTicket");
        }

        this.sessionTicket = response.getAsJsonObject("data").get("SessionTicket").getAsString();
        logger.debug("[Nethernet] PlayFab login successful");
    }

    /**
     * The login request as the client writes it, to the byte: its JSON library indents by three
     * spaces, puts spaces around the colons and ends with a line feed. The captured client sent
     * {@code CreateAccount} false for its existing account.
     *
     * @param customId the custom ID, which holds nothing JSON would need escaped (see connection-id.yml)
     */
    static String loginBody(String customId, boolean createAccount) {
        return """
            {
               "CreateAccount" : %s,
               "CustomId" : "%s",
               "InfoRequestParameters" : {
                  "GetPlayerProfile" : true,
                  "GetUserAccountInfo" : true
               },
               "TitleId" : "%s"
            }
            """.formatted(createAccount, customId, TITLE_ID);
    }

    private void obtainMCToken() throws IOException {
        if (sessionTicket == null) {
            throw new IOException("No PlayFab session ticket available");
        }

        JsonObject response = postJson(SESSION_START_URL, sessionStartBody(deviceId, sessionTicket), CLIENT_USER_AGENT);

        if (!response.has("result") || !response.getAsJsonObject("result").has("authorizationHeader")) {
            throw new IOException("session/start response missing authorizationHeader");
        }

        JsonObject result = response.getAsJsonObject("result");
        this.mcToken = result.get("authorizationHeader").getAsString();
        // A token without an expiry counts as expired; it must not inherit the previous token's
        this.mcTokenExpiry = result.has("validUntil") ? result.get("validUntil").getAsString() : null;

        // Extract pmid from the MCToken JWT payload
        this.pmsgId = extractPmsgId(this.mcToken);

        logger.debug("[Nethernet] MCToken obtained");
    }

    /**
     * The session/start request with the device block of the captured client: its application
     * type, game version and hardware values. With these the MCToken names the same application
     * type as an Education client's; the pmid, and with it the connection ID, does not depend on
     * them (measured 2026-11-20).
     */
    static String sessionStartBody(String deviceId, String sessionTicket) {
        JsonArray capabilities = new JsonArray();
        capabilities.add("RayTracing");

        JsonObject device = new JsonObject();
        device.addProperty("applicationType", "MinecraftEdu");
        device.add("capabilities", capabilities);
        device.addProperty("gameVersion", GAME_VERSION);
        device.addProperty("hardwareMemoryTier", 5);
        device.addProperty("id", deviceId);
        device.addProperty("isPreview", false);
        device.addProperty("memory", "29687091200");
        device.addProperty("platform", "Win32");
        device.addProperty("playFabTitleId", TITLE_ID);
        device.addProperty("storePlatform", "uwp.store");
        device.add("treatmentOverrides", null);
        device.addProperty("type", "Win32");

        JsonObject user = new JsonObject();
        user.addProperty("language", "en");
        user.addProperty("languageCode", "en-US");
        user.addProperty("regionCode", "US");
        user.addProperty("token", sessionTicket);
        user.addProperty("tokenType", "PlayFab");

        JsonObject payload = new JsonObject();
        payload.add("device", device);
        payload.add("user", user);
        return payload.toString();
    }

    /** Package-private so tests can answer for PlayFab and the session service. */
    JsonObject postJson(String url, String jsonBody, @Nullable String userAgent) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        if (userAgent != null) {
            request.header("User-Agent", userAgent);
        }
        HttpResponse<String> response;
        try {
            response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException("interrupted during the request to " + url);
            interrupted.initCause(e);
            throw interrupted;
        }
        if (response.statusCode() >= 400) {
            throw new HttpStatusException(response.statusCode(), response.body());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    /** An error status, with the body the service explained it in. */
    static final class HttpStatusException extends IOException {
        private final String body;

        HttpStatusException(int status, String body) {
            super("HTTP " + status + ": " + body);
            this.body = body;
        }

        /** Whether the body is a PlayFab error with this {@code errorCode}. */
        boolean isPlayFabError(int errorCode) {
            try {
                JsonObject error = JsonParser.parseString(body).getAsJsonObject();
                return error.has("errorCode") && error.get("errorCode").getAsInt() == errorCode;
            } catch (RuntimeException e) {
                return false;
            }
        }
    }
}
