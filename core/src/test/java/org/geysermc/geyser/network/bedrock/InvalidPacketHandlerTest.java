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

package org.geysermc.geyser.network.bedrock;

import io.netty.channel.embedded.EmbeddedChannel;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.configuration.GeyserConfig;
import org.geysermc.geyser.session.GeyserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.nio.channels.ClosedChannelException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvalidPacketHandlerTest {

    private final GeyserLogger logger = mock(GeyserLogger.class);
    private final GeyserSession session = mock(GeyserSession.class);
    private MockedStatic<GeyserImpl> geyserInstance;

    @BeforeEach
    void mockGeyser() {
        GeyserImpl geyser = mock(GeyserImpl.class);
        when(geyser.getLogger()).thenReturn(logger);
        when(geyser.config()).thenReturn(mock(GeyserConfig.class));
        geyserInstance = mockStatic(GeyserImpl.class);
        geyserInstance.when(GeyserImpl::getInstance).thenReturn(geyser);
        when(session.bedrockUsername()).thenReturn("player");
    }

    @AfterEach
    void closeMocks() {
        geyserInstance.close();
    }

    @Test
    void aConnectionTheTransportLostClosesWithoutADisconnect() {
        // As when the player quits while a send is in flight: the send fails with the transport's IOException
        EmbeddedChannel channel = channelFor(new ClosedChannelException());

        assertFalse(channel.isOpen());
        // A disconnect would replace the reason the transport already gave
        verify(session, never()).disconnect(anyString());
        verify(logger, never()).error(anyString(), any(Throwable.class));
    }

    @Test
    void anyOtherExceptionStillKicksThePlayer() {
        channelFor(new IllegalStateException("broken"));

        verify(session).disconnect("An internal error occurred!");
        verify(session).forciblyCloseUpstream();
    }

    private EmbeddedChannel channelFor(Throwable cause) {
        InvalidPacketHandler handler = new InvalidPacketHandler();
        handler.setSession(session);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        channel.pipeline().fireExceptionCaught(cause);
        return channel;
    }
}
