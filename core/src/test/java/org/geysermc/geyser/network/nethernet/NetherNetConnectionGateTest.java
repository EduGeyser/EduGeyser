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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.event.connection.ConnectionRequestEvent;
import org.geysermc.geyser.configuration.GeyserConfig;
import org.geysermc.geyser.event.GeyserEventBus;
import org.geysermc.geyser.scoreboard.network.util.EmptyGeyserLogger;
import org.geysermc.geyser.text.GeyserLocale;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class NetherNetConnectionGateTest {

    private static final InetSocketAddress CLIENT = new InetSocketAddress("203.0.113.7", 50123);

    @Test
    void refusedConnectionClosesBeforeTheRestOfThePipelineActivates() throws Exception {
        List<String> downstream = new ArrayList<>();
        EmbeddedChannel channel = activate(geyser(true, new ArrayList<>()), downstream);

        assertFalse(channel.isOpen());
        assertTrue(downstream.isEmpty());
    }

    @Test
    void acceptedConnectionReportsThePeerAndStepsAside() throws Exception {
        List<ConnectionRequestEvent> events = new ArrayList<>();
        List<String> downstream = new ArrayList<>();
        EmbeddedChannel channel;
        // The accepted path logs a locale string, and no locale is loaded here
        try (var locale = mockStatic(GeyserLocale.class)) {
            channel = activate(geyser(false, events), downstream);
        }

        assertTrue(channel.isOpen());
        assertEquals(List.of("active"), downstream);
        assertNull(channel.pipeline().get(NetherNetConnectionGate.NAME));
        assertEquals(1, events.size());
        assertEquals(CLIENT, events.getFirst().inetSocketAddress());
        assertNull(events.getFirst().proxyIp());
        channel.finishAndReleaseAll();
    }

    private static EmbeddedChannel activate(GeyserImpl geyser, List<String> downstream) throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel(false, false) {
            @Override
            protected SocketAddress remoteAddress0() {
                return CLIENT;
            }
        };
        channel.pipeline()
                .addLast(NetherNetConnectionGate.NAME, new NetherNetConnectionGate(geyser))
                .addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelActive(ChannelHandlerContext ctx) {
                        downstream.add("active");
                    }
                });
        channel.register();
        return channel;
    }

    private static GeyserImpl geyser(boolean refuse, List<ConnectionRequestEvent> events) {
        GeyserImpl geyser = mock(GeyserImpl.class);
        GeyserConfig config = mock(GeyserConfig.class);
        when(geyser.config()).thenReturn(config);
        when(geyser.getLogger()).thenReturn(new EmptyGeyserLogger());
        GeyserEventBus eventBus = mock(GeyserEventBus.class);
        when(geyser.eventBus()).thenReturn(eventBus);
        doAnswer(invocation -> {
            ConnectionRequestEvent event = invocation.getArgument(0);
            events.add(event);
            event.setCancelled(refuse);
            return null;
        }).when(eventBus).fire(any(ConnectionRequestEvent.class));
        return geyser;
    }
}
