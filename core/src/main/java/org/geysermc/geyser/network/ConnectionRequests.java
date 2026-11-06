/*
 * Copyright (c) 2019-2026 GeyserMC. http://geysermc.org
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

package org.geysermc.geyser.network;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.event.connection.ConnectionRequestEvent;
import org.geysermc.geyser.text.GeyserLocale;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The pre-authentication gate every inbound connection passes, whichever transport carried it.
 */
public final class ConnectionRequests {

    private static final AtomicInteger ATTEMPTS = new AtomicInteger();

    private ConnectionRequests() {
    }

    /**
     * @return how many connections asked this gate since the process started, refused ones included
     */
    public static int attempts() {
        return ATTEMPTS.get();
    }

    /**
     * Fires {@link ConnectionRequestEvent} and reports whether the connection may proceed.
     * <p>
     * Extensions use this to refuse a peer by address, before any session exists, so every transport
     * has to ask before it creates one.
     *
     * @param geyser        The running instance
     * @param clientAddress The address the connection came from
     * @param proxyAddress  The proxy or relay that declared it, or null when nothing stood in between
     * @param source        Where the transport learned the address, named in the debug line, or null
     * @return true when the connection may proceed
     */
    public static boolean accept(GeyserImpl geyser, InetSocketAddress clientAddress,
                                 @Nullable InetSocketAddress proxyAddress, @Nullable String source) {
        ATTEMPTS.incrementAndGet();
        boolean logIps = geyser.config().logPlayerIpAddresses();
        String ip = logIps ? String.valueOf(clientAddress) : "<IP address withheld>";

        ConnectionRequestEvent requestEvent = new ConnectionRequestEvent(clientAddress, proxyAddress);
        geyser.eventBus().fire(requestEvent);
        if (requestEvent.isCancelled()) {
            geyser.getLogger().debug("Connection request from " + ip + " was cancelled using the API!");
            return false;
        }

        String via = proxyAddress == null ? "" : "via " + (logIps ? proxyAddress : "<Proxy IP address withheld>");
        String detail = String.join(" ", source == null ? "" : source, via).trim();
        geyser.getLogger().debug(GeyserLocale.getLocaleStringLog("geyser.network.attempt_connect", ip)
                + (detail.isEmpty() ? "" : " (" + detail + ")"));
        return true;
    }
}
