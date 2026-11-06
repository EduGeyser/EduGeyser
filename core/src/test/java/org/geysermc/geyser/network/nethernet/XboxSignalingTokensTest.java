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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XboxSignalingTokensTest {

    @Test
    void aTokenArrivingAfterItsAttemptGaveUpServesTheNextAttempt() throws Exception {
        CountDownLatch slowLogin = new CountDownLatch(1);
        FakePlayFab playFab = new FakePlayFab(slowLogin);
        try (XboxSignalingTokens tokens = new XboxSignalingTokens(playFab)) {
            tokens.token(false);
            // The source timed that attempt out while PlayFab was still answering
            var nextAttempt = tokens.token(false).toCompletableFuture();
            slowLogin.countDown();

            assertEquals("MCToken token-1", nextAttempt.get(5, TimeUnit.SECONDS));
            assertEquals("MCToken token-1", tokens.token(false).toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(1, playFab.logins.get());
        }
    }

    @Test
    void aRefusedTokenIsReplaced() throws Exception {
        FakePlayFab playFab = new FakePlayFab(null);
        try (XboxSignalingTokens tokens = new XboxSignalingTokens(playFab)) {
            assertEquals("MCToken token-1", tokens.token(false).toCompletableFuture().get(5, TimeUnit.SECONDS));

            assertEquals("MCToken token-2", tokens.token(true).toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(2, playFab.logins.get());
        }
    }

    @Test
    void aRestartReconnectsAgainWhenTheJoinedAttemptUsedTheOldToken() throws Exception {
        FakePlayFab playFab = new FakePlayFab(null);
        try (XboxSignalingTokens tokens = new XboxSignalingTokens(playFab)) {
            // An attempt is running with the cached token when the restart comes
            assertEquals("MCToken token-1", tokens.token(false).toCompletableFuture().get(5, TimeUnit.SECONDS));

            List<String> attemptTokens = new ArrayList<>();
            AtomicInteger reconnects = new AtomicInteger();
            tokens.reconnectWithFreshToken(() -> {
                if (reconnects.incrementAndGet() == 1) {
                    // Joins the running attempt, which then succeeds
                    return CompletableFuture.completedFuture(null);
                }
                // Starts a new attempt, which asks for a token first
                return tokens.token(false).thenAccept(attemptTokens::add);
            }, Duration.ofSeconds(5));

            assertEquals(2, reconnects.get());
            assertEquals(List.of("MCToken token-2"), attemptTokens);
        }
    }

    /** Answers for PlayFab and the session service; each login yields the next numbered token. */
    static final class FakePlayFab extends PlayFabTokenManager {
        final AtomicInteger logins = new AtomicInteger();
        private final @Nullable CountDownLatch firstLoginGate;

        /** @param firstLoginGate when set, the first login waits for it */
        FakePlayFab(@Nullable CountDownLatch firstLoginGate) {
            super(new EmptyGeyserLogger(), "custom-id", "device-id");
            this.firstLoginGate = firstLoginGate;
        }

        @Override
        JsonObject postJson(String url, String jsonBody, @Nullable String userAgent) throws IOException {
            JsonObject response = new JsonObject();
            if (url.endsWith("/Client/LoginWithCustomID")) {
                if (logins.incrementAndGet() == 1 && firstLoginGate != null) {
                    try {
                        firstLoginGate.await();
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                }
                JsonObject data = new JsonObject();
                data.addProperty("SessionTicket", "ticket");
                response.add("data", data);
            } else {
                JsonObject result = new JsonObject();
                result.addProperty("authorizationHeader", "MCToken token-" + logins.get());
                result.addProperty("validUntil", Instant.now().plus(Duration.ofHours(4)).toString());
                response.add("result", result);
            }
            return response;
        }
    }
}
