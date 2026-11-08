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

import io.github.sendablemetatype.netty.channel.nethernet.address.AddressSource;
import io.github.sendablemetatype.netty.channel.nethernet.address.SourcedAddress;
import io.github.sendablemetatype.netty.channel.nethernet.signaling.Join;
import io.github.sendablemetatype.netty.channel.nethernet.signaling.http.TlsHandshakeFailure;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.network.nethernet.NetherNetJoinLog.Signaling;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class NetherNetJoinLogTest {

    private static final InetSocketAddress CLIENT = new InetSocketAddress("198.51.100.9", 50000);
    private static final SourcedAddress SIGNALED = new SourcedAddress(CLIENT, AddressSource.SIGNALING, null);

    private final GeyserLogger logger = mock(GeyserLogger.class);

    @Test
    void anHttpJoinIsHttpsOnlyWhenItsConnectionSpokeTls() {
        assertEquals(Signaling.HTTPS, NetherNetJoinLog.signalingOf(addressed(true), SIGNALED));
        assertEquals(Signaling.HTTP, NetherNetJoinLog.signalingOf(addressed(false), SIGNALED));
    }

    @Test
    void joinsWithoutAnAddressCameOverXboxSignalingAndOthersFromAnotherSource() {
        assertEquals(Signaling.XBOX_RPC, NetherNetJoinLog.signalingOf(null, SourcedAddress.unknown()));
        assertEquals(Signaling.OTHER, NetherNetJoinLog.signalingOf(null,
                new SourcedAddress(CLIENT, AddressSource.SIGNALING_LAN, null)));
    }

    @Test
    void theJoinLineNamesTheAddressOnlyWhenAddressesAreLogged() {
        new NetherNetJoinLog(logger, true).joined("1", addressed(true), SIGNALED);
        new NetherNetJoinLog(logger, false).joined("2", addressed(false), SIGNALED);

        verify(logger).debug("[Nethernet] Join 1 signaled over HTTPS from 198.51.100.9:50000");
        verify(logger).debug("[Nethernet] Join 2 signaled over plain HTTP from <IP address withheld>");
    }

    @Test
    void theJoinLineNamesTheProxyOnlyWhenAddressesAreLogged() {
        InetSocketAddress proxy = new InetSocketAddress("192.0.2.4", 40000);
        new NetherNetJoinLog(logger, true).joined("1", addressed(true),
                new SourcedAddress(CLIENT, AddressSource.SIGNALING_PROXY_PROTOCOL, proxy));
        new NetherNetJoinLog(logger, false).joined("2", addressed(false),
                new SourcedAddress(CLIENT, AddressSource.SIGNALING_FORWARDED_FOR, proxy));

        verify(logger).debug("[Nethernet] Join 1 signaled over HTTPS from 198.51.100.9:50000"
                + " through a proxy at 192.0.2.4:40000 (PROXY protocol)");
        verify(logger).debug("[Nethernet] Join 2 signaled over plain HTTP from <IP address withheld>"
                + " through a proxy at <Proxy IP address withheld> (X-Forwarded-For)");
    }

    @Test
    void aFailedHandshakeNamesTheClientOnlyWhenAddressesAreLogged() {
        SSLHandshakeException rejected = new SSLHandshakeException("Received fatal alert: certificate_unknown");

        new NetherNetJoinLog(logger, true).tlsHandshakeFailed(new TlsHandshakeFailure(CLIENT, null, rejected));
        new NetherNetJoinLog(logger, false).tlsHandshakeFailed(
                new TlsHandshakeFailure(CLIENT, "play.example.com", rejected));

        verify(logger).info("[Nethernet] TLS handshake for HTTP signaling from 198.51.100.9:50000 failed"
                + " (no server name): Received fatal alert: certificate_unknown");
        verify(logger).info("[Nethernet] TLS handshake for HTTP signaling from <IP address withheld> failed"
                + " (server name play.example.com): Received fatal alert: certificate_unknown");
    }

    @Test
    void failedHandshakesAreRateLimitedAndClosedConnectionsAreNotLogged() {
        NetherNetJoinLog log = new NetherNetJoinLog(logger, true);
        SSLHandshakeException rejected = new SSLHandshakeException("Received fatal alert: bad_certificate");

        log.tlsHandshakeFailed(new TlsHandshakeFailure(CLIENT, null, new ClosedChannelException()));
        verify(logger, never()).info(anyString());

        log.tlsHandshakeFailed(new TlsHandshakeFailure(CLIENT, null, rejected));
        log.tlsHandshakeFailed(new TlsHandshakeFailure(CLIENT, null, rejected));
        verify(logger).info(anyString());
        verify(logger).debug(anyString());
    }

    private static Join.Addressed addressed(boolean tls) {
        return new Join.Addressed("203.0.113.1:19132", 19132, false, tls, null);
    }
}
