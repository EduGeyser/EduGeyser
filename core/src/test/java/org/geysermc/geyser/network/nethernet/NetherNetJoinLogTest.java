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

import io.github.sendablemetatype.netty.channel.nethernet.address.AddressSource;
import io.github.sendablemetatype.netty.channel.nethernet.address.SourcedAddress;
import io.github.sendablemetatype.netty.channel.nethernet.signaling.http.TlsContextSelector;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.network.nethernet.NetherNetJoinLog.Signaling;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shredzone.acme4j.util.CertificateUtils;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class NetherNetJoinLogTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private final GeyserLogger logger = mock(GeyserLogger.class);
    private EventLoopGroup group;
    private Channel server;

    @BeforeEach
    void setUp() {
        group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close().syncUninterruptibly();
        }
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    void aJoinFromAConnectionThatCompletedTlsCameOverHttps() throws Exception {
        NetherNetJoinLog log = new NetherNetJoinLog(logger, true);
        Credentials credentials = Credentials.create();
        SslContext served = credentials.serverContext();
        serve(log.observe(name -> served));

        Channel client = connect(credentials.clientContext());
        SourcedAddress signaled = signaling((InetSocketAddress) client.localAddress());

        awaitSignaling(Signaling.HTTPS, () -> log.signalingOf(signaled));
        assertEquals(Signaling.HTTP, log.signalingOf(signaling(new InetSocketAddress(InetAddress.getLoopbackAddress(), 1))),
                "another client address never spoke TLS");
        client.close().sync();
    }

    @Test
    void aJoinFromTheAddressOfAClosedTlsConnectionIsNotLabeledHttps() throws Exception {
        NetherNetJoinLog log = new NetherNetJoinLog(logger, true);
        Credentials credentials = Credentials.create();
        SslContext served = credentials.serverContext();
        serve(log.observe(name -> served));

        Channel client = connect(credentials.clientContext());
        SourcedAddress signaled = signaling((InetSocketAddress) client.localAddress());
        awaitSignaling(Signaling.HTTPS, () -> log.signalingOf(signaled));

        // A plain HTTP join from the same IP and port can follow now
        client.close().sync();
        awaitSignaling(Signaling.HTTP_OR_HTTPS, () -> log.signalingOf(signaled));
    }

    @Test
    void aClosedTlsConnectionIsForgottenOnceItsGraceEnds() throws Exception {
        NetherNetJoinLog log = new NetherNetJoinLog(logger, true, Duration.ZERO);
        Credentials credentials = Credentials.create();
        SslContext served = credentials.serverContext();
        serve(log.observe(name -> served));

        Channel client = connect(credentials.clientContext());
        SourcedAddress signaled = signaling((InetSocketAddress) client.localAddress());
        awaitSignaling(Signaling.HTTPS, () -> log.signalingOf(signaled));

        client.close().sync();
        awaitSignaling(Signaling.HTTP, () -> log.signalingOf(signaled));
    }

    @Test
    void aClientThatRejectsTheCertificateIsLoggedAndTheNextOnesGoToDebug() throws Exception {
        NetherNetJoinLog log = new NetherNetJoinLog(logger, true);
        SslContext served = Credentials.create().serverContext();
        serve(log.observe(name -> served));

        // Trusts another certificate, so each client refuses the one served
        SslContext rejecting = Credentials.create().clientContext();
        for (int i = 0; i < 3; i++) {
            connect(rejecting);
        }

        String failed = "TLS handshake for HTTP signaling failed (no server name)";
        verify(logger, timeout(WAIT.toMillis()).times(2)).debug(contains(failed));
        verify(logger, times(1)).info(contains(failed));
    }

    @Test
    void aJoinAProxyDeclaredIsToldApartByTheProxyConnection() throws Exception {
        NetherNetJoinLog log = new NetherNetJoinLog(logger, true);
        Credentials credentials = Credentials.create();
        SslContext served = credentials.serverContext();
        serve(log.observe(name -> served));

        // The proxy passes the player's TLS through and declares the player in a PROXY protocol header
        Channel proxy = connect(credentials.clientContext());
        InetSocketAddress player = new InetSocketAddress("203.0.113.7", 50123);
        SourcedAddress signaled = new SourcedAddress(player, AddressSource.SIGNALING_PROXY_PROTOCOL,
                (InetSocketAddress) proxy.localAddress());

        awaitSignaling(Signaling.HTTPS, () -> log.signalingOf(signaled));
        proxy.close().sync();
    }

    @Test
    void joinsWithoutAnAddressCameOverXboxSignaling() {
        NetherNetJoinLog log = new NetherNetJoinLog(logger, true);

        assertEquals(Signaling.XBOX_RPC, log.signalingOf(SourcedAddress.unknown()));
    }

    private static SourcedAddress signaling(InetSocketAddress address) {
        return new SourcedAddress(address, AddressSource.SIGNALING, null);
    }

    private void serve(TlsContextSelector selector) {
        server = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        // As NetworkM's HTTP signaling does for a client that sent no server name
                        channel.pipeline().addLast(selector.select(null).newHandler(channel.alloc()));
                    }
                })
                .bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                .syncUninterruptibly()
                .channel();
    }

    /** Connects and starts the TLS handshake, without waiting for it to finish. */
    private Channel connect(SslContext clientContext) throws InterruptedException {
        return new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        SslHandler handler = clientContext.newHandler(channel.alloc());
                        channel.pipeline().addLast(handler);
                    }
                })
                .connect(server.localAddress())
                .sync()
                .channel();
    }

    /** The server side may finish its handshake after the client's, so this waits for it. */
    private static void awaitSignaling(Signaling expected, Supplier<Signaling> actual) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (actual.get() != expected) {
            if (System.nanoTime() > deadline) {
                fail("expected " + expected + " within " + WAIT + ", still " + actual.get());
            }
            Thread.sleep(10);
        }
    }

    private record Credentials(KeyPair keys, X509Certificate certificate) {

        static Credentials create() throws Exception {
            // RSA: acme4j signs its test certificates with SHA256withRSA
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keys = generator.generateKeyPair();
            Instant now = Instant.now();
            X509Certificate certificate = CertificateUtils.createTestRootCertificate("CN=EduGeyser test",
                    now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(1)), keys);
            return new Credentials(keys, certificate);
        }

        SslContext serverContext() throws Exception {
            return SslContextBuilder.forServer(keys.getPrivate(), certificate).build();
        }

        SslContext clientContext() throws Exception {
            // The certificate names no address, and only the trust decides here
            return SslContextBuilder.forClient()
                    .trustManager(certificate)
                    .endpointIdentificationAlgorithm(null)
                    .build();
        }
    }
}
