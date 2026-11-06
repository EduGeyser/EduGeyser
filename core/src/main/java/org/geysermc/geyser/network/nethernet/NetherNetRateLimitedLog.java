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

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Logs a line that anyone can cause at will, before any join throttle, such
 * as a failed TLS handshake on HTTP signaling: at info at most once per
 * interval, otherwise at debug. The next info line says how many went to
 * debug since the previous one.
 */
final class NetherNetRateLimitedLog {

    private static final Duration INTERVAL = Duration.ofSeconds(10);

    private final GeyserLogger logger;
    private final long intervalNanos;
    private final LongSupplier nanoTime;
    // Guarded by this
    private boolean infoLogged;
    private long lastInfoNanos;
    private int debugSinceInfo;

    NetherNetRateLimitedLog(GeyserLogger logger) {
        this(logger, INTERVAL, System::nanoTime);
    }

    NetherNetRateLimitedLog(GeyserLogger logger, Duration interval, LongSupplier nanoTime) {
        this.logger = logger;
        this.intervalNanos = interval.toNanos();
        this.nanoTime = nanoTime;
    }

    void log(String message) {
        boolean atInfo;
        int sentToDebug = 0;
        synchronized (this) {
            long now = nanoTime.getAsLong();
            atInfo = !infoLogged || now - lastInfoNanos >= intervalNanos;
            if (atInfo) {
                infoLogged = true;
                lastInfoNanos = now;
                sentToDebug = debugSinceInfo;
                debugSinceInfo = 0;
            } else {
                debugSinceInfo++;
            }
        }
        if (!atInfo) {
            logger.debug(message);
        } else if (sentToDebug == 0) {
            logger.info(message);
        } else {
            logger.info(message + " (" + sentToDebug + " more like it since the previous one, logged at debug)");
        }
    }
}
