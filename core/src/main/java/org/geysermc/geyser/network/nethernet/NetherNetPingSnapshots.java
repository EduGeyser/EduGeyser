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

import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.util.JoinAddress;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.ping.GeyserPingInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The Java server's ping answers HTTP status requests are built from, one snapshot per virtual
 * host. Asking the Java server can block for as long as its ping event chain runs (Velocity
 * waits for its event without a limit), so a request is never made to wait for it: a request
 * that finds its snapshot older than {@link #MAX_AGE_NANOS} schedules one refresh on the
 * refresher and is answered from the snapshot it found.
 * <p>
 * The Java server is asked with the server's own address, never the requester's: a snapshot is
 * shared by everyone who names the same host, so an answer a plugin shaped for one requester
 * would reach the others. The requester sees only Geyser's own ping event, which runs per request.
 * <p>
 * Bounds, since anyone who reaches the port can send any Host: at most {@link #MAX_HOSTS} hosts
 * have a snapshot of their own, besides the one for requests that name none. A host that no
 * request named for {@link #IDLE_NANOS} gives up its place once a new host needs one; while all
 * places are taken, a new host is answered from the host-less snapshot. Each snapshot asks at
 * most once per {@link #MAX_AGE_NANOS} and has at most one ask queued or running. Apart from the
 * map, at most {@link #MAX_OUTSTANDING_ASKS} asks are queued or running in all: a snapshot gives
 * up its place with its ask still queued, so without that bound a passthrough that hangs would
 * let new hosts queue asks without end.
 */
final class NetherNetPingSnapshots {

    /** How stale a snapshot may grow before a request for it schedules a refresh. */
    static final long MAX_AGE_NANOS = TimeUnit.SECONDS.toNanos(1);
    /**
     * How many hosts keep a snapshot of their own. A proxy serves a handful of virtual hosts, each
     * reached under a few spellings (with and without the port, the IP from a server list).
     */
    static final int MAX_HOSTS = 64;
    /**
     * How long a host keeps its place without being named. Three times the 30 s poll of an open
     * retail server list, so a list that misses a poll keeps its host's snapshot.
     */
    static final long IDLE_NANOS = TimeUnit.SECONDS.toNanos(90);
    /**
     * How many asks may be queued or running at once: one for every snapshot the map holds at a
     * time. A healthy passthrough never reaches it.
     */
    static final int MAX_OUTSTANDING_ASKS = MAX_HOSTS + 1;
    /** A snapshot's ask state while its ask is queued or running. */
    private static final long ASKING = Long.MIN_VALUE;

    private static final String LOG_PREFIX = "[Nethernet] ";

    private final Function<@Nullable String, @Nullable GeyserPingInfo> passthrough;
    private final Executor refresher;
    private final int defaultPort;
    private final LongSupplier clock;
    private final GeyserLogger logger;
    private final Snapshot hostless;
    private final Map<JoinAddress, Snapshot> byHost = new ConcurrentHashMap<>();
    private final Semaphore outstanding = new Semaphore(MAX_OUTSTANDING_ASKS);

    /**
     * @param passthrough asks the Java server for its answer to the given virtual host, or to none
     *                    for null; may block
     * @param refresher   runs the asks one at a time, off the I/O threads
     * @param defaultPort the port of a host that names none, the HTTP signaling port
     * @param clock       {@link System#nanoTime()}
     * @param logger      where failed asks are reported, at debug
     */
    NetherNetPingSnapshots(Function<@Nullable String, @Nullable GeyserPingInfo> passthrough, Executor refresher,
                           int defaultPort, LongSupplier clock, GeyserLogger logger) {
        this.passthrough = passthrough;
        this.refresher = refresher;
        this.defaultPort = defaultPort;
        this.clock = clock;
        this.logger = logger;
        this.hostless = new Snapshot(clock.getAsLong());
    }

    /** Schedules the host-less snapshot's first ask, so the first requests find an answer. */
    void prime() {
        refreshIfStale(hostless, null);
    }

    /**
     * The latest answer for a host, without waiting. A host with no answer of its own yet is
     * answered from the host-less snapshot meanwhile.
     *
     * @param host the virtual host the request named, such as its Host header, or null
     * @return the Java server's answer, or null when it gave none or has not answered yet
     */
    @Nullable GeyserPingInfo latest(@Nullable String host) {
        long now = clock.getAsLong();
        Snapshot own = host == null || host.isBlank() ? hostless : forHost(JoinAddress.parse(host, defaultPort), now);
        if (own != null) {
            own.requestedNanos = now;
            Answer answer = refreshIfStale(own, host);
            if (answer != null) {
                return answer.info();
            }
        }
        Answer fallback = refreshIfStale(hostless, null);
        return fallback != null ? fallback.info() : null;
    }

    /**
     * The host's snapshot, created if there is room. The key is the host as the passthrough reads
     * it ({@link JoinAddress}), so spellings that ask the same question share one snapshot.
     */
    private @Nullable Snapshot forHost(JoinAddress key, long now) {
        Snapshot snapshot = byHost.get(key);
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (byHost) {
            snapshot = byHost.get(key);
            if (snapshot == null) {
                if (byHost.size() >= MAX_HOSTS) {
                    byHost.values().removeIf(idle -> now - idle.requestedNanos >= IDLE_NANOS);
                    if (byHost.size() >= MAX_HOSTS) {
                        return null;
                    }
                }
                snapshot = new Snapshot(now);
                byHost.put(key, snapshot);
            }
            return snapshot;
        }
    }

    /**
     * Schedules an ask when the snapshot is stale and none is queued or running. The snapshot is
     * claimed against the very time judged stale, so a request that saw it stale while another
     * ask finished does not ask again right after.
     */
    private @Nullable Answer refreshIfStale(Snapshot snapshot, @Nullable String host) {
        long asked = snapshot.asked.get();
        if (asked != ASKING && clock.getAsLong() - asked >= MAX_AGE_NANOS
                && snapshot.asked.compareAndSet(asked, ASKING)) {
            ask(snapshot, host, asked);
        }
        return snapshot.answer;
    }

    private void ask(Snapshot snapshot, @Nullable String host, long asked) {
        if (!outstanding.tryAcquire()) {
            // Asks pile up behind one that hangs; the answer the snapshot has serves meanwhile
            snapshot.asked.set(asked);
            return;
        }
        try {
            refresher.execute(() -> {
                try {
                    snapshot.answer = new Answer(passthrough.apply(host));
                } catch (RuntimeException e) {
                    logger.debug(LOG_PREFIX + "Ping passthrough failed: " + e.getMessage());
                } finally {
                    // Advanced on failure too, so a throwing passthrough is asked once a
                    // second rather than as fast as requests arrive
                    snapshot.asked.set(clock.getAsLong());
                    outstanding.release();
                }
            });
        } catch (RejectedExecutionException e) {
            // Shutdown raced the request; the answer the snapshot has serves fine
            snapshot.asked.set(asked);
            outstanding.release();
        }
    }

    /** One ask's result; null info when the Java server gave no answer. */
    private record Answer(@Nullable GeyserPingInfo info) {
    }

    private static final class Snapshot {
        /**
         * When the last ask finished, or {@link #ASKING} while one is queued or running. Starts
         * stale, so the first request asks.
         */
        final AtomicLong asked;
        volatile @Nullable Answer answer;
        volatile long requestedNanos;

        Snapshot(long now) {
            this.asked = new AtomicLong(now - MAX_AGE_NANOS);
            this.requestedNanos = now;
        }
    }
}
