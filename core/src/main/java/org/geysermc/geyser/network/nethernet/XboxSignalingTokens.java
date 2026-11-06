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

import io.github.sendablemetatype.netty.channel.nethernet.signaling.NetherNetXboxRpcSignaling;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Supplies the Xbox source's MCTokens from a {@link PlayFabTokenManager}.
 *
 * Fetches block on PlayFab for seconds, so they run on a thread of their own,
 * never on the signaling event loop that asks for them, and one at a time in
 * the order they were asked for, since PlayFabTokenManager is not meant for
 * concurrent authentication. A request made while a fetch runs shares it.
 *
 * A valid token is never dropped because an attempt failed or timed out: one
 * authentication is two PlayFab requests with 15 second timeouts each, so it
 * can outlast the source's 30 second attempt deadline. Dropping the token it
 * delivers late would make every following attempt authenticate again and
 * time out the same way. Only the service refusing a token, its expiry, or a
 * manual restart ({@link #reconnectWithFreshToken}) replace it.
 */
final class XboxSignalingTokens implements NetherNetXboxRpcSignaling.TokenSupplier, AutoCloseable {

    private final PlayFabTokenManager tokenManager;
    private final ExecutorService executor;

    // Guarded by this.
    private long fetchCount;
    /** The newest fetch; plain requests made while it runs share it. */
    private @Nullable Fetch latest;
    /** The fetch behind the source's newest request: the one of its current or last attempt. */
    private @Nullable Fetch requested;

    XboxSignalingTokens(PlayFabTokenManager tokenManager) {
        this.tokenManager = tokenManager;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Nethernet Token");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Serves the cached token while it is valid. With {@code refresh} the
     * service refused the previous attempt's token, so that token is dropped,
     * unless a newer fetch has replaced it already.
     */
    @Override
    public synchronized CompletionStage<String> token(boolean refresh) {
        Fetch fetch = latest;
        if (refresh) {
            CompletableFuture<String> previous = requested != null ? requested.token() : null;
            String refused = previous != null && previous.state() == Future.State.SUCCESS
                    ? previous.resultNow() : null;
            fetch = submit(() -> {
                if (refused == null || refused.equals(tokenManager.getMCToken())) {
                    tokenManager.invalidate();
                }
                return tokenManager.authenticate();
            });
        } else if (fetch == null || fetch.token().isDone()) {
            fetch = submit(tokenManager::authenticate);
        }
        requested = fetch;
        // A copy, so the source cannot complete the shared fetch
        return fetch.token().minimalCompletionStage();
    }

    /**
     * Replaces the source's connection with one made with a token from a new
     * authentication that this call starts. Blocks until that connection is
     * up or the timeout ends.
     *
     * The token comes first: the source's reconnect joins an attempt that is
     * already running instead of starting one, and that attempt may use the
     * token from before. When the connection that comes up used an older
     * token, this reconnects again.
     *
     * @param reconnect the source's reconnect: completes when the attempt it starts or joins succeeds
     * @throws ExecutionException when the authentication or an attempt fails
     * @throws TimeoutException when the timeout ends first
     */
    void reconnectWithFreshToken(Supplier<? extends CompletionStage<Void>> reconnect, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        long deadline = System.nanoTime() + timeout.toNanos();
        Fetch fresh;
        synchronized (this) {
            fresh = submit(tokenManager::renew);
        }
        fresh.token().get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
        while (true) {
            reconnect.get().toCompletableFuture().get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            synchronized (this) {
                // Fetches run in order, so every later one serves this token or a newer one
                if (requested != null && requested.number() >= fresh.number()) {
                    return;
                }
            }
        }
    }

    /** Stops fetching and fails the fetches still waiting to run. */
    @Override
    public void close() {
        for (Runnable task : executor.shutdownNow()) {
            if (task instanceof FetchTask fetch) {
                fetch.token().completeExceptionally(new IllegalStateException("Nethernet is shut down"));
            }
        }
    }

    private Fetch submit(Supplier<@Nullable String> authentication) {
        CompletableFuture<String> token = new CompletableFuture<>();
        try {
            executor.execute(new FetchTask(authentication, token));
        } catch (RejectedExecutionException e) {
            token.completeExceptionally(e);
        }
        Fetch fetch = new Fetch(++fetchCount, token);
        latest = fetch;
        return fetch;
    }

    /** One token fetch; fetches run in the order of their numbers. */
    private record Fetch(long number, CompletableFuture<String> token) {
    }

    private record FetchTask(Supplier<@Nullable String> authentication, CompletableFuture<String> token)
            implements Runnable {
        @Override
        public void run() {
            try {
                String value = authentication.get();
                if (value != null) {
                    token.complete(value);
                } else {
                    token.completeExceptionally(new IOException("could not get an MCToken"));
                }
            } catch (RuntimeException e) {
                token.completeExceptionally(e);
            }
        }
    }
}
