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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class NetherNetPingSnapshotsTest {

    private static final int BEDROCK_PORT = 19132;
    /**
     * A budget no test waits out: a request that has it returns only on what the test does, so
     * no assertion depends on how fast the threads are scheduled.
     */
    private static final Duration LONG_WAIT = Duration.ofMinutes(1);
    /** A budget the tests wait out, for requests whose ask cannot finish in time. */
    private static final Duration SHORT_WAIT = Duration.ofMillis(50);
    /**
     * How long a test gives a request that should return without waiting out {@link #LONG_WAIT},
     * or a thread it waits for: generous, and still far below that budget.
     */
    private static final Duration WATCHDOG = Duration.ofSeconds(10);

    // A clock the tests set and asks queued until the test runs them

    private final Queue<Runnable> refresher = new ConcurrentLinkedQueue<>();
    private final AtomicLong clock = new AtomicLong(1_000_000_000_000L);
    /** The hosts the Java server was asked about, in order; "-" for none. */
    private final List<String> asked = new ArrayList<>();
    private RuntimeException failure;
    /** Runs once on the clock read that counts it down to zero, then clears itself. */
    private Runnable onRead;
    private int readsUntilOnRead;

    /** Never waits, so these tests decide when asks run. */
    private final NetherNetPingSnapshots snapshots = queued(Duration.ZERO);

    // Asks on a refresher thread of their own, as in production, and the real clock

    private final ExecutorService liveRefresher = Executors.newSingleThreadExecutor();
    /** Added to the real clock, so a test can pass the time of a quiet spell at once. */
    private final AtomicLong skew = new AtomicLong();
    /** The hosts {@link #live} asked about, in order; "-" for none. */
    private final List<String> askedLive = Collections.synchronizedList(new ArrayList<>());
    /** The passthrough of {@link #live} blocks until this opens. */
    private volatile CountDownLatch gate = new CountDownLatch(0);

    private final NetherNetPingSnapshots live = new NetherNetPingSnapshots(this::askLive, liveRefresher,
            BEDROCK_PORT, () -> System.nanoTime() + skew.get(), LONG_WAIT, mock(GeyserLogger.class));

    @AfterEach
    void stopRefresher() {
        liveRefresher.shutdownNow();
    }

    /** Snapshots on the test's clock and queue that wait up to the given budget. */
    private NetherNetPingSnapshots queued(Duration wait) {
        return onTestClock(refresher::add, wait);
    }

    private NetherNetPingSnapshots onTestClock(Executor refresher, Duration wait) {
        return new NetherNetPingSnapshots(this::ask, refresher, BEDROCK_PORT, this::now, wait,
                mock(GeyserLogger.class));
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
    void aPassthroughSlowerThanTheBudgetLeavesTheAnswerFromBefore() {
        NetherNetPingSnapshots waiting = queued(SHORT_WAIT);
        waiting.prime();
        runRefresher();
        advance(NetherNetPingSnapshots.MAX_AGE_NANOS);

        // The ask stays queued, so only the budget ends each wait
        assertEquals(1, online(assertTimeoutPreemptively(WATCHDOG, () -> waiting.latest(null))));
        assertEquals(1, online(assertTimeoutPreemptively(WATCHDOG, () -> waiting.latest(null))),
                "a second request waits for the same ask");
        assertEquals(1, refresher.size());

        // The late answer serves the next request
        runRefresher();
        assertEquals(2, online(waiting.latest(null)));
    }

    @Test
    void aNewHostWhoseAnswerIsLateGetsTheHostlessOneUntilItsOwnIsIn() {
        NetherNetPingSnapshots waiting = queued(SHORT_WAIT);
        waiting.prime();
        runRefresher();

        assertEquals("-", motd(assertTimeoutPreemptively(WATCHDOG, () -> waiting.latest("lobby.example.com"))));

        runRefresher();
        assertEquals("lobby.example.com", motd(waiting.latest("lobby.example.com")));
    }

    @Test
    void theHostlessFallbackWaitsForWhatIsLeftOfTheBudget() throws InterruptedException {
        NetherNetPingSnapshots waiting = queued(LONG_WAIT);
        waiting.prime();
        runRefresher();
        advance(NetherNetPingSnapshots.MAX_AGE_NANOS);

        Request request = new Request(waiting, "lobby.example.com");
        request.awaitWaiting();
        // Half the budget passes while the request waits for its own ask, which then ends
        // without an answer
        advance(LONG_WAIT.toNanos() / 2);
        failure = new IllegalStateException("no answer for this host");
        refresher.remove().run();
        failure = null;

        // The request falls back to the host-less snapshot and waits for its ask with the rest
        awaitQueued(1);
        request.awaitWaiting();
        runRefresher();

        request.join();
        assertEquals(3, online(request.answer), "the host-less answer of the ask it waited for");
    }

    @Test
    void theHostlessFallbackDoesNotWaitOnceTheBudgetIsSpent() throws InterruptedException {
        NetherNetPingSnapshots waiting = queued(LONG_WAIT);
        waiting.prime();
        runRefresher();
        advance(NetherNetPingSnapshots.MAX_AGE_NANOS);

        Request request = new Request(waiting, "lobby.example.com");
        request.awaitWaiting();
        // The budget runs out while the request waits for its own ask, which then ends without
        // an answer. The host-less ask the request falls back to stays queued.
        advance(LONG_WAIT.toNanos());
        failure = new IllegalStateException("no answer for this host");
        refresher.remove().run();

        request.join();
        assertEquals("-", motd(request.answer));
    }

    @Test
    void aRequestDeniedAnAskWaitsForNone() throws InterruptedException {
        NetherNetPingSnapshots waiting = queued(LONG_WAIT);
        waiting.prime();
        runRefresher();
        advance(NetherNetPingSnapshots.MAX_AGE_NANOS);
        // The host-less refresh hangs, and the asks of every host queue behind it up to the bound
        waiting.prime();
        List<Request> hosts = new ArrayList<>();
        for (int i = 0; i < NetherNetPingSnapshots.MAX_HOSTS; i++) {
            hosts.add(new Request(waiting, "host" + i + ".example.com"));
        }
        awaitQueued(NetherNetPingSnapshots.MAX_OUTSTANDING_ASKS);

        // A new host takes the place of an idle one, but its ask finds no room
        advance(NetherNetPingSnapshots.IDLE_NANOS);
        assertEquals("-", motd(assertTimeoutPreemptively(WATCHDOG, () -> waiting.latest("late.example.com"))),
                "the host-less answer, without waiting for the host-less refresh");
        assertEquals(NetherNetPingSnapshots.MAX_OUTSTANDING_ASKS, refresher.size());

        runRefresher();
        for (Request host : hosts) {
            host.join();
        }
    }

    @Test
    void requestsWaitingOnAClaimThatWasDeniedWaitForNoOther() throws InterruptedException {
        AtomicBoolean rejectNext = new AtomicBoolean();
        CountDownLatch rejecting = new CountDownLatch(1);
        CountDownLatch reject = new CountDownLatch(1);
        // Queues asks, except that the one after rejectNext is set blocks until the test lets it
        // fail, as an executor that shuts down meanwhile does
        NetherNetPingSnapshots waiting = onTestClock(task -> {
            if (rejectNext.getAndSet(false)) {
                rejecting.countDown();
                try {
                    reject.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new RejectedExecutionException("shut down");
            }
            refresher.add(task);
        }, LONG_WAIT);
        waiting.prime();
        runRefresher();
        advance(NetherNetPingSnapshots.MAX_AGE_NANOS);
        // The host-less refresh hangs
        waiting.prime();

        rejectNext.set(true);
        Request claimant = new Request(waiting, "lobby.example.com");
        assertTrue(rejecting.await(WATCHDOG.toNanos(), TimeUnit.NANOSECONDS));
        Request observer = new Request(waiting, "lobby.example.com");
        observer.awaitWaiting();
        reject.countDown();

        claimant.join();
        observer.join();
        assertEquals("-", motd(claimant.answer));
        assertEquals("-", motd(observer.answer), "the host-less answer, without waiting for the host-less refresh");
    }

    @Test
    void theCurrentAnswerNeverWaitsButStillRefreshes() {
        NetherNetPingSnapshots waiting = queued(LONG_WAIT);
        waiting.prime();
        runRefresher();
        advance(NetherNetPingSnapshots.MAX_AGE_NANOS);

        // Stale with an ask that never runs: latest() would wait its whole budget here
        assertEquals("-", motd(assertTimeoutPreemptively(WATCHDOG, () -> waiting.current(null))));
        assertEquals(1, refresher.size(), "the stale snapshot still schedules its refresh");
        runRefresher();
        assertEquals(2, online(waiting.current(null)));
    }

    @Test
    void afterCloseNoRequestWaitsEvenWhileTheRefresherTakesAsks() {
        NetherNetPingSnapshots waiting = queued(LONG_WAIT);
        waiting.prime();
        runRefresher();
        waiting.close();
        advance(NetherNetPingSnapshots.MAX_AGE_NANOS);

        assertEquals("-", motd(assertTimeoutPreemptively(WATCHDOG, () -> waiting.latest(null))));
        assertEquals("-", motd(assertTimeoutPreemptively(WATCHDOG, () -> waiting.latest("lobby.example.com"))));
        assertTrue(refresher.isEmpty(), "nothing is asked after close");
    }

    /** Returns once the given number of asks is queued, as requests on other threads queue them. */
    private void awaitQueued(int asks) throws InterruptedException {
        long deadline = System.nanoTime() + WATCHDOG.toNanos();
        while (refresher.size() < asks) {
            assertTrue(System.nanoTime() < deadline, "only " + refresher.size() + " asks queued");
            Thread.sleep(1);
        }
    }

    @Test
    void theFirstRequestAfterAQuietSpellGetsTheFreshAnswer() {
        assertEquals(1, online(assertTimeoutPreemptively(WATCHDOG, () -> live.latest(null))));
        skew.addAndGet(TimeUnit.HOURS.toNanos(2));

        assertEquals(2, online(assertTimeoutPreemptively(WATCHDOG, () -> live.latest(null))),
                "the answer of the refresh it started, as soon as it came");
    }

    @Test
    void aNewHostsFirstRequestGetsItsOwnAnswer() {
        assertEquals("-", motd(assertTimeoutPreemptively(WATCHDOG, () -> live.latest(null))));

        assertEquals("lobby.example.com",
                motd(assertTimeoutPreemptively(WATCHDOG, () -> live.latest("lobby.example.com"))));
    }

    @Test
    void concurrentRequestsShareOneAsk() throws InterruptedException {
        assertEquals(1, online(assertTimeoutPreemptively(WATCHDOG, () -> live.latest(null))));
        gate = new CountDownLatch(1);
        skew.addAndGet(NetherNetPingSnapshots.MAX_AGE_NANOS);

        List<Request> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            requests.add(new Request(live, null));
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
        assertEquals(1, online(assertTimeoutPreemptively(WATCHDOG, () -> live.latest(null))));
        skew.addAndGet(NetherNetPingSnapshots.MAX_AGE_NANOS);
        // Something else holds the refresher, so the request's ask stays queued
        CountDownLatch held = new CountDownLatch(1);
        liveRefresher.execute(() -> {
            try {
                held.await();
            } catch (InterruptedException ignored) {
            }
        });

        Request queued = new Request(live, null);
        queued.awaitWaiting();
        // As NetherNetServer shuts down: the refresher discards the queued ask
        liveRefresher.shutdownNow();
        live.close();
        queued.join();
        assertEquals(1, online(queued.answer));

        assertEquals(1, online(assertTimeoutPreemptively(WATCHDOG, () -> live.latest("lobby.example.com"))),
                "a new host after shutdown gets the host-less answer without waiting");
    }

    @Test
    void anInterruptedRequestStopsWaiting() throws InterruptedException {
        assertEquals(1, online(assertTimeoutPreemptively(WATCHDOG, () -> live.latest(null))));
        gate = new CountDownLatch(1);
        skew.addAndGet(NetherNetPingSnapshots.MAX_AGE_NANOS);

        Request request = new Request(live, null);
        request.awaitWaiting();
        request.thread.interrupt();
        request.join();

        assertEquals(1, online(request.answer));
        assertTrue(request.interruptedAfter, "the interrupt stays set for the caller");
        gate.countDown();
    }

    /** A request on a thread of its own, so the test can act while it waits. */
    private static final class Request {
        final Thread thread;
        volatile @Nullable GeyserPingInfo answer;
        volatile boolean interruptedAfter;

        Request(NetherNetPingSnapshots waiting, @Nullable String host) {
            thread = Thread.ofPlatform().daemon().start(() -> {
                answer = waiting.latest(host);
                interruptedAfter = Thread.currentThread().isInterrupted();
            });
        }

        /** Returns once the request waits for an ask, the only timed wait it can be in. */
        void awaitWaiting() throws InterruptedException {
            long deadline = System.nanoTime() + WATCHDOG.toNanos();
            while (thread.getState() != Thread.State.TIMED_WAITING) {
                assertTrue(System.nanoTime() < deadline, "the request never started to wait");
                Thread.sleep(1);
            }
        }

        /** Fails when the request does not return within {@link #WATCHDOG}, as when it waits out its budget. */
        void join() throws InterruptedException {
            assertTrue(thread.join(WATCHDOG), "the request still waits after " + WATCHDOG);
        }
    }
}
