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
import org.geysermc.geyser.configuration.GeyserConfig;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NetherNetProxiesTest {

    private static final InetSocketAddress HAPROXY = new InetSocketAddress("10.0.0.1", 40000);
    private static final InetSocketAddress FORWARDER = new InetSocketAddress("10.0.0.2", 40000);
    private static final InetSocketAddress RELAY = new InetSocketAddress("10.0.0.3", 40000);
    /** Entries NetworkM skips: a list whose server is down, and a range that does not parse. */
    private static final List<String> UNREADABLE = List.of("https://127.0.0.1:1/proxies.txt", "10.0.0.0/99");

    private final GeyserLogger logger = mock(GeyserLogger.class);

    @Test
    void eachListTrustsItsProxiesInItsOwnRoleOnly() {
        NetherNetProxies proxies = NetherNetProxies.parse(config(List.of("10.0.0.1"), List.of("10.0.0.2"), List.of("10.0.0.3")), logger);

        assertTrue(proxies.signaling().trustsProxyProtocol(HAPROXY));
        assertFalse(proxies.signaling().trustsForwardedFor(HAPROXY));
        assertTrue(proxies.signaling().trustsForwardedFor(FORWARDER));
        assertFalse(proxies.signaling().trustsProxyProtocol(FORWARDER));
        // A relay in front of the ICE port speaks for nobody on HTTP signaling
        assertFalse(proxies.signaling().isProxy(RELAY));

        assertTrue(proxies.relays().trustsProxyProtocol(RELAY));
        // and a signaling proxy gains no relay trust on the ICE port
        assertFalse(proxies.relays().isProxy(HAPROXY));
        assertFalse(proxies.relays().isProxy(FORWARDER));
        assertTrue(proxies.signalingProxied());
        verify(logger, never()).warning(anyString());
    }

    @Test
    void onlyARelayLeavesSignalingUnproxied() {
        NetherNetProxies proxies = NetherNetProxies.parse(config(List.of(), List.of(), List.of("10.0.0.3")), logger);

        assertFalse(proxies.signalingProxied());
    }

    @Test
    void aRefusedListNamesItsSetting() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
                NetherNetProxies.parse(config(List.of(), List.of(), List.of("http://example.com/relays.txt")), logger));

        assertTrue(refused.getMessage().startsWith("\"webrtc-proxy-protocol-relays\" under \"advanced.bedrock\""),
                refused.getMessage());
    }

    @Test
    void relaysNoneOfWhichCanBeReadRefuseToStart() {
        // Running without them would take players directly and announce this host's addresses
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
                NetherNetProxies.parse(config(List.of(), List.of(), UNREADABLE), logger));

        assertTrue(refused.getMessage().startsWith("\"webrtc-proxy-protocol-relays\" under \"advanced.bedrock\" lists relays"),
                refused.getMessage());
    }

    @Test
    void signalingListsNoneOfWhichCanBeReadTrustNobodyAndWarn() {
        NetherNetProxies proxies = NetherNetProxies.parse(config(UNREADABLE, UNREADABLE, List.of()), logger);

        assertFalse(proxies.signalingProxied());
        verify(logger).warning(startsWith("\"haproxy-protocol-whitelisted-ips\" under \"advanced.bedrock\" lists proxies"));
        verify(logger).warning(startsWith("\"signaling-trusted-proxies\" under \"advanced.bedrock\" lists proxies"));
    }

    private static GeyserConfig.AdvancedBedrockConfig config(List<String> haproxy, List<String> forwardedFor,
                                                             List<String> relays) {
        GeyserConfig.AdvancedBedrockConfig config = mock(GeyserConfig.AdvancedBedrockConfig.class);
        when(config.haproxyProtocolWhitelistedIps()).thenReturn(haproxy);
        when(config.signalingTrustedProxies()).thenReturn(forwardedFor);
        when(config.webrtcProxyProtocolRelays()).thenReturn(relays);
        return config;
    }
}
