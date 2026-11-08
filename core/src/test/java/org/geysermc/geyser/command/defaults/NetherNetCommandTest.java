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

package org.geysermc.geyser.command.defaults;

import io.github.sendablemetatype.netty.channel.nethernet.engine.IceEndpoint;
import io.github.sendablemetatype.netty.channel.nethernet.nxs.ProviderClient;
import io.github.sendablemetatype.netty.channel.nethernet.nxs.diagnostic.DiagnosticHostPolicy;
import io.github.sendablemetatype.netty.channel.nethernet.nxs.host.DiagnosticResult;
import io.github.sendablemetatype.netty.channel.nethernet.nxs.transport.AddressFamily;
import org.geysermc.geyser.network.nethernet.NetherNetServer.Announced;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Upstream Geyser's tests of its command's messages, with EduGeyser's sources and wording. */
class NetherNetCommandTest {
    private static final InetSocketAddress IPV4 = new InetSocketAddress("198.51.100.1", 19132);
    private static final InetSocketAddress IPV6 = new InetSocketAddress("2001:db8::1", 19133);

    @Test
    void ownAnswersListTheConfiguredAddresses() {
        assertEquals("HTTP and Xbox signaling advertise 198.51.100.1:19132, [2001:db8::1]:19133.",
            NetherNetCommand.announced(new Announced(19132, List.of(IPV4, IPV6), false, List.of()), true));
        // In the "nxs" mode HTTP signaling does not run, and Xbox signaling always does
        assertEquals("Xbox signaling advertises 198.51.100.1:19132.",
            NetherNetCommand.announced(new Announced(19132, List.of(IPV4), false, List.of()), false));
    }

    @Test
    void ownAnswersWithoutConfiguredAddressesSayWhatStunFound() {
        assertEquals("HTTP and Xbox signaling advertise this machine's own addresses, and 198.51.100.1:19132 found with STUN.",
            NetherNetCommand.announced(new Announced(19132, List.of(), true, List.of(IPV4)), true));
        assertEquals("HTTP and Xbox signaling advertise this machine's own addresses. STUN discovery has not found a public address yet.",
            NetherNetCommand.announced(new Announced(19132, List.of(), true, List.of()), true));
        assertEquals("HTTP and Xbox signaling advertise this machine's own addresses. STUN discovery is off.",
            NetherNetCommand.announced(new Announced(19132, List.of(), false, List.of()), true));
    }

    @Test
    void nxsListsWhatTheServiceConfirmed() {
        assertEquals("The signaling service publishes 198.51.100.1:19132, [2001:db8::1]:19133. Assisted joins are offered over IPv4 and IPv6.",
            NetherNetCommand.nxsPublished(new ProviderClient.PublishedEndpoints(List.of(IPV4, IPV6),
                Set.of(AddressFamily.IPV6, AddressFamily.IPV4))));
        assertEquals("The signaling service publishes 198.51.100.1:19132.",
            NetherNetCommand.nxsPublished(new ProviderClient.PublishedEndpoints(List.of(IPV4), Set.of())));
        assertEquals("The signaling service publishes no addresses.",
            NetherNetCommand.nxsPublished(new ProviderClient.PublishedEndpoints(List.of(), Set.of())));
        assertEquals("The signaling service has confirmed no addresses right now.",
            NetherNetCommand.nxsPublished(null));
    }

    @Test
    void iceSocketsSayWhereThePortIsOpenAndWhereItCouldNotBeOpened() {
        InetSocketAddress lan = new InetSocketAddress("192.168.1.5", 19132);
        InetSocketAddress global = new InetSocketAddress("2001:db8::7", 19132);
        IceEndpoint.BindFailure taken = new IceEndpoint.BindFailure(new InetSocketAddress("10.0.0.5", 19132),
            "Address already in use");
        IceEndpoint.BindFailure refused = new IceEndpoint.BindFailure(new InetSocketAddress("2001:db8::5", 19132),
            "Permission denied");

        assertEquals("The ICE port is open on UDP 192.168.1.5:19132, [2001:db8::7]:19132.",
            NetherNetCommand.iceSockets(new IceEndpoint.Sockets(List.of(lan, global), List.of())));
        assertEquals("The ICE port is open on UDP 192.168.1.5:19132. It could not be opened on 10.0.0.5:19132"
                + " (Address already in use), [2001:db8::5]:19132 (Permission denied).",
            NetherNetCommand.iceSockets(new IceEndpoint.Sockets(List.of(lan), List.of(taken, refused))));
        assertEquals("The ICE port is not open on any address. It could not be opened on 10.0.0.5:19132"
                + " (Address already in use).",
            NetherNetCommand.iceSockets(new IceEndpoint.Sockets(List.of(), List.of(taken))));
        assertEquals("The ICE port is not open on any address yet: it opens when the first player joins.",
            NetherNetCommand.iceSockets(IceEndpoint.Sockets.NONE));
    }

    @Test
    void countsConnectivityChecksByHowTheyEnded() {
        assertEquals("No connectivity checks since the last look.", NetherNetCommand.connectivityChecks(List.of()));
        assertEquals("Connectivity checks since the last look: 2 PONG_SENT, 1 HANDSHAKE_TIMEOUT.",
            NetherNetCommand.connectivityChecks(List.of(check(DiagnosticResult.Outcome.HANDSHAKE_TIMEOUT),
                check(DiagnosticResult.Outcome.PONG_SENT), check(DiagnosticResult.Outcome.PONG_SENT))));
    }

    private static DiagnosticResult check(DiagnosticResult.Outcome outcome) {
        return new DiagnosticResult("attempt", "K001", new DiagnosticHostPolicy.Endpoint(4, "00".repeat(16), 0, 1),
            null, outcome, null, null, 0, 0, 0, 0, 0);
    }
}
