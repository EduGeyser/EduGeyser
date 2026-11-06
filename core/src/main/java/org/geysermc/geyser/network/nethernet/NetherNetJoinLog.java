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
import io.github.sendablemetatype.netty.channel.nethernet.address.SourcedAddress;
import io.github.sendablemetatype.netty.channel.nethernet.signaling.http.TlsContextSelector;
import io.netty.channel.Channel;
import io.netty.handler.ssl.DelegatingSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.geyser.GeyserLogger;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Logs each NetherNet join with the signaling it came over: Xbox RPC, plain
 * HTTP or HTTPS.
 *
 * NetworkM keeps to itself whether an HTTP signaling connection speaks TLS,
 * so HTTPS joins are told apart by address. {@link #observe} wraps the HTTP
 * signaling TLS selector, and every connection whose TLS handshake completes
 * is kept by its client address. An HTTP join's signaled address is the
 * client address of the connection its offer came over, or, when a trusted
 * proxy declared the player, that connection is the proxy's and its address
 * is the signaled address's {@code via}. The endpoint sets up the join's
 * channel before it answers, while that connection still waits. TCP allows
 * one connection per pair of addresses, so a TLS connection still open from
 * that address is the one the offer came over, and the join came over
 * HTTPS. The only case this can get wrong is a client holding connections
 * from one IP and port to two of the server's addresses at once, one of
 * them TLS. Behind a proxy that reads HTTP itself (X-Forwarded-For), HTTPS
 * describes the proxy's connection, not the player's.
 *
 * A TLS connection that closed is kept a little longer: a join from its
 * address is then logged as not told apart, since its client either left the
 * connection before its answer, which ends the join, or reused the address
 * for a plain HTTP connection since.
 */
final class NetherNetJoinLog {

    private static final String LOG_PREFIX = "[Nethernet] ";
    /** How long a join from the address of a closed TLS connection is logged as not told apart. */
    private static final Duration KEPT_AFTER_CLOSE = Duration.ofSeconds(10);

    /** The signaling a join came over. */
    enum Signaling {
        XBOX_RPC("Xbox RPC", ""),
        HTTP("plain HTTP", ""),
        HTTPS("HTTPS", ""),
        /** HTTP from the address of a TLS connection that closed just before; see the class comment. */
        HTTP_OR_HTTPS("plain HTTP or HTTPS",
                " (not told apart: an HTTPS connection from this address closed before the join was set up)"),
        /** A join source EduGeyser does not run, such as LAN. */
        OTHER("another source", "");

        private final String label;
        private final String note;

        Signaling(String label, String note) {
            this.label = label;
            this.note = note;
        }
    }

    private final GeyserLogger logger;
    private final boolean logAddresses;
    private final Duration keptAfterClose;
    private final NetherNetRateLimitedLog handshakeFailures;
    /** Client addresses of TLS connections, each with its connection, so a reused address is not dropped early. */
    private final Map<InetSocketAddress, Channel> tlsConnections = new ConcurrentHashMap<>();

    NetherNetJoinLog(GeyserLogger logger, boolean logAddresses) {
        this(logger, logAddresses, KEPT_AFTER_CLOSE);
    }

    NetherNetJoinLog(GeyserLogger logger, boolean logAddresses, Duration keptAfterClose) {
        this.logger = logger;
        this.logAddresses = logAddresses;
        this.keptAfterClose = keptAfterClose;
        this.handshakeFailures = new NetherNetRateLimitedLog(logger);
    }

    /**
     * Wraps a TLS selector so that every TLS connection it serves is
     * remembered once its handshake completes, and a failed handshake is
     * logged through a {@link NetherNetRateLimitedLog}.
     */
    TlsContextSelector observe(TlsContextSelector selector) {
        return serverName -> {
            SslContext context = selector.select(serverName);
            return context == null ? null : new Observed(context, serverName);
        };
    }

    /** Logs a join whose channel is being set up; called from its initializer. */
    void joined(NetherNetChildChannel child) {
        SourcedAddress signaled = child.signaledAddress();
        Signaling signaling = signalingOf(signaled);
        String from = signaling == Signaling.XBOX_RPC ? ""
                : " from " + (logAddresses ? describe(signaled.address()) : "<IP address withheld>");
        String via = switch (signaled.source()) {
            case SIGNALING_PROXY_PROTOCOL -> " through a proxy at " + describeProxy(signaled) + " (PROXY protocol)";
            case SIGNALING_FORWARDED_FOR -> " through a proxy at " + describeProxy(signaled) + " (X-Forwarded-For)";
            default -> "";
        };
        logger.info(LOG_PREFIX + "Join " + child.attr(NetherNetChildChannel.CONNECTION_ID).get()
                + " signaled over " + signaling.label + from + via + signaling.note);
    }

    Signaling signalingOf(SourcedAddress signaled) {
        return switch (signaled.source()) {
            // Xbox joins carry no address
            case UNKNOWN -> Signaling.XBOX_RPC;
            case SIGNALING, SIGNALING_PROXY_PROTOCOL, SIGNALING_FORWARDED_FOR -> {
                // The connection the offer came over: the player's own, or the proxy's that declared the player
                Channel tls = tlsConnections.get(signaled.via() != null ? signaled.via() : signaled.address());
                yield tls == null ? Signaling.HTTP : tls.isOpen() ? Signaling.HTTPS : Signaling.HTTP_OR_HTTPS;
            }
            default -> Signaling.OTHER;
        };
    }

    private void handshakeDone(Future<Channel> handshake, @Nullable String serverName) {
        if (handshake.isSuccess()) {
            Channel channel = handshake.getNow();
            if (channel.remoteAddress() instanceof InetSocketAddress client) {
                tlsConnections.put(client, channel);
                channel.closeFuture().addListener(closed -> forgetLater(client, channel));
            }
        } else if (handshake.cause() instanceof SSLException failure) {
            // A client that rejects the certificate ends up here. NetworkM
            // hands the handler no peer address, so only the name it asked
            // for tells such clients apart. Anyone can fail handshakes
            // before any join throttle, hence the limit.
            handshakeFailures.log(LOG_PREFIX + "TLS handshake for HTTP signaling failed ("
                    + (serverName == null ? "no server name" : "server name " + serverName) + "): "
                    + failure.getMessage());
        }
    }

    private void forgetLater(InetSocketAddress client, Channel channel) {
        try {
            channel.eventLoop().schedule(() -> tlsConnections.remove(client, channel),
                    keptAfterClose.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            tlsConnections.remove(client, channel);
        }
    }

    private static String describe(InetSocketAddress address) {
        return address.getAddress().getHostAddress() + ":" + address.getPort();
    }

    private String describeProxy(SourcedAddress signaled) {
        return logAddresses && signaled.via() != null ? describe(signaled.via()) : "<Proxy IP address withheld>";
    }

    /** Serves the selected context and watches the handshake of every handler made from it. */
    private final class Observed extends DelegatingSslContext {
        private final @Nullable String serverName;

        Observed(SslContext context, @Nullable String serverName) {
            super(context);
            this.serverName = serverName;
        }

        @Override
        protected void initEngine(SSLEngine engine) {
        }

        @Override
        protected void initHandler(SslHandler handler) {
            // The handshake completes with the handler's channel, on its loop
            FutureListener<Channel> listener = handshake -> handshakeDone(handshake, serverName);
            handler.handshakeFuture().addListener(listener);
        }
    }
}
