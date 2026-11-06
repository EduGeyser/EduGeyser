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

import io.github.sendablemetatype.netty.channel.nethernet.NetherNetChildChannel;
import io.github.sendablemetatype.netty.channel.nethernet.address.AddressSource;
import io.github.sendablemetatype.netty.channel.nethernet.address.SourcedAddress;
import io.github.sendablemetatype.netty.channel.nethernet.signaling.Join;
import io.github.sendablemetatype.netty.channel.nethernet.signaling.http.TlsHandshakeFailure;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.geyser.GeyserLogger;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;

/**
 * Logs each NetherNet join with the signaling it came over (Xbox RPC, plain
 * HTTP or HTTPS), and failed TLS handshakes on HTTP signaling. Both honor
 * log-player-ip-addresses.
 */
final class NetherNetJoinLog {

    private static final String LOG_PREFIX = "[Nethernet] ";

    /** The signaling a join came over. */
    enum Signaling {
        XBOX_RPC("Xbox RPC"),
        HTTP("plain HTTP"),
        HTTPS("HTTPS"),
        /** A join source EduGeyser does not run, such as LAN. */
        OTHER("another source");

        private final String label;

        Signaling(String label) {
            this.label = label;
        }
    }

    private final GeyserLogger logger;
    private final boolean logAddresses;
    private final NetherNetRateLimitedLog handshakeFailures;

    NetherNetJoinLog(GeyserLogger logger, boolean logAddresses) {
        this.logger = logger;
        this.logAddresses = logAddresses;
        this.handshakeFailures = new NetherNetRateLimitedLog(logger);
    }

    /** Logs a join whose channel is being set up; called from its initializer. */
    void joined(NetherNetChildChannel child) {
        joined(child.attr(NetherNetChildChannel.CONNECTION_ID).get(), child.addressed().orElse(null),
                child.signaledAddress());
    }

    void joined(String connectionId, Join.@Nullable Addressed addressed, SourcedAddress signaled) {
        Signaling signaling = signalingOf(addressed, signaled);
        String from = signaling == Signaling.XBOX_RPC ? "" : " from " + describe(signaled.address());
        logger.info(LOG_PREFIX + "Join " + connectionId + " signaled over " + signaling.label + from);
    }

    /**
     * Logs a TLS handshake on HTTP signaling that failed with a TLS error,
     * as when a client rejects the certificate. Anyone can fail handshakes
     * before any join throttle, hence the limit. A connection that closed
     * before its handshake finished is not logged: scanners do that.
     */
    void tlsHandshakeFailed(TlsHandshakeFailure failure) {
        if (!(failure.cause() instanceof SSLException cause)) {
            return;
        }
        handshakeFailures.log(LOG_PREFIX + "TLS handshake for HTTP signaling from " + describe(failure.client())
                + " failed (" + (failure.serverName() == null ? "no server name" : "server name " + failure.serverName())
                + "): " + cause.getMessage());
    }

    static Signaling signalingOf(Join.@Nullable Addressed addressed, SourcedAddress signaled) {
        if (addressed != null) {
            // Only HTTP signaling sees what the client reached
            return addressed.tls() ? Signaling.HTTPS : Signaling.HTTP;
        }
        // Xbox joins carry no address
        return signaled.source() == AddressSource.UNKNOWN ? Signaling.XBOX_RPC : Signaling.OTHER;
    }

    private String describe(InetSocketAddress address) {
        return logAddresses ? address.getAddress().getHostAddress() + ":" + address.getPort() : "<IP address withheld>";
    }
}
