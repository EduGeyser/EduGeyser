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

import org.geysermc.geyser.GeyserLogger;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class NetherNetRateLimitedLogTest {

    @Test
    void logsAtInfoOncePerIntervalAndCountsWhatWentToDebug() {
        GeyserLogger logger = mock(GeyserLogger.class);
        AtomicLong now = new AtomicLong();
        NetherNetRateLimitedLog log = new NetherNetRateLimitedLog(logger, Duration.ofNanos(10), now::get);

        log.log("a");
        now.set(9);
        log.log("b");
        log.log("c");
        now.set(10);
        log.log("d");
        log.log("e");

        InOrder order = inOrder(logger);
        order.verify(logger).info("a");
        order.verify(logger).debug("b");
        order.verify(logger).debug("c");
        order.verify(logger).info("d (2 more like it since the previous one, logged at debug)");
        order.verify(logger).debug("e");
        order.verifyNoMoreInteractions();
    }
}
