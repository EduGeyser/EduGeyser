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
import org.spongepowered.configurate.interfaces.InterfaceDefaultOptions;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NetherNetAddressingTest {

    private final GeyserLogger logger = mock(GeyserLogger.class);

    private static GeyserConfig.SignalingConfig config(String yaml) throws Exception {
        return YamlConfigurationLoader.builder()
                .source(() -> new BufferedReader(new StringReader(yaml)))
                .defaultOptions(InterfaceDefaultOptions::addTo)
                .build().load().get(GeyserConfig.SignalingConfig.class);
    }

    private NetherNetAddressing read(String yaml, Map<String, String> properties) throws Exception {
        return NetherNetAddressing.read(config(yaml), false, properties::get, logger);
    }

    @Test
    void discoveryAsksUpstreamsServersByDefault() throws Exception {
        NetherNetAddressing addressing = read("", Map.of());

        assertEquals(List.of(), addressing.advertised());
        assertEquals(List.of(InetSocketAddress.createUnresolved("stun.cloudflare.com", 3478),
                InetSocketAddress.createUnresolved("stun.l.google.com", 19302)), addressing.stunServers());
        assertTrue(addressing.stunDiscovery());
    }

    @Test
    void advertisedAddressesTurnDiscoveryOff() throws Exception {
        NetherNetAddressing addressing = read("""
                advertise-addresses: ["1.2.3.4", " play.example.com ", "[2606:4700::1]:19133", "1.2.3.4"]
                """, Map.of("geyserStunServers", "stun:stun.example.com"));

        assertEquals(List.of(new InetSocketAddress(InetAddress.getByName("1.2.3.4"), 0),
                InetSocketAddress.createUnresolved("play.example.com", 0),
                new InetSocketAddress(InetAddress.getByName("2606:4700::1"), 19133)), addressing.advertised());
        assertFalse(addressing.stunDiscovery());
        verify(logger).warning(contains("\"advertise-addresses\" is set"));
    }

    @Test
    void discoveryOffAsksNoServer() throws Exception {
        assertFalse(read("stun-discovery: false", Map.of()).stunDiscovery());
    }

    @Test
    void relaysInFrontOfTheIcePortTurnDiscoveryOff() throws Exception {
        assertFalse(NetherNetAddressing.read(config(""), true, name -> null, logger).stunDiscovery());
    }

    @Test
    void thePropertyReplacesTheConfiguredList() throws Exception {
        NetherNetAddressing addressing = read("advertise-addresses: [\"1.2.3.4\"]",
                Map.of("geyserAdvertiseAddresses", "5.6.7.8:19133, "));

        assertEquals(List.of(new InetSocketAddress(InetAddress.getByName("5.6.7.8"), 19133)), addressing.advertised());
    }

    @Test
    void anEmptyPropertyKeepsTheConfiguredList() throws Exception {
        NetherNetAddressing addressing = read("advertise-addresses: [\"1.2.3.4\"]",
                Map.of("geyserAdvertiseAddresses", " , "));

        assertEquals(List.of(new InetSocketAddress(InetAddress.getByName("1.2.3.4"), 0)), addressing.advertised());
    }

    @Test
    void stunServersFromThePropertyReplaceTheDefaults() throws Exception {
        assertEquals(List.of(InetSocketAddress.createUnresolved("stun.example.com", 3478)),
                read("", Map.of("geyserStunServers", "stun:stun.example.com")).stunServers());
        assertFalse(read("", Map.of("geyserStunServers", "")).stunDiscovery(), "an empty list asks none");
    }

    @Test
    void anInvalidEntryNamesTheSetting() {
        IllegalArgumentException special = assertThrows(IllegalArgumentException.class,
                () -> read("advertise-addresses: [\"198.51.100.1\"]", Map.of()));
        assertTrue(special.getMessage().startsWith("Invalid entry in \"advertise-addresses\""), special.getMessage());

        IllegalArgumentException property = assertThrows(IllegalArgumentException.class,
                () -> read("", Map.of("geyserAdvertiseAddresses", "[::1")));
        assertTrue(property.getMessage().startsWith("Invalid entry in -DgeyserAdvertiseAddresses"), property.getMessage());

        IllegalArgumentException stun = assertThrows(IllegalArgumentException.class,
                () -> read("", Map.of("geyserStunServers", "turn:turn.example.com")));
        assertTrue(stun.getMessage().startsWith("Invalid entry in -DgeyserStunServers"), stun.getMessage());
    }

    @Test
    void moreThanNetworkMsLimitFails() {
        String entries = String.join(", ", IntStream.rangeClosed(1, 33).mapToObj(i -> "\"1.2.3." + i + "\"").toList());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> read("advertise-addresses: [" + entries + "]", Map.of()));
        assertTrue(e.getMessage().contains("at most 32"), e.getMessage());
    }
}
