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
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import org.geysermc.geyser.GeyserBootstrap;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.configuration.GeyserConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        NetherNetServerChannel endpoint = NetherNetChannelFactory.server(() -> fail("no engine needed")).newChannel();
        group.register(endpoint).sync();
        endpoint.close().sync();

        NetherNetHttpSignaling http = NetherNetHttpSignaling.builder(group).build();
        assertThrows(IllegalStateException.class, () -> NetherNetServer.listen(endpoint, http, address));

        http.termination().toCompletableFuture().get(3, TimeUnit.SECONDS);
        try (ServerSocket next = new ServerSocket()) {
            next.bind(address);
        }
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
                "device", () -> engine);

        assertFalse(server.start());

        verify(logger).error(contains("Failed to open the ICE port, UDP 127.0.0.1:"));
        verify(logger, never()).info(contains("HTTP signaling on TCP port"));
        assertTrue(engineClosed.get(), "the shutdown waited for the endpoint to close the engine it owns");
        assertFalse(server.isRunning());
    }

    private GeyserImpl geyser(Path configFolder, int port) {
        GeyserImpl geyser = mock(GeyserImpl.class);
        GeyserConfig config = mock(GeyserConfig.class, RETURNS_DEEP_STUBS);
        GeyserBootstrap bootstrap = mock(GeyserBootstrap.class);
        when(geyser.getLogger()).thenReturn(logger);
        when(geyser.config()).thenReturn(config);
        when(geyser.getBootstrap()).thenReturn(bootstrap);
        when(bootstrap.getConfigFolder()).thenReturn(configFolder);
        when(config.bedrock().address()).thenReturn("127.0.0.1");
        when(config.bedrock().port()).thenReturn(port);
        return geyser;
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        }
    }

    private static int freeUdpPort() throws IOException {
        try (DatagramSocket probe = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            return probe.getLocalPort();
        }
    }
}
