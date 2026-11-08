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

package org.geysermc.geyser.dump;

import com.google.gson.Gson;
import io.github.sendablemetatype.netty.channel.nethernet.engine.IceEndpoint;
import io.github.sendablemetatype.netty.channel.nethernet.nxs.ProviderClient;
import io.github.sendablemetatype.netty.channel.nethernet.nxs.transport.AddressFamily;
import org.geysermc.geyser.configuration.GeyserConfig;
import org.geysermc.geyser.network.nethernet.NetherNetNxs;
import org.geysermc.geyser.network.nethernet.NetherNetServer;
import org.geysermc.geyser.text.AsteriskSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.interfaces.InterfaceDefaultOptions;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class DumpCensorTest {
    private static final String CONFIG = """
        haproxy-protocol-whitelisted-ips: ["198.51.100.1", "192.168.1.5", "0.0.0.0", "127.0.0.1"]
        signaling-trusted-proxies: ["198.51.100.1", "192.168.1.5", "0.0.0.0", "127.0.0.1"]
        webrtc-proxy-protocol-relays: ["198.51.100.1", "192.168.1.5", "0.0.0.0", "127.0.0.1"]
        """;

    @AfterEach
    void resetSensitivity() {
        AsteriskSerializer.showSensitive = false;
    }

    /** The same node the dump is built from, so the test cannot drift away from it. */
    private static CommentedConfigurationNode dumped() throws Exception {
        GeyserConfig.AdvancedBedrockConfig config = YamlConfigurationLoader.builder()
            .source(() -> new BufferedReader(new StringReader(CONFIG)))
            .defaultOptions(InterfaceDefaultOptions::addTo)
            .build().load().get(GeyserConfig.AdvancedBedrockConfig.class);

        CommentedConfigurationNode node = CommentedConfigurationNode.root(AsteriskSerializer.dumpOptions());
        node.set(config);
        return node;
    }

    @ParameterizedTest
    @ValueSource(strings = {"haproxy-protocol-whitelisted-ips", "signaling-trusted-proxies", "webrtc-proxy-protocol-relays"})
    void censorsPublicProxyAddresses(String key) throws Exception {
        // Local and wildcard entries stay readable, as with the bind address
        assertEquals(List.of("***", "192.168.1.5", "0.0.0.0", "127.0.0.1"),
            dumped().node(key).getList(String.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"haproxy-protocol-whitelisted-ips", "signaling-trusted-proxies", "webrtc-proxy-protocol-relays"})
    void showsThemInAFullDump(String key) throws Exception {
        AsteriskSerializer.showSensitive = true;
        assertEquals(List.of("198.51.100.1", "192.168.1.5", "0.0.0.0", "127.0.0.1"),
            dumped().node(key).getList(String.class));
    }

    @Test
    void censorsAdvertisedAddressesThatAreNotLocal() throws Exception {
        GeyserConfig.SignalingConfig config = YamlConfigurationLoader.builder()
            .source(() -> new BufferedReader(new StringReader("""
                advertise-addresses: ["198.51.100.1", "play.example.com", "[2001:db8::1]:19133", "192.168.1.5"]
                """)))
            .defaultOptions(InterfaceDefaultOptions::addTo)
            .build().load().get(GeyserConfig.SignalingConfig.class);

        CommentedConfigurationNode node = CommentedConfigurationNode.root(AsteriskSerializer.dumpOptions());
        node.set(config);
        // Names and entries with a port are censored whatever they point to
        assertEquals(List.of("***", "***", "***", "192.168.1.5"),
            node.node("advertise-addresses").getList(String.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void censorsTheCertificatePasswordEvenInAFullDump(boolean full) throws Exception {
        AsteriskSerializer.showSensitive = full;

        assertEquals("***", dumpedHttps("hunter2").node("password").getString());
        assertEquals("***", dumpedHttps("file:secrets/tls").node("password").getString());
        assertEquals("***", dumpedHttps("./secret-password").node("password").getString(), "a bare path may be the password");
        assertEquals("tls.p12", dumpedHttps("hunter2").node("certificate").getString());
        assertEquals("", dumpedHttps("").node("password").getString(), "an unset password stays visible as unset");
    }

    private static CommentedConfigurationNode dumpedHttps(String password) throws Exception {
        GeyserConfig.SignalingConfig config = YamlConfigurationLoader.builder()
            .source(() -> new BufferedReader(new StringReader("""
                builtin:
                  https:
                    certificate: tls.p12
                    password: "%s"
                """.formatted(password))))
            .defaultOptions(InterfaceDefaultOptions::addTo)
            .build().load().get(GeyserConfig.SignalingConfig.class);

        CommentedConfigurationNode node = CommentedConfigurationNode.root(AsteriskSerializer.dumpOptions());
        node.set(config);
        return node.node("builtin", "https");
    }

    @Test
    void censorsTheAnnouncedNetherNetAddresses() throws Exception {
        NetherNetServer.Announced announced = new NetherNetServer.Announced(19132,
            List.of(endpoint("1.2.3.4", 19132), endpoint("192.168.1.5", 19132)), true,
            List.of(endpoint("2606:4700::1", 19132)));

        DumpInfo.NetherNetInfo info = DumpInfo.NetherNetInfo.of(announced, null, null, null);
        assertEquals(List.of("***:19132", "192.168.1.5:19132"), info.advertised());
        assertEquals(List.of("[***]:19132"), info.discovered());

        AsteriskSerializer.showSensitive = true;
        assertEquals(List.of("[2606:4700::1]:19132"), DumpInfo.NetherNetInfo.of(announced, null, null, null).discovered());
    }

    @Test
    void censorsTheIcePortsPublicLocalAddressesButNotTheReasons() throws Exception {
        NetherNetServer.Announced announced = new NetherNetServer.Announced(19132, List.of(), false, List.of());
        IceEndpoint.Sockets sockets = new IceEndpoint.Sockets(
            List.of(endpoint("192.168.1.5", 19132), endpoint("203.0.113.9", 19132), endpoint("2001:db8::5", 19132)),
            List.of(new IceEndpoint.BindFailure(endpoint("10.0.0.5", 19132), "Address already in use"),
                new IceEndpoint.BindFailure(endpoint("198.51.100.7", 19132), "Permission denied")));

        // As with the announced addresses: a LAN address stays readable, a public one does not
        assertEquals("{\"bound\":[\"192.168.1.5:19132\",\"***:19132\",\"[***]:19132\"],"
                + "\"failed\":[{\"address\":\"10.0.0.5:19132\",\"reason\":\"Address already in use\"},"
                + "{\"address\":\"***:19132\",\"reason\":\"Permission denied\"}]}",
            new Gson().toJson(DumpInfo.NetherNetInfo.of(announced, null, sockets, null).iceSockets()));

        AsteriskSerializer.showSensitive = true;
        DumpInfo.NetherNetInfo.IceSockets full = DumpInfo.NetherNetInfo.of(announced, null, sockets, null).iceSockets();
        assertEquals(List.of("192.168.1.5:19132", "203.0.113.9:19132", "[2001:db8::5]:19132"), full.bound(),
            "a full dump shows them");
        assertEquals("198.51.100.7:19132", full.failed().getLast().address());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void censorsTheNxsTokenEvenInAFullDump(boolean full) throws Exception {
        // "dump full" only ever unmasks, which is why a secret cannot rely on the asterisk processor
        AsteriskSerializer.showSensitive = full;
        GeyserConfig.SignalingConfig config = YamlConfigurationLoader.builder()
            .source(() -> new BufferedReader(new StringReader("""
                mode: nxs
                nxs:
                  token: yaml-secret
                  endpoint: https://signal.example.net
                """)))
            .defaultOptions(InterfaceDefaultOptions::addTo)
            .build().load().get(GeyserConfig.SignalingConfig.class);

        CommentedConfigurationNode node = CommentedConfigurationNode.root(AsteriskSerializer.dumpOptions());
        node.set(config);
        assertEquals("***", node.node("nxs", "token").getString());
        assertFalse(node.toString().contains("yaml-secret"));
        assertEquals("https://signal.example.net", node.node("nxs", "endpoint").getString(), "the service is not secret");
    }

    /** NXS confirming a public IPv4, a public IPv6 and a LAN address. */
    private static final NetherNetNxs.Published PUBLISHED = new NetherNetNxs.Published(
        new ProviderClient.PublishedEndpoints(List.of(new InetSocketAddress("198.51.100.1", 19132),
            new InetSocketAddress("2001:db8::1", 19133), new InetSocketAddress("192.168.1.5", 19132)),
            Set.of(AddressFamily.IPV6, AddressFamily.IPV4)),
        "srv_01HZX", -125_000);

    @Test
    void censorsPublicAddressesNxsPublishesButKeepsTheirPorts() throws Exception {
        NetherNetServer.Announced announced = new NetherNetServer.Announced(19132, List.of(), true, List.of());

        assertEquals("{\"confirmed\":true,\"instanceId\":\"srv_01HZX\",\"clockOffsetMillis\":-125000,"
                + "\"addresses\":[\"***:19132\",\"[***]:19133\",\"192.168.1.5:19132\"],\"assistedFamilies\":[4,6]}",
            new Gson().toJson(DumpInfo.NetherNetInfo.of(announced, null, null, PUBLISHED).nxs()));

        AsteriskSerializer.showSensitive = true;
        assertEquals(List.of("198.51.100.1:19132", "[2001:db8::1]:19133", "192.168.1.5:19132"),
            DumpInfo.NetherNetInfo.of(announced, null, null, PUBLISHED).nxs().addresses(), "a full dump shows them");
    }

    @Test
    void keepsTheNxsInstanceIdReadableWithoutAFullDump() {
        // Support looks the server up by it, and registration already logs it
        NetherNetServer.Announced announced = new NetherNetServer.Announced(19132, List.of(), false, List.of());
        assertEquals("srv_01HZX", DumpInfo.NetherNetInfo.of(announced, null, null, PUBLISHED).nxs().instanceId());
    }

    @Test
    void tellsNxsRunningWithoutConfirmedAddressesFromNotRunning() {
        NetherNetServer.Announced announced = new NetherNetServer.Announced(19132, List.of(), false, List.of());
        assertEquals("{\"confirmed\":false,\"clockOffsetMillis\":0,\"addresses\":[],\"assistedFamilies\":[]}",
            new Gson().toJson(DumpInfo.NetherNetInfo.of(announced, null, null, new NetherNetNxs.Published(null, null, 0)).nxs()));
        assertNull(DumpInfo.NetherNetInfo.of(announced, null, null, null).nxs());
    }

    @Test
    void censorsTheAddressesInSystemPropertyFlags() {
        List<String> flags = List.of(
            "-Xmx1G",
            "-DgeyserAdvertiseAddresses=1.2.3.4, play.example.com,192.168.1.5",
            "-DgeyserStunServers=stun:stun.example.com:3478",
            "-DgeyserUdpAddress=0.0.0.0",
            "-DpluginUdpAddress=1.2.3.4",
            "-DgeyserUdpPort=19133");

        assertEquals(List.of(
            "-Xmx1G",
            "-DgeyserAdvertiseAddresses=***,***,192.168.1.5",
            "-DgeyserStunServers=***",
            "-DgeyserUdpAddress=0.0.0.0",
            "-DpluginUdpAddress=***",
            "-DgeyserUdpPort=19133"), new DumpInfo.FlagsInfo(flags).flags());

        AsteriskSerializer.showSensitive = true;
        assertEquals("-DgeyserAdvertiseAddresses=1.2.3.4",
            new DumpInfo.FlagsInfo(List.of("-DgeyserAdvertiseAddresses=1.2.3.4")).flags().getFirst(), "a full dump shows them");
    }

    private static InetSocketAddress endpoint(String literal, int port) throws Exception {
        return new InetSocketAddress(InetAddress.getByName(literal), port);
    }
}
