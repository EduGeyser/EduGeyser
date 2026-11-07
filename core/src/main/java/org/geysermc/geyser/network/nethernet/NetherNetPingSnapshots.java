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

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The Java server's ping answers HTTP status requests are built from, one snapshot per virtual
 * host. A request that finds its snapshot older than {@link #MAX_AGE_NANOS}, or a new host's
 * first request, schedules one refresh on the refresher and waits for it up to the wait budget;
 * a request that finds a refresh queued or running waits for that one. An answer in time is
 * served. Otherwise the request gets the snapshot it found, or the host-less snapshot while its
 * host has no answer of its own. Without the wait, a request that came more than a second after
 * the one before would get the answer that one scheduled: on a quiet server, hours old. The
 * wait is bounded because asking the Java server can block for as long as its ping event chain
 * runs (Velocity waits for its event without a limit), and HTTP signaling answers a status call
 * that misses its deadline with an older document or none.
 * <p>
 * The Java server is asked with the server's own address, never the requester's: a snapshot is
 * shared by everyone who names the same host, so an answer a plugin shaped for one requester
 * would reach the others. The requester sees only Geyser's own ping event, which runs per request.
 * <p>
 * Bounds, since anyone who reaches the port can send any Host: at most {@link #MAX_HOSTS} hosts
 * have a snapshot of their own, besides the one for requests that name none. A host that no
 * request named for {@link #IDLE_NANOS} gives up its place once a new host needs one; while all
 * places are taken, a new host is answered from the host-less snapshot. Each snapshot asks at
 * most once per {@link #MAX_AGE_NANOS} and has at most one ask queued or running, which every
 * request for it waits on. Apart from the map, at most {@link #MAX_OUTSTANDING_ASKS} asks are
 * queued or running in all: a snapshot gives up its place with its ask still queued, so without
 * that bound a passthrough that hangs would let new hosts queue asks without end. A request that
 * cannot schedule an ask waits for none, not even the host-less snapshot's.
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

    private static final String LOG_PREFIX = "[Nethernet] ";

    private final Function<@Nullable String, @Nullable GeyserPingInfo> passthrough;
    private final Executor refresher;
    private final int defaultPort;
    private final LongSupplier clock;
    private final long waitNanos;
    private final GeyserLogger logger;
    private final Snapshot hostless;
    private final Map<JoinAddress, Snapshot> byHost = new ConcurrentHashMap<>();
    private final Semaphore outstanding = new Semaphore(MAX_OUTSTANDING_ASKS);
    /**
     * The published claims until they open, so {@link #close} can open them for the requests that
     * wait on them. Guarded by itself, as is {@link #closed}: a claim is published and added here
     * in one step that close cannot come between, so close reaches every claim a request can
     * find. The ask of each holds an outstanding permit, or the claim is about to be given back.
     */
    private final Set<Claim> inFlight = new HashSet<>();
    /** Set once by {@link #close}; no ask is claimed after it. */
    private boolean closed;

    /**
     * @param passthrough asks the Java server for its answer to the given virtual host, or to none
     *                    for null; may block
     * @param refresher   runs the asks one at a time, off the I/O threads
     * @param defaultPort the port of a host that names none, the HTTP signaling port
     * @param clock       {@link System#nanoTime()}
     * @param wait        how long one request may wait for asks, its own snapshot's and the
     *                    host-less one's together; zero answers every request from what is there
     * @param logger      where failed asks are reported, at debug
     */
    NetherNetPingSnapshots(Function<@Nullable String, @Nullable GeyserPingInfo> passthrough, Executor refresher,
                           int defaultPort, LongSupplier clock, Duration wait, GeyserLogger logger) {
        this.passthrough = passthrough;
        this.refresher = refresher;
        this.defaultPort = defaultPort;
        this.clock = clock;
        this.waitNanos = wait.toNanos();
        this.logger = logger;
        this.hostless = new Snapshot(clock.getAsLong());
    }

    /** Schedules the host-less snapshot's first ask, so the first requests find an answer. */
    void prime() {
        refreshIfStale(hostless, null);
    }

    /**
     * Wakes every request waiting for an ask and claims no ask after, for when the refresher
     * stopped: it discards the asks it had queued, which would otherwise leave their requests to
     * wait out their budget. No request waits after this.
     */
    void close() {
        synchronized (inFlight) {
            closed = true;
            inFlight.forEach(Claim::open);
            inFlight.clear();
        }
    }

    /**
     * The latest answer for a host. When the snapshot is stale or has an ask queued or running,
     * waits for that ask until the wait budget is spent; a host with no answer of its own by then
     * is answered from the host-less snapshot, within the same budget. When no ask could be
     * scheduled for the snapshot, whether this request or the one whose claim it waited on found
     * that out, waits for no other. Returns early when interrupted, with the interrupt still set.
     *
     * @param host the virtual host the request named, such as its Host header, or null
     * @return the Java server's answer, or null when it gave none or has not answered yet
     */
    @Nullable GeyserPingInfo latest(@Nullable String host) {
        return latest(host, waitNanos);
    }

    /**
     * The answer there is for a host, without waiting: schedules a refresh when the snapshot is
     * stale, as {@link #latest(String)} does, but never waits for one. For callers that must not
     * block, such as NXS reporting the listing from its service client's thread.
     *
     * @param host the virtual host, or null
     * @return the Java server's answer, or null when it gave none or has not answered yet
     */
    @Nullable GeyserPingInfo current(@Nullable String host) {
        return latest(host, 0);
    }

    private @Nullable GeyserPingInfo latest(@Nullable String host, long budgetNanos) {
        long now = clock.getAsLong();
        long deadline = now + budgetNanos;
        Snapshot own = host == null || host.isBlank() ? hostless : forHost(JoinAddress.parse(host, defaultPort), now);
        if (own != null) {
            own.requestedNanos = now;
            if (!settle(refreshIfStale(own, host), deadline)) {
                // Asks pile up behind one that hangs, or the refresher stopped: waiting for the
                // host-less ask would only hold the request
                deadline = now;
            }
            Answer answer = own.answer;
            if (answer != null) {
                return answer.info();
            }
        }
        settle(refreshIfStale(hostless, null), deadline);
        Answer fallback = hostless.answer;
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
     * Waits for the ask to wait for, if any, until it finished or the deadline passed.
     *
     * @return false when no ask could be scheduled for the snapshot, whether this request or the
     *         one whose claim it waited on found that out
     */
    private boolean settle(Refresh refresh, long deadline) {
        return switch (refresh) {
            case NoAsk found -> found != NoAsk.DENIED;
            case Pending(Claim claim) -> {
                // Does not wait once the deadline passed
                claim.await(deadline - clock.getAsLong());
                yield !claim.denied();
            }
        };
    }

    /**
     * Schedules an ask when the snapshot is stale and none is queued or running.
     *
     * @return the ask to wait for: the one this call scheduled or one already queued or running
     */
    private Refresh refreshIfStale(Snapshot snapshot, @Nullable String host) {
        while (true) {
            Refresh found = switch (snapshot.state.get()) {
                case Asking(Claim claim) -> new Pending(claim);
                case Idle(long finished) when clock.getAsLong() - finished < MAX_AGE_NANOS -> NoAsk.FRESH;
                case Idle stale -> claim(snapshot, host, stale);
            };
            if (found != null) {
                return found;
            }
            // Another request claimed the snapshot first, or gave its claim back; look again, so
            // that a claim given back is tried again rather than taken for a fresh snapshot
        }
    }

    /**
     * Claims a stale snapshot for an ask and schedules it. The claim is made against the very
     * state judged stale (compareAndSet compares references, not record values), so a request
     * that saw it stale while another ask finished does not ask again right after.
     *
     * @return what to wait for, or null when another request changed the state first
     */
    private @Nullable Refresh claim(Snapshot snapshot, @Nullable String host, Idle stale) {
        Claim claim = new Claim();
        synchronized (inFlight) {
            if (closed) {
                return NoAsk.DENIED;
            }
            if (!snapshot.state.compareAndSet(stale, new Asking(claim))) {
                return null;
            }
            inFlight.add(claim);
        }
        if (!outstanding.tryAcquire()) {
            // Asks pile up behind one that hangs; the answer the snapshot has serves meanwhile
            abandon(snapshot, stale, claim);
            return NoAsk.DENIED;
        }
        try {
            refresher.execute(() -> {
                try {
                    snapshot.answer = new Answer(passthrough.apply(host));
                } catch (RuntimeException e) {
                    logger.debug(LOG_PREFIX + "Ping passthrough failed: " + e.getMessage());
                } finally {
                    // Finished on failure too, so a throwing passthrough is asked once a second
                    // rather than as fast as requests arrive
                    snapshot.state.set(new Idle(clock.getAsLong()));
                    outstanding.release();
                    claim.open();
                    forget(claim);
                }
            });
            return new Pending(claim);
        } catch (RejectedExecutionException e) {
            // Shutdown raced the request; the answer the snapshot has serves fine
            outstanding.release();
            abandon(snapshot, stale, claim);
            return NoAsk.DENIED;
        }
    }

    /**
     * Gives back a claim whose ask never got scheduled. The requests that found the claim
     * meanwhile and wait on it learn that, so none of them waits for another ask.
     */
    private void abandon(Snapshot snapshot, Idle stale, Claim claim) {
        snapshot.state.set(stale);
        claim.deny();
        forget(claim);
    }

    /** Forgets a claim once it is open, never before, so close never misses one a request waits on. */
    private void forget(Claim claim) {
        synchronized (inFlight) {
            inFlight.remove(claim);
        }
    }

    /** One ask's result; null info when the Java server gave no answer. */
    private record Answer(@Nullable GeyserPingInfo info) {
    }

    /** What a request found to wait for when it looked at a snapshot. */
    private sealed interface Refresh {
    }

    /** An ask is queued or running, or was until its claim opened. */
    private record Pending(Claim claim) implements Refresh {
    }

    /** Nothing to wait for. */
    private enum NoAsk implements Refresh {
        /** The snapshot is fresh. */
        FRESH,
        /** The snapshot is stale, but the bound on asks is reached or the refresher stopped. */
        DENIED
    }

    /** Whether a snapshot has an ask queued or running. */
    private sealed interface State {
    }

    /** No ask is queued or running; the last one finished at {@code finishedNanos}. */
    private record Idle(long finishedNanos) implements State {
    }

    /** A request claimed the snapshot for an ask, which is queued or running or about to be. */
    private record Asking(Claim claim) implements State {
    }

    /**
     * A snapshot's claim for one ask, which every request for the snapshot waits on. It opens
     * once the ask finished, once the claim was given back with no ask scheduled, or on
     * {@link NetherNetPingSnapshots#close}.
     */
    private static final class Claim {
        private final CountDownLatch opened = new CountDownLatch(1);
        /** Written before the claim opens, so every request that waited on it reads it. */
        private volatile boolean denied;

        void open() {
            opened.countDown();
        }

        /** Opens the claim of an ask that could not be scheduled. */
        void deny() {
            denied = true;
            open();
        }

        boolean denied() {
            return denied;
        }

        /** Waits until the claim opens or the time is up; returns early when interrupted. */
        void await(long nanos) {
            try {
                opened.await(nanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                // HTTP signaling interrupts its status calls when it closes; the interrupt stays set
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class Snapshot {
        /** Starts stale, so the first request asks. */
        final AtomicReference<State> state;
        volatile @Nullable Answer answer;
        volatile long requestedNanos;

        Snapshot(long now) {
            this.state = new AtomicReference<>(new Idle(now - MAX_AGE_NANOS));
            this.requestedNanos = now;
        }
    }
}
