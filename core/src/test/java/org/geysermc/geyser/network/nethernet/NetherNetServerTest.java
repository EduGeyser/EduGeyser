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
import io.github.sendablemetatype.netty.channel.nethernet.signaling.NetherNetHttpSignaling;
import io.github.sendablemetatype.netty.channel.nethernet.signaling.http.StatusAnswer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.geysermc.geyser.event.type.GeyserBedrockPingEventImpl;
import org.geysermc.geyser.network.BedrockPingHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class NetherNetServerTest {

    private final EventLoopGroup group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());

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

        // The listener closes on its own threads
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (true) {
            try (ServerSocket next = new ServerSocket()) {
                next.bind(address);
                return;
            } catch (BindException e) {
                if (System.nanoTime() > deadline) {
                    fail("TCP " + address + " is still bound after the failed start: " + e.getMessage());
                }
                Thread.sleep(20);
            }
        }
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
}
