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
import io.github.sendablemetatype.netty.channel.nethernet.config.NetherChannelOption;
import io.github.sendablemetatype.netty.channel.nethernet.engine.EndpointConfig;
import io.github.sendablemetatype.netty.channel.nethernet.engine.IceEndpoint;
import io.github.sendablemetatype.netty.channel.nethernet.engine.NetherNetEngine;
import io.github.sendablemetatype.netty.channel.nethernet.engine.Peer;
import io.github.sendablemetatype.netty.channel.nethernet.engine.PeerConfig;
import io.github.sendablemetatype.netty.channel.nethernet.engine.PeerListener;
import io.github.sendablemetatype.netty.channel.nethernet.nxs.ProviderStateStore;
import io.github.sendablemetatype.netty.util.nethernet.ServerIdentity;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.configuration.GeyserConfig;
import org.geysermc.geyser.event.GeyserEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * NXS on a real endpoint, bound on an engine that opens the shared ICE port and nothing more, against a signaling
 * service that is not there: the start, the cleanup after a registration that fails, the stop, and a second start on
 * the same folder as a reload does.
 */
class NetherNetNxsLifecycleTest {

    private final EventLoopGroup group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
    private final Queue<String> errors = new ConcurrentLinkedQueue<>();
    private final GeyserLogger logger = mock(GeyserLogger.class, withSettings().defaultAnswer(invocation -> {
        if (invocation.getMethod().getName().equals("error")) {
            errors.add(invocation.getArgument(0));
        }
        return null;
    }));
    private final GeyserEventBus eventBus = mock(GeyserEventBus.class);

    @AfterEach
    void tearDown() {
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    void aStartThatCannotRegisterReleasesEverythingAndTheNextStartOnTheFolderWorks(@TempDir Path folder) throws Exception {
        NetherNetServerChannel endpoint = boundEndpoint();
        try {
            GeyserImpl geyser = geyser(closedTcpPort());
            Path state = folder.resolve("provider-state");

            NetherNetNxs first = new NetherNetNxs(geyser, folder, NetherNetNxsLifecycleTest::pong);
            first.start(endpoint, List.of(), false);
            // The client calls the service only once NXS is open on the endpoint
            awaitError("Could not register with the signaling service", 1);
            first.stop().toCompletableFuture().get(20, TimeUnit.SECONDS);

            assertTrue(endpoint.sources().isEmpty(), "the stop took NXS off the endpoint");
            String certificate = Files.readString(state.resolve("host-cert.pem"));
            String key = Files.readString(state.resolve("host-key.pem"));
            // Its lock is free for the next start
            new ProviderStateStore(state).close();
            verify(eventBus).unregisterAll(first);

            NetherNetNxs second = new NetherNetNxs(geyser, folder, NetherNetNxsLifecycleTest::pong);
            second.start(endpoint, List.of(), false);
            awaitError("Could not register with the signaling service", 2);
            second.stop().toCompletableFuture().get(20, TimeUnit.SECONDS);

            assertTrue(endpoint.sources().isEmpty());
            assertEquals(certificate, Files.readString(state.resolve("host-cert.pem")), "the certificate is kept");
            assertEquals(key, Files.readString(state.resolve("host-key.pem")));
            assertTrue(errors.stream().noneMatch(error -> error.contains("NXS did not start")), errors.toString());
        } finally {
            endpoint.close().syncUninterruptibly();
        }
    }

    @Test
    void aStopBeforeTheStartLeavesNothingBehind(@TempDir Path folder) throws Exception {
        NetherNetServerChannel endpoint = boundEndpoint();
        try {
            NetherNetNxs nxs = new NetherNetNxs(geyser(closedTcpPort()), folder, NetherNetNxsLifecycleTest::pong);
            nxs.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
            nxs.start(endpoint, List.of(), false);

            assertTrue(endpoint.sources().isEmpty());
            assertTrue(Files.notExists(folder.resolve("provider-state")));
        } finally {
            endpoint.close().syncUninterruptibly();
        }
    }

    private void awaitError(String text, int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (errors.stream().filter(error -> error.contains(text)).count() < count) {
            if (System.nanoTime() > deadline) {
                fail("No \"" + text + "\" error, only " + errors);
            }
            Thread.sleep(20);
        }
    }

    private GeyserImpl geyser(int providerPort) {
        GeyserImpl geyser = mock(GeyserImpl.class);
        GeyserConfig config = mock(GeyserConfig.class, RETURNS_DEEP_STUBS);
        when(geyser.getLogger()).thenReturn(logger);
        when(geyser.config()).thenReturn(config);
        when(geyser.eventBus()).thenReturn(eventBus);
        // Plain HTTP is allowed on loopback only, for a service on this machine
        when(config.bedrock().signaling().nxs().endpoint()).thenReturn("http://127.0.0.1:" + providerPort);
        when(config.bedrock().signaling().nxs().token()).thenReturn("");
        when(config.advanced().nxs().location()).thenReturn(Map.of());
        return geyser;
    }

    private static BedrockPong pong() {
        return new BedrockPong().motd("Geyser").subMotd("Another Geyser server.").protocolVersion(1)
            .version("1.26.30").playerCount(0).maximumPlayerCount(10).gameType("Survival");
    }

    /** An endpoint with the shared ICE port, bound on loopback. */
    private NetherNetServerChannel boundEndpoint() throws Exception {
        NetherNetEngine engine = new PortOnlyEngine();
        NetherNetServerChannel endpoint = (NetherNetServerChannel) new ServerBootstrap()
            .group(group, group)
            .channelFactory(NetherNetChannelFactory.server(() -> engine))
            .option(NetherChannelOption.NETHER_SERVER_IDENTITY, ServerIdentity.ephemeral("self"))
            .option(NetherChannelOption.NETHER_SERVER_SHARED_ICE_PORT, true)
            .childHandler(new ChannelInboundHandlerAdapter())
            .bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), freeUdpPort())).sync().channel();
        endpoint.ready().toCompletableFuture().get(10, TimeUnit.SECONDS);
        return endpoint;
    }

    private static int freeUdpPort() throws Exception {
        try (DatagramSocket probe = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            return probe.getLocalPort();
        }
    }

    /** A TCP port nothing listens on, so a connection to it is refused at once. */
    private static int closedTcpPort() throws Exception {
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        }
    }

    /** Opens the shared ICE port and creates no peers, so no player and no address probe gets anywhere. */
    private static final class PortOnlyEngine implements NetherNetEngine {
        @Override
        public Peer createPeer(PeerConfig peerConfig, PeerListener listener) {
            throw new IllegalStateException("no peers in this test");
        }

        @Override
        public CompletionStage<IceEndpoint> openEndpoint(EndpointConfig endpointConfig) {
            return CompletableFuture.completedFuture(new PortOnlyEndpoint(endpointConfig.port()));
        }

        @Override
        public CompletionStage<Void> close() {
            return CompletableFuture.completedFuture(null);
        }
    }

    private record PortOnlyEndpoint(int port) implements IceEndpoint {
        @Override
        public void onHeldCheck(Consumer<HeldCheck> handler, Executor executor) {
        }

        @Override
        public void clearHeldCheckHandler() {
        }

        @Override
        public void heldCheckLimits(int maxPending, Duration timeout) {
        }

        @Override
        public void derivedPasswords(byte[] context, Map<String, byte[]> keys) {
        }

        @Override
        public void clearDerivedPasswords() {
        }

        @Override
        public void proxyRelays(List<String> relays) {
        }

        @Override
        public CompletionStage<Optional<InetSocketAddress>> relayOf(InetSocketAddress client) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletionStage<StunMonitor> monitorStun(InetSocketAddress server) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("no STUN in this test"));
        }

        @Override
        public CompletionStage<Stats> stats() {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("no stats in this test"));
        }

        @Override
        public CompletionStage<Void> close() {
            return CompletableFuture.completedFuture(null);
        }
    }
}
