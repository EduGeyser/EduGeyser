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

import io.github.sendablemetatype.netty.channel.nethernet.NetherNetChannel;
import io.github.sendablemetatype.netty.channel.nethernet.address.AddressSource;
import io.github.sendablemetatype.netty.channel.nethernet.address.SourcedAddress;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.api.event.connection.ConnectionRequestEvent;
import org.geysermc.geyser.configuration.GeyserConfig;
import org.geysermc.geyser.event.GeyserEventBus;
import org.geysermc.geyser.text.GeyserLocale;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NetherNetConnectionGateTest {

    private static final InetSocketAddress PLAYER = new InetSocketAddress("203.0.113.7", 50123);
    private static final InetSocketAddress RELAY = new InetSocketAddress("198.51.100.20", 40000);

    private final List<ConnectionRequestEvent> events = new ArrayList<>();
    private final GeyserLogger logger = mock(GeyserLogger.class);
    private final ChannelPipeline pipeline = mock(ChannelPipeline.class);

    @Test
    void refusedConnectionClosesBeforeTheRestOfThePipelineActivates() throws Exception {
        NetherNetConnectionGate gate = new NetherNetConnectionGate(geyser(true, true));
        ChannelHandlerContext ctx = context(new SourcedAddress(PLAYER, AddressSource.ICE, null));

        gate.channelActive(ctx);

        verify(ctx).close();
        verify(ctx, never()).fireChannelActive();
    }

    @Test
    void acceptedConnectionReportsWhereItsAddressCameFromAndStepsAside() throws Exception {
        NetherNetConnectionGate gate = new NetherNetConnectionGate(geyser(false, true));
        ChannelHandlerContext ctx = context(new SourcedAddress(PLAYER, AddressSource.ICE_PROXY_PROTOCOL, RELAY));

        // The accepted path logs a locale string, and no locale is loaded here
        try (var locale = mockStatic(GeyserLocale.class)) {
            gate.channelActive(ctx);
        }

        verify(pipeline).remove(gate);
        verify(ctx).fireChannelActive();
        assertEquals(1, events.size());
        assertEquals(PLAYER, events.getFirst().inetSocketAddress());
        assertEquals(RELAY, events.getFirst().proxyIp());
        assertTrue(debugLine().contains("(ICE_PROXY_PROTOCOL via " + RELAY + ")"), debugLine());
    }

    @Test
    void theRelayIsWithheldWithThePlayerAddress() throws Exception {
        NetherNetConnectionGate gate = new NetherNetConnectionGate(geyser(false, false));
        ChannelHandlerContext ctx = context(new SourcedAddress(PLAYER, AddressSource.ICE_PROXY_PROTOCOL, RELAY));

        try (var locale = mockStatic(GeyserLocale.class)) {
            gate.channelActive(ctx);
            locale.verify(() -> GeyserLocale.getLocaleStringLog("geyser.network.attempt_connect", "<IP address withheld>"));
        }

        assertTrue(debugLine().contains("(ICE_PROXY_PROTOCOL via <Proxy IP address withheld>)"), debugLine());
        assertFalse(debugLine().contains(RELAY.getAddress().getHostAddress()), debugLine());
    }

    private ChannelHandlerContext context(SourcedAddress reported) {
        NetherNetChannel channel = mock(NetherNetChannel.class);
        when(channel.reportedAddress()).thenReturn(reported);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(channel);
        when(ctx.pipeline()).thenReturn(pipeline);
        return ctx;
    }

    private String debugLine() {
        ArgumentCaptor<String> line = ArgumentCaptor.forClass(String.class);
        verify(logger).debug(line.capture());
        return line.getValue();
    }

    private GeyserImpl geyser(boolean refuse, boolean logAddresses) {
        GeyserImpl geyser = mock(GeyserImpl.class);
        GeyserConfig config = mock(GeyserConfig.class);
        when(config.logPlayerIpAddresses()).thenReturn(logAddresses);
        when(geyser.config()).thenReturn(config);
        when(geyser.getLogger()).thenReturn(logger);
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
