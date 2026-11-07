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
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.ping.GeyserPingInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class NetherNetPingSnapshotsTest {

    private static final int BEDROCK_PORT = 19132;
    /** The wait budget of {@link #waiting}: well past an answer that comes at once, short enough for quick tests. */
    private static final Duration WAIT = Duration.ofMillis(400);
    /** What a test allows over {@link #WAIT}, below a second wait of the same length. */
    private static final long WAIT_WITH_SLACK_NANOS = WAIT.toNanos() * 3 / 2;

    // Snapshots that never wait, so the tests run the refresher when they choose

    private final Queue<Runnable> refresher = new ArrayDeque<>();
    private final AtomicLong clock = new AtomicLong(1_000_000_000_000L);
    /** The hosts the Java server was asked about, in order; "-" for none. */
    private final List<String> asked = new ArrayList<>();
    private RuntimeException failure;
    /** Runs once on the clock read that counts it down to zero, then clears itself. */
    private Runnable onRead;
    private int readsUntilOnRead;

    private final NetherNetPingSnapshots snapshots = new NetherNetPingSnapshots(this::ask, refresher::add,
            BEDROCK_PORT, this::now, Duration.ZERO, mock(GeyserLogger.class));

    // Snapshots that wait, on a refresher thread of their own as in production

    private final ExecutorService liveRefresher = Executors.newSingleThreadExecutor();
    /** Added to the real clock, so a test can pass the time of a quiet spell at once. */
    private final AtomicLong skew = new AtomicLong();
    /** The hosts {@link #waiting} asked about, in order; "-" for none. */
    private final List<String> askedLive = Collections.synchronizedList(new ArrayList<>());
    /** The passthrough of {@link #waiting} blocks until this opens. */
    private volatile CountDownLatch gate = new CountDownLatch(0);

    private final NetherNetPingSnapshots waiting = new NetherNetPingSnapshots(this::askLive, liveRefresher,
            BEDROCK_PORT, () -> System.nanoTime() + skew.get(), WAIT, mock(GeyserLogger.class));

    @AfterEach
    void stopRefresher() {
        liveRefresher.shutdownNow();
    }

    private long now() {
        if (onRead != null && --readsUntilOnRead == 0) {
            Runnable hook = onRead;
            onRead = null;
            hook.run();
        }
        return clock.get();
    }

    private GeyserPingInfo ask(@Nullable String host) {
        asked.add(host == null ? "-" : host);
        if (failure != null) {
            throw failure;
        }
        return new GeyserPingInfo(host == null ? "-" : host, 100, asked.size());
    }

    private GeyserPingInfo askLive(@Nullable String host) {
        try {
            gate.await();
        } catch (InterruptedException e) {
            throw new IllegalStateException("interrupted", e);
        }
        askedLive.add(host == null ? "-" : host);
        return new GeyserPingInfo(host == null ? "-" : host, 100, askedLive.size());
    }

    private void runRefresher() {
        Runnable task;
        while ((task = refresher.poll()) != null) {
            task.run();
        }
    }

    private void advance(long nanos) {
        clock.addAndGet(nanos);
    }

    private static String motd(@Nullable GeyserPingInfo info) {
        return info == null ? null : info.getDescription();
    }

    private static int online(@Nullable GeyserPingInfo info) {
        return info == null ? -1 : info.getPlayers().getOnline();
    }

    @Test
    void eachVirtualHostGetsItsOwnAnswer() {
        snapshots.prime();
        runRefresher();

        // With no time to wait, a new host is answered from the host-less snapshot until its own
        // answer is in
        assertEquals("-", motd(snapshots.latest("lobby.example.com")));
        runRefresher();
        assertEquals("lobby.example.com", motd(snapshots.latest("lobby.example.com")));
        assertEquals("-", motd(snapshots.latest("games.example.com")));
        runRefresher();
        assertEquals("games.example.com", motd(snapshots.latest("games.example.com")));
        assertEquals("lobby.example.com", motd(snapshots.latest("lobby.example.com")));
        assertEquals("-", motd(snapshots.latest(null)));
        assertEquals("-", motd(snapshots.latest(" ")), "a blank Host names no host");
    }

    @Test
    void spellingsTheJavaServerCannotTellApartShareASnapshot() {
        snapshots.latest("play.example.com");
        runRefresher();

        assertEquals("play.example.com", motd(snapshots.latest("play.example.com:" + BEDROCK_PORT)));
        assertTrue(refresher.isEmpty(), "the same host with the default port asks nothing new");
        snapshots.latest("play.example.com:19133");
        assertEquals(1, refresher.size(), "another port is another host");
    }

    @Test
    void aStaleSnapshotHasOneAskQueuedAtATime() {
        snapshots.prime();
        assertNull(snapshots.latest(null), "nothing answered yet");
        runRefresher();
        GeyserPingInfo first = snapshots.latest(null);

        advance(NetherNetPingSnapshots.MAX_AGE_NANOS);
        // Stale: the request schedules a refresh and, with no time to wait, is answered from what
        // is there
        assertEquals(first, snapshots.latest(null));
        assertEquals(1, refresher.size());
        assertEquals(first, snapshots.latest(null), "an ask already queued is not queued again");
        assertEquals(1, refresher.size());
        runRefresher();
        assertEquals(2, online(snapshots.latest(null)));
    }

    @Test
    void eachSnapshotAsksAtMostOncePerMaxAgeEvenWhenTheJavaServerFails() {
        failure = new IllegalStateException("passthrough down");
        for (int i = 0; i < 10; i++) {
            snapshots.latest(null);
            runRefresher();
        }
        assertEquals(List.of("-"), asked);

        advance(NetherNetPingSnapshots.MAX_AGE_NANOS);
        failure = null;
        snapshots.latest(null);
        runRefresher();
        assertEquals("-", motd(snapshots.latest(null)));
        assertEquals(List.of("-", "-"), asked);
    }

    @Test
    void hostsBeyondTheLimitShareTheHostlessSnapshotUntilAPlaceFreesUp() {
        snapshots.prime();
        for (int i = 0; i < NetherNetPingSnapshots.MAX_HOSTS; i++) {
            snapshots.latest("host" + i + ".example.com");
        }
        runRefresher();
        asked.clear();

        assertEquals("-", motd(snapshots.latest("late.example.com")));
        runRefresher();
        assertEquals("-", motd(snapshots.latest("late.example.com")), "no place for another host");
        assertEquals(List.of(), asked, "and the Java server is not asked about it");

        // Every host but host0 goes idle; host0 stays named
        advance(NetherNetPingSnapshots.IDLE_NANOS / 2);
        snapshots.latest("host0.example.com");
        advance(NetherNetPingSnapshots.IDLE_NANOS / 2);
        snapshots.latest("late.example.com");
        runRefresher();
        assertEquals("late.example.com", motd(snapshots.latest("late.example.com")));
        assertEquals("host0.example.com", motd(snapshots.latest("host0.example.com")));
    }

    @Test
    void aRequestThatSawTheSnapshotStaleDoesNotAskAgainOnceAnotherAskFinished() {
        // The request reads the clock on entry, then again between its look at the snapshot and
        // its claim on it. In that window another request asks and its ask finishes.
        onRead = () -> {
            snapshots.latest(null);
            runRefresher();
        };
        readsUntilOnRead = 2;

        snapshots.latest(null);

        assertEquals(List.of("-"), asked, "the other request's ask answered for both");
        assertTrue(refresher.isEmpty(), "no second ask within the same second");
    }

    @Test
    void aPassthroughThatHangsHoldsABoundedNumberOfAsks() {
        // The refresher never runs a task, as behind an ask that never returns. Every round, new
        // hosts take the places of the idle ones, whose asks stay queued.
        snapshots.prime();
        for (int round = 0; round < 5; round++) {
            for (int i = 0; i < NetherNetPingSnapshots.MAX_HOSTS; i++) {
                snapshots.latest("round" + round + "-host" + i + ".example.com");
            }
            advance(NetherNetPingSnapshots.IDLE_NANOS);
        }

        assertEquals(NetherNetPingSnapshots.MAX_OUTSTANDING_ASKS, refresher.size());

        // Once the hang ends, asks are taken again
        runRefresher();
        snapshots.latest("round4-host0.example.com");
        assertEquals(1, refresher.size());
    }

    @Test
    void theFirstRequestAfterAQuietSpellGetsTheFreshAnswer() {
        assertEquals(1, online(waiting.latest(null)));
        skew.addAndGet(TimeUnit.HOURS.toNanos(2));

        long started = System.nanoTime();
        assertEquals(2, online(waiting.latest(null)), "the answer of the refresh it started, not the one from before");
        assertTrue(System.nanoTime() - started < WAIT.toNanos(), "given as soon as it came");
    }

    @Test
    void aNewHostsFirstRequestGetsItsOwnAnswer() {
        assertEquals("-", motd(waiting.latest(null)));

        assertEquals("lobby.example.com", motd(waiting.latest("lobby.example.com")));
    }

    @Test
    void aPassthroughSlowerThanTheBudgetLeavesTheAnswerFromBefore() {
        assertEquals(1, online(waiting.latest(null)));
        gate = new CountDownLatch(1);
        skew.addAndGet(NetherNetPingSnapshots.MAX_AGE_NANOS);

        long started = System.nanoTime();
        assertEquals(1, online(waiting.latest(null)));
        assertTrue(System.nanoTime() - started < WAIT_WITH_SLACK_NANOS);

        // The late answer serves the next request
        gate.countDown();
        assertEquals(2, online(waiting.latest(null)));
    }

    @Test
    void aNewHostWhoseAnswerIsLateGetsTheHostlessOneUntilItsOwnIsIn() {
        assertEquals("-", motd(waiting.latest(null)));
        gate = new CountDownLatch(1);

        long started = System.nanoTime();
        assertEquals("-", motd(waiting.latest("lobby.example.com")));
        assertTrue(System.nanoTime() - started < WAIT_WITH_SLACK_NANOS);

        gate.countDown();
        assertEquals("lobby.example.com", motd(waiting.latest("lobby.example.com")));
    }

    @Test
    void aHangingPassthroughNeverHoldsARequestPastTheBudget() {
        assertEquals("-", motd(waiting.latest(null)));
        gate = new CountDownLatch(1);
        skew.addAndGet(NetherNetPingSnapshots.MAX_AGE_NANOS);

        // A new host waits for its own ask, then falls back to the stale host-less snapshot, whose
        // ask queues behind the hanging one; both within one budget
        for (int i = 0; i < 2; i++) {
            long started = System.nanoTime();
            assertEquals("-", motd(waiting.latest("lobby.example.com")));
            assertTrue(System.nanoTime() - started < WAIT_WITH_SLACK_NANOS, "request " + i);
        }

        gate.countDown();
        assertEquals("lobby.example.com", motd(waiting.latest("lobby.example.com")));
        waiting.latest(null);
        assertEquals(List.of("-", "lobby.example.com", "-"), askedLive, "the second request joined the asks of the first");
    }

    @Test
    void concurrentRequestsShareOneAsk() throws InterruptedException {
        assertEquals(1, online(waiting.latest(null)));
        gate = new CountDownLatch(1);
        skew.addAndGet(NetherNetPingSnapshots.MAX_AGE_NANOS);

        List<Request> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            requests.add(new Request(null));
        }
        for (Request request : requests) {
            request.awaitWaiting();
        }
        gate.countDown();

        for (Request request : requests) {
            request.join();
            assertEquals(2, online(request.answer), "every request got the answer of the one ask");
        }
        assertEquals(List.of("-", "-"), askedLive);
    }

    @Test
    void shutdownLeavesNoRequestWaiting() throws InterruptedException {
        assertEquals(1, online(waiting.latest(null)));
        skew.addAndGet(NetherNetPingSnapshots.MAX_AGE_NANOS);
        // Something else holds the refresher, so the request's ask stays queued
        gate = new CountDownLatch(1);
        CountDownLatch held = gate;
        liveRefresher.execute(() -> {
            try {
                held.await();
            } catch (InterruptedException ignored) {
            }
        });

        Request queued = new Request(null);
        queued.awaitWaiting();
        // As NetherNetServer shuts down: the refresher discards the queued ask
        liveRefresher.shutdownNow();
        waiting.close();
        queued.join();
        assertEquals(1, online(queued.answer));
        assertTrue(queued.tookNanos < WAIT.toNanos() / 2, "woken by close, not by its budget");

        long started = System.nanoTime();
        assertEquals(1, online(waiting.latest("lobby.example.com")), "the host-less answer");
        assertTrue(System.nanoTime() - started < WAIT.toNanos() / 2, "the refresher rejects the new host's ask, so it does not wait");
    }

    @Test
    void anInterruptedRequestStopsWaiting() throws InterruptedException {
        assertEquals(1, online(waiting.latest(null)));
        gate = new CountDownLatch(1);
        skew.addAndGet(NetherNetPingSnapshots.MAX_AGE_NANOS);

        Request request = new Request(null);
        request.awaitWaiting();
        request.thread.interrupt();
        request.join();

        assertEquals(1, online(request.answer));
        assertTrue(request.tookNanos < WAIT.toNanos() / 2);
        assertTrue(request.interruptedAfter, "the interrupt stays set for the caller");
        gate.countDown();
    }

    /** A request to {@link #waiting} on a thread of its own, so the test can act while it waits. */
    private final class Request {
        final Thread thread;
        volatile @Nullable GeyserPingInfo answer;
        volatile long tookNanos;
        volatile boolean interruptedAfter;

        Request(@Nullable String host) {
            thread = Thread.ofPlatform().start(() -> {
                long started = System.nanoTime();
                answer = waiting.latest(host);
                tookNanos = System.nanoTime() - started;
                interruptedAfter = Thread.currentThread().isInterrupted();
            });
        }

        /** Returns once the request waits for an ask; the only timed wait it can be in. */
        void awaitWaiting() {
            long deadline = System.nanoTime() + WAIT.toNanos() / 2;
            while (thread.getState() != Thread.State.TIMED_WAITING) {
                assertTrue(System.nanoTime() < deadline, "the request never started to wait");
                Thread.onSpinWait();
            }
        }

        void join() throws InterruptedException {
            assertTrue(thread.join(Duration.ofSeconds(5)), "the request returned");
        }
    }
}
