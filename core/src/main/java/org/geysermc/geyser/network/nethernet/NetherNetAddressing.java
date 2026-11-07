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

import io.github.sendablemetatype.netty.channel.nethernet.config.NetherChannelOption;
import io.github.sendablemetatype.netty.channel.nethernet.ice.AdvertisedAddresses;
import io.github.sendablemetatype.netty.channel.nethernet.ice.StunObserver;
import io.netty.bootstrap.ServerBootstrap;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.configuration.GeyserConfig;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * What the NetherNet endpoint tells joining players about its own addresses, read from
 * "advertise-addresses", "stun-discovery" and their system properties. NetworkM applies it to the
 * answers of every join source. That matters most for join codes and connection IDs: their joins
 * come over Xbox signaling with no Host header, so behind a router or in a container only an
 * advertised or a discovered address gives those players a public candidate.
 *
 * @param advertised  the advertised addresses, IP literals parsed and names left unresolved for
 *                    NetworkM to look up when the endpoint binds, port 0 standing for the ICE port
 * @param stunServers the STUN servers discovery asks from the ICE port, empty when discovery is off
 */
record NetherNetAddressing(List<InetSocketAddress> advertised, List<InetSocketAddress> stunServers) {

    /**
     * Asked unless -DgeyserStunServers says otherwise: upstream Geyser's two, plus Nextcloud's, an
     * operator outside the US. Three operators, as any of them may be blocked where a server runs.
     * All three answered a binding request on 2026-11-07.
     */
    static final List<String> DEFAULT_STUN_SERVERS = List.of("stun:stun.cloudflare.com:3478",
            "stun:stun.l.google.com:19302", "stun:stun.nextcloud.com:443");

    private static final String LOG_PREFIX = "[Nethernet] ";
    private static final String ADVERTISE_PROPERTY = "geyserAdvertiseAddresses";
    private static final String STUN_SERVERS_PROPERTY = "geyserStunServers";

    /**
     * Reads and checks the settings before the endpoint binds, so a mistake stops the start with a
     * message that names the setting. -DgeyserAdvertiseAddresses (comma separated) replaces the
     * configured list when it names any address; -DgeyserStunServers (comma separated
     * {@code stun:host[:port]} URLs) replaces {@link #DEFAULT_STUN_SERVERS}, and empty asks none.
     * <p>
     * Discovery runs only with "stun-discovery" on, no advertised address and no PROXY protocol
     * relays in front of the ICE port: an advertised address already says what STUN would find,
     * and behind relays STUN would find this host, which players cannot reach. NetworkM refuses
     * either combination at bind.
     *
     * @param config       the signaling settings
     * @param mediaRelayed whether PROXY protocol relays front the ICE port
     * @param properties   looks up a system property, null when it is not set
     * @param logger       where overrides and ignored settings are reported
     * @return the addressing to bind with
     * @throws IllegalArgumentException if an entry is invalid or there are too many
     */
    static NetherNetAddressing read(GeyserConfig.SignalingConfig config, boolean mediaRelayed,
                                    UnaryOperator<String> properties, GeyserLogger logger) {
        String source = "\"advertise-addresses\"";
        List<String> entries = entries(config.advertiseAddresses().stream());
        String property = properties.apply(ADVERTISE_PROPERTY);
        if (property != null) {
            List<String> fromProperty = entries(Arrays.stream(property.split(",")));
            if (!fromProperty.isEmpty()) {
                logger.info(LOG_PREFIX + "Advertised addresses set from system property: " + String.join(", ", fromProperty));
                entries = fromProperty;
                source = "-D" + ADVERTISE_PROPERTY;
            }
        }
        List<InetSocketAddress> advertised;
        try {
            advertised = AdvertisedAddresses.parse(entries, 0);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid entry in " + source + ": " + e.getMessage(), e);
        }
        if (advertised.size() > AdvertisedAddresses.MAX_ENDPOINTS) {
            throw new IllegalArgumentException(source + " lists " + advertised.size() + " addresses, at most "
                    + AdvertisedAddresses.MAX_ENDPOINTS + " are allowed");
        }

        boolean discovery = config.stunDiscovery() && advertised.isEmpty() && !mediaRelayed;
        String stunProperty = properties.apply(STUN_SERVERS_PROPERTY);
        if (!discovery && stunProperty != null) {
            logger.warning(LOG_PREFIX + "-D" + STUN_SERVERS_PROPERTY + " is ignored, as "
                    + (!config.stunDiscovery() ? "\"stun-discovery\" is off"
                    : !advertised.isEmpty() ? source + " is set"
                    : "PROXY protocol relays front the ICE port"));
        }
        return new NetherNetAddressing(advertised, discovery ? stunServers(stunProperty, logger) : List.of());
    }

    /** Whether STUN discovery runs. */
    boolean stunDiscovery() {
        return !stunServers.isEmpty();
    }

    /** Sets the endpoint's advertised addresses and STUN servers. */
    void applyTo(ServerBootstrap bootstrap) {
        bootstrap.option(NetherChannelOption.NETHER_SERVER_ADVERTISED_ADDRESSES, advertised)
                .option(NetherChannelOption.NETHER_SERVER_STUN_SERVERS, stunServers);
    }

    private static List<InetSocketAddress> stunServers(String property, GeyserLogger logger) {
        List<String> urls = DEFAULT_STUN_SERVERS;
        if (property != null) {
            urls = entries(Arrays.stream(property.split(",")));
            logger.info(LOG_PREFIX + "STUN servers set from system property: "
                    + (urls.isEmpty() ? "none" : String.join(", ", urls)));
        }
        try {
            return urls.stream().map(StunObserver::parseServer).distinct().toList();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid entry in -D" + STUN_SERVERS_PROPERTY + ": " + e.getMessage(), e);
        }
    }

    private static List<String> entries(Stream<String> values) {
        return values.filter(Objects::nonNull).map(String::trim).filter(entry -> !entry.isEmpty()).distinct().toList();
    }
}
