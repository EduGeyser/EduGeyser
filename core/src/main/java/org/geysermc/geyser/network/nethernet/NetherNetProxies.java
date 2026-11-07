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

import io.github.sendablemetatype.netty.util.address.ProxyList;
import io.github.sendablemetatype.netty.util.address.TrustedProxies;
import org.geysermc.geyser.GeyserLogger;
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

    private static final String HAPROXY = "haproxy-protocol-whitelisted-ips";
    private static final String FORWARDED_FOR = "signaling-trusted-proxies";
    private static final String RELAYS = "webrtc-proxy-protocol-relays";
    private static final String UNREAD = " (each entry is an address or range that does not parse, a name that does not"
            + " resolve, or a list that could not be downloaded or is empty)";

    /**
     * Why NetherNet does not start when the relay list names relays and none of them could be
     * read, which the endpoint refuses to bind with
     * ({@link io.github.sendablemetatype.netty.channel.nethernet.UnreadableRelaysException}).
     */
    static final String UNREADABLE_RELAYS = where(RELAYS) + " lists relays, but none of them could be read" + UNREAD
            + ", and without them players would reach this server directly. Fix the list, or empty it to let players"
            + " connect without relays.";

    /**
     * Parses the three lists. Blocks while names resolve and https:// lists download, so this
     * runs on the starting thread, never on an I/O thread.
     * <p>
     * NetworkM skips an entry it cannot read, so a configured list can come back empty. For the
     * relays that would fail open: the ICE port would take players directly and announce this
     * host's addresses. The endpoint therefore refuses to bind with relays none of which could be
     * read, which the start reports with {@link #UNREADABLE_RELAYS}. A signaling list that comes
     * back empty trusts no header, which fails closed by itself, so it only warns.
     *
     * @param logger takes the warnings for signaling lists none of whose entries could be read
     * @throws IllegalArgumentException if a list is refused, as one fetched over plain HTTP is; the
     *                                  message names the setting
     */
    static NetherNetProxies parse(GeyserConfig.AdvancedBedrockConfig config, GeyserLogger logger) {
        ProxyList proxyProtocol = parse(HAPROXY, config.haproxyProtocolWhitelistedIps());
        ProxyList forwardedFor = parse(FORWARDED_FOR, config.signalingTrustedProxies());
        ProxyList relays = parse(RELAYS, config.webrtcProxyProtocolRelays());
        warnIfUnread(logger, HAPROXY, proxyProtocol, "PROXY protocol header");
        warnIfUnread(logger, FORWARDED_FOR, forwardedFor, "X-Forwarded-For header");
        return new NetherNetProxies(new TrustedProxies(proxyProtocol, forwardedFor),
                new TrustedProxies(relays, ProxyList.NONE));
    }

    private static ProxyList parse(String key, List<String> entries) {
        try {
            return TrustedProxies.parseList(entries);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where(key) + ": " + e.getMessage(), e);
        }
    }

    private static void warnIfUnread(GeyserLogger logger, String key, ProxyList parsed, String header) {
        if (parsed.allUnreadable()) {
            logger.warning(where(key) + " lists proxies, but none of them could be read" + UNREAD + ", so HTTP "
                    + "signaling trusts no " + header + " and players behind them show the proxy's address.");
        }
    }

    private static String where(String key) {
        return "\"" + key + "\" under \"advanced.bedrock\"";
    }

    /**
     * @return Whether any proxy in front of HTTP signaling is trusted, which
     *         "prefer-signaling-proxy-address" needs to do anything
     */
    boolean signalingProxied() {
        return !signaling.proxyProtocol().ranges().isEmpty() || !signaling.forwardedFor().ranges().isEmpty();
    }

    /** @return Whether PROXY protocol relays front the ICE port, which rules out STUN discovery */
    boolean relayed() {
        return !relays.proxyProtocol().ranges().isEmpty();
    }
}
