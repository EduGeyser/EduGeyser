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
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class NetherNetPingSnapshotsTest {

    private static final int BEDROCK_PORT = 19132;

    private final Queue<Runnable> refresher = new ArrayDeque<>();
    private final AtomicLong clock = new AtomicLong(1_000_000_000_000L);
    /** The hosts the Java server was asked about, in order; "-" for none. */
    private final List<String> asked = new ArrayList<>();
    private RuntimeException failure;
    /** Runs once on the clock read that counts it down to zero, then clears itself. */
    private Runnable onRead;
    private int readsUntilOnRead;

    private final NetherNetPingSnapshots snapshots = new NetherNetPingSnapshots(this::ask, refresher::add,
            BEDROCK_PORT, this::now, mock(GeyserLogger.class));

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

    @Test
    void eachVirtualHostGetsItsOwnAnswer() {
        snapshots.prime();
        runRefresher();

        // A new host is answered from the host-less snapshot until its own answer is in
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
    void aRequestNeverWaitsForTheJavaServer() {
        snapshots.prime();
        assertNull(snapshots.latest(null), "nothing answered yet");
        runRefresher();
        GeyserPingInfo first = snapshots.latest(null);

        advance(NetherNetPingSnapshots.MAX_AGE_NANOS);
        // Stale: the request schedules a refresh and is answered from what is there
        assertEquals(first, snapshots.latest(null));
        assertEquals(1, refresher.size());
        assertEquals(first, snapshots.latest(null), "an ask already queued is not queued again");
        assertEquals(1, refresher.size());
        runRefresher();
        assertEquals(2, snapshots.latest(null).getPlayers().getOnline());
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
}
