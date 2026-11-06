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

import io.github.sendablemetatype.netty.util.address.IpRangeSet;
import io.github.sendablemetatype.netty.util.address.TrustedProxies;
import org.geysermc.geyser.configuration.GeyserConfig;

import java.util.List;

/**
 * The proxies that may speak for a player, by role, parsed from the config once per start. HTTP
 * signaling trusts PROXY protocol headers from "haproxy-protocol-whitelisted-ips" and
 * X-Forwarded-For from "signaling-trusted-proxies"; the shared ICE port reads PROXY protocol v2
 * from "webrtc-proxy-protocol-relays". The roles stay apart: a proxy trusted on HTTP signaling
 * gains no relay trust on the ICE port, and a relay none on HTTP signaling.
 *
 * @param signaling The proxies HTTP signaling trusts, in both of its roles
 * @param relays    The relays in front of the ICE port, in the PROXY protocol role only, as the
 *                  endpoint reads its relays from there
 */
record NetherNetProxies(TrustedProxies signaling, TrustedProxies relays) {

    /**
     * Parses the three lists. Blocks while names resolve and https:// lists download, so this
     * runs on the starting thread, never on an I/O thread.
     *
     * @throws IllegalArgumentException if a list is refused, as one fetched over plain HTTP is;
     *                                  the message names the setting
     */
    static NetherNetProxies parse(GeyserConfig.AdvancedBedrockConfig config) {
        IpRangeSet proxyProtocol = parse("haproxy-protocol-whitelisted-ips", config.haproxyProtocolWhitelistedIps());
        IpRangeSet forwardedFor = parse("signaling-trusted-proxies", config.signalingTrustedProxies());
        IpRangeSet relays = parse("webrtc-proxy-protocol-relays", config.webrtcProxyProtocolRelays());
        return new NetherNetProxies(new TrustedProxies(proxyProtocol, forwardedFor),
                new TrustedProxies(relays, IpRangeSet.empty()));
    }

    private static IpRangeSet parse(String key, List<String> entries) {
        try {
            return TrustedProxies.parseList(entries);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("\"" + key + "\" under \"advanced.bedrock\": " + e.getMessage(), e);
        }
    }

    /**
     * @return Whether any proxy in front of HTTP signaling is trusted, which
     *         "prefer-signaling-proxy-address" needs to do anything
     */
    boolean signalingProxied() {
        return !signaling.proxyProtocol().isEmpty() || !signaling.forwardedFor().isEmpty();
    }
}
