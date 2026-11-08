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

import io.github.sendablemetatype.netty.channel.nethernet.NetherNetChannelFactory;
import io.github.sendablemetatype.netty.channel.nethernet.NetherNetServerChannel;
import io.github.sendablemetatype.netty.channel.nethernet.engine.EndpointConfig;
import io.github.sendablemetatype.netty.channel.nethernet.engine.IceEndpoint;
import io.github.sendablemetatype.netty.channel.nethernet.engine.NetherNetEngine;
import io.github.sendablemetatype.netty.channel.nethernet.engine.Peer;
import io.github.sendablemetatype.netty.channel.nethernet.engine.PeerConfig;
import io.github.sendablemetatype.netty.channel.nethernet.engine.PeerListener;
import io.github.sendablemetatype.netty.channel.nethernet.signaling.NetherNetHttpSignaling;
import io.github.sendablemetatype.netty.channel.nethernet.signaling.http.StatusAnswer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.geysermc.geyser.GeyserBootstrap;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.configuration.GeyserConfig;
import org.geysermc.geyser.configuration.GeyserConfig.SignalingConfig.Mode;
import org.geysermc.geyser.configuration.GeyserPluginConfig;
import org.geysermc.geyser.configuration.GeyserRemoteConfig;
import org.geysermc.geyser.event.type.GeyserBedrockPingEventImpl;
import org.geysermc.geyser.network.BedrockPingHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.interfaces.InterfaceDefaultOptions;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NetherNetServerTest {

    private final EventLoopGroup group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
    private final GeyserLogger logger = mock(GeyserLogger.class);

    @AfterEach
    void tearDown() {
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    void httpSignalingThatAClosedEndpointRefusesFreesItsPort() throws Exception {
        InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), freePort());
        // As when the engine failed to open the ICE port and NetworkM closed the endpoint. An
        // endpoint that never binds creates no engine.
        NetherNetServerChannel endpoint = registeredEndpoint();
        endpoint.close().sync();

        NetherNetHttpSignaling http = NetherNetHttpSignaling.builder(group).build();
        assertThrows(IllegalStateException.class, () -> NetherNetServer.listen(endpoint, http, address));

        http.termination().toCompletableFuture().get(3, TimeUnit.SECONDS);
        try (ServerSocket next = new ServerSocket()) {
            next.bind(address);
        }
    }

    @Test
    void onlyTheNxsAndHybridModesRegisterWithASignalingService() throws Exception {
        for (Mode mode : Mode.values()) {
            NetherNetServer.Sources sources = NetherNetServer.Sources.of(loaded(mode, 25565, 19132, 0));
            assertEquals(mode == Mode.NXS || mode == Mode.HYBRID, sources.nxs(), mode.name());
            assertEquals(mode != Mode.NXS, sources.http(), mode.name());
            assertFalse(sources.portTaken(), mode.name());
        }
    }

    @Test
    void builtinSignalingOnTheJavaServersPortStaysBuiltinWithoutHttpSignaling() throws Exception {
        // As clone-remote-port leaves it: the Bedrock port is the Java port and "port" under "signaling" is 0
        assertEquals(new NetherNetServer.Sources(false, false, true),
                NetherNetServer.Sources.of(loaded(Mode.BUILTIN, 25565, 25565, 0)), "no switch to a signaling service");
        assertEquals(new NetherNetServer.Sources(false, true, true),
                NetherNetServer.Sources.of(loaded(Mode.HYBRID, 25565, 25565, 0)));
        // "nxs" asks for no HTTP signaling, so nothing clashes
        assertEquals(new NetherNetServer.Sources(false, true, false),
                NetherNetServer.Sources.of(loaded(Mode.NXS, 25565, 25565, 0)));
        // The effective TCP port decides: "port" under "signaling" moves HTTP signaling off the Java port, and
        // onto it from another Bedrock port
        assertEquals(new NetherNetServer.Sources(true, false, false),
                NetherNetServer.Sources.of(loaded(Mode.BUILTIN, 25565, 25565, 25566)));
        assertEquals(new NetherNetServer.Sources(false, false, true),
                NetherNetServer.Sources.of(loaded(Mode.BUILTIN, 25565, 19132, 25565)));
    }

    @Test
    void theJavaPortErrorSaysThatOnlyTheModeLetsPlayersJoinThroughASignalingService(@TempDir Path config)
            throws Exception {
        int port = freeUdpPort();
        GeyserImpl geyser = geyser(config, port, GeyserPluginConfig.class);
        // An explicit IntegratedJavaConfig: a deep stub reached through GeyserConfig#java can come back as a plain
        // JavaConfig mock, which the server's cast to the plugin config's covariant java() then rejects
        GeyserPluginConfig.IntegratedJavaConfig java = mock(GeyserPluginConfig.IntegratedJavaConfig.class);
        when(java.port()).thenReturn(port);
        when(((GeyserPluginConfig) geyser.config()).java()).thenReturn(java);
        NetherNetServer server = new NetherNetServer(geyser, group, "1234567890", "custom", "device",
                threshold -> failingEngine());

        assertFalse(server.start());

        verify(logger).error(contains("HTTP signaling does not start: its TCP port, " + port + ", is the Java server's port"));
        verify(logger).error(contains("Joins over Xbox signaling (join codes, connection IDs and friend list joins) still work."));
        verify(logger).error(contains("set \"mode\" under \"bedrock.signaling\" to \"nxs\" (or \"hybrid\")"));
        verify(logger, never()).info(contains("HTTP signaling on TCP port"));
        assertNull(server.nxs());
    }

    @Test
    void nxsDoesNotStartBehindRelaysWithoutTheirAddresses(@TempDir Path config) throws Exception {
        GeyserImpl geyser = geyser(config, freeUdpPort(), GeyserConfig.class);
        when(geyser.config().bedrock().signaling().mode()).thenReturn(Mode.NXS);
        when(geyser.config().advanced().bedrock().webrtcProxyProtocolRelays()).thenReturn(List.of("10.0.0.0/8"));
        NetherNetServer server = new NetherNetServer(geyser, group, "1234567890", "custom", "device",
                threshold -> failingEngine());

        assertFalse(server.start());

        verify(logger).error(contains("NXS does not start: \"webrtc-proxy-protocol-relays\" is set, so"
                + " \"advertise-addresses\" under \"bedrock.signaling\" has to name the relays"));
    }

    /** An engine whose ICE port fails to open, as when another process took it after the bind probed it. */
    private static NetherNetEngine failingEngine() {
        return new NetherNetEngine() {
            @Override
            public Peer createPeer(PeerConfig peerConfig, PeerListener listener) {
                throw new IllegalStateException("no peers in this test");
            }

            @Override
            public CompletionStage<IceEndpoint> openEndpoint(EndpointConfig endpointConfig) {
                return CompletableFuture.failedFuture(new UncheckedIOException(new BindException("taken")));
            }

            @Override
            public CompletionStage<Void> close() {
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    /** A config as the loader builds it, so the effective ports come from its own defaults. */
    private static GeyserPluginConfig loaded(Mode mode, int javaPort, int bedrockPort, int signalingPort)
            throws Exception {
        GeyserPluginConfig config = YamlConfigurationLoader.builder()
                .source(() -> new BufferedReader(new StringReader("{}")))
                .defaultOptions(InterfaceDefaultOptions::addTo)
                .build().load().get(GeyserPluginConfig.class);
        config.java().port(javaPort);
        config.bedrock().port(bedrockPort);
        config.bedrock().signaling().port(signalingPort);
        config.bedrock().signaling().mode(mode);
        return config;
    }

    @Test
    void anIcePortThatFailsToOpenAfterTheBindFailsTheStartAndShutdownWaitsForTheEngine(@TempDir Path config)
            throws Exception {
        AtomicBoolean engineClosed = new AtomicBoolean();
        NetherNetEngine engine = new NetherNetEngine() {
            @Override
            public Peer createPeer(PeerConfig peerConfig, PeerListener listener) {
                throw new IllegalStateException("no peers in this test");
            }

            @Override
            public CompletionStage<IceEndpoint> openEndpoint(EndpointConfig endpointConfig) {
                // As the WebRTC engine fails when another process took the port after the bind probed it
                CompletableFuture<IceEndpoint> opening = new CompletableFuture<>();
                CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(() ->
                        opening.completeExceptionally(new UncheckedIOException(new BindException("taken after the probe"))));
                return opening;
            }

            @Override
            public CompletionStage<Void> close() {
                return CompletableFuture.runAsync(() -> engineClosed.set(true),
                        CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS));
            }
        };
        NetherNetServer server = new NetherNetServer(geyser(config, freeUdpPort()), group, "1234567890", "custom",
                "device", threshold -> engine);

        assertFalse(server.start());

        verify(logger).error(contains("Failed to open the ICE port, UDP 127.0.0.1:"));
        verify(logger, never()).info(contains("HTTP signaling on TCP port"));
        assertTrue(engineClosed.get(), "the shutdown waited for the endpoint to close the engine it owns");
        assertFalse(server.isRunning());
    }

    @Test
    void relaysNoneOfWhichCanBeReadStopTheStartNamingTheSetting(@TempDir Path config) throws Exception {
        GeyserImpl geyser = geyser(config, freeUdpPort());
        // A range that does not parse. Running without the relays would take players directly and
        // announce this host's addresses, so NetworkM refuses to bind.
        when(geyser.config().advanced().bedrock().webrtcProxyProtocolRelays()).thenReturn(List.of("10.0.0.0/99"));
        NetherNetServer server = new NetherNetServer(geyser, group, "1234567890", "custom", "device",
                threshold -> fail("the bind is refused before it needs the engine"));

        assertFalse(server.start());

        verify(logger).error(contains(
                "NetherNet will not start: \"webrtc-proxy-protocol-relays\" under \"advanced.bedrock\" lists relays"));
        assertFalse(server.isRunning());
    }

    @Test
    void anEndpointThatClosesItselfWhileRunningIsReportedAndAShutdownIsNot() throws Exception {
        AtomicBoolean running = new AtomicBoolean(true);
        NetherNetServerChannel closedItself = registeredEndpoint();
        NetherNetServer.warnWhenClosedByItself(closedItself, running::get, logger);

        closedItself.close().sync();
        verify(logger, timeout(2000)).warning(contains("Bedrock players can no longer join until a restart or reload"));

        // As shutdown does before it closes the endpoint
        running.set(false);
        NetherNetServerChannel shutDown = registeredEndpoint();
        NetherNetServer.warnWhenClosedByItself(shutDown, running::get, logger);
        shutDown.close().sync();
        shutDown.eventLoop().submit(() -> { }).sync();
        verify(logger, times(1)).warning(anyString());
    }

    @Test
    void httpSignalingStaysOffTheJavaServersPortOnAPluginPlatform() {
        GeyserPluginConfig plugin = mock(GeyserPluginConfig.class, RETURNS_DEEP_STUBS);
        when(plugin.java().port()).thenReturn(25565);
        when(plugin.bedrock().signalingPort()).thenReturn(25565);
        assertTrue(NetherNetServer.signalingPortIsTheJavaServers(plugin));

        when(plugin.bedrock().signalingPort()).thenReturn(25566);
        assertFalse(NetherNetServer.signalingPortIsTheJavaServers(plugin), "a second TCP port brings it back");

        // Standalone connects to a Java server that may run anywhere, so only the bind can tell
        GeyserRemoteConfig remote = mock(GeyserRemoteConfig.class, RETURNS_DEEP_STUBS);
        when(remote.java().port()).thenReturn(25565);
        when(remote.bedrock().signalingPort()).thenReturn(25565);
        assertFalse(NetherNetServer.signalingPortIsTheJavaServers(remote));
    }

    /** An endpoint that never binds, so it needs no engine. */
    private NetherNetServerChannel registeredEndpoint() throws InterruptedException {
        NetherNetServerChannel endpoint = NetherNetChannelFactory.server(() -> fail("no engine needed")).newChannel();
        group.register(endpoint).sync();
        return endpoint;
    }

    private GeyserImpl geyser(Path configFolder, int port) {
        return geyser(configFolder, port, GeyserConfig.class);
    }

    private GeyserImpl geyser(Path configFolder, int port, Class<? extends GeyserConfig> configType) {
        GeyserImpl geyser = mock(GeyserImpl.class);
        GeyserConfig config = mock(configType, RETURNS_DEEP_STUBS);
        GeyserBootstrap bootstrap = mock(GeyserBootstrap.class);
        when(geyser.getLogger()).thenReturn(logger);
        when(geyser.config()).thenReturn(config);
        when(geyser.getBootstrap()).thenReturn(bootstrap);
        when(bootstrap.getConfigFolder()).thenReturn(configFolder);
        when(config.bedrock().address()).thenReturn("127.0.0.1");
        when(config.bedrock().port()).thenReturn(port);
        when(config.bedrock().icePort()).thenReturn(port);
        when(config.bedrock().signalingPort()).thenReturn(port);
        when(config.bedrock().signaling().mode()).thenReturn(Mode.BUILTIN);
        // Deep stubs would answer with a mock list, which is never empty
        when(config.bedrock().signaling().advertiseAddresses()).thenReturn(List.of());
        return geyser;
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        }
    }

    @Test
    void aStatusDocumentThePingEventChangedGoesToItsRequesterAlone() {
        InetSocketAddress requester = new InetSocketAddress(InetAddress.getLoopbackAddress(), 50000);

        BedrockPong untouched = pong();
        GeyserBedrockPingEventImpl reading = new GeyserBedrockPingEventImpl(untouched, requester);
        reading.playerCount(reading.playerCount());
        assertTrue(served(untouched, reading).shared(), "a listener that changed nothing leaves the document shared");

        BedrockPong personal = pong();
        GeyserBedrockPingEventImpl welcoming = new GeyserBedrockPingEventImpl(personal, requester);
        welcoming.primaryMotd("Welcome back, Steve");
        assertFalse(served(personal, welcoming).shared(),
            "NetworkM's fallback must never repeat a MOTD chosen for this requester to another");
    }

    private static StatusAnswer.Serve served(BedrockPong pong, GeyserBedrockPingEventImpl event) {
        return assertInstanceOf(StatusAnswer.Serve.class,
            NetherNetServer.statusAnswer(new BedrockPingHandler.PingAnswer(pong, event.changedPong())));
    }

    private static BedrockPong pong() {
        return new BedrockPong().motd("Geyser").subMotd("Another Geyser server.").protocolVersion(1)
            .version("1.26.30").playerCount(0).maximumPlayerCount(100).gameType("Survival");
    }

    private static int freeUdpPort() throws IOException {
        try (DatagramSocket probe = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            return probe.getLocalPort();
        }
    }
}
