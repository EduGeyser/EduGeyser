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

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.network.ConnectionRequests;

import java.net.InetSocketAddress;

/**
 * Applies the {@link ConnectionRequests} gate to NetherNet connections when the transport opens. The channel is
 * handed to the initializer before ICE finishes, while an Xbox signaled connection only carries a placeholder
 * address, and its session only starts with the first packet after activation. For a relayed connection the
 * address is the TURN relay's.
 */
@ChannelHandler.Sharable
final class NetherNetConnectionGate extends ChannelInboundHandlerAdapter {
    static final String NAME = "geyser-connection-request";

    private final GeyserImpl geyser;

    NetherNetConnectionGate(GeyserImpl geyser) {
        this.geyser = geyser;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // NetherNet carries no PROXY protocol, so the peer address is the client address
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress clientAddress
                && !ConnectionRequests.accept(this.geyser, clientAddress, null)) {
            ctx.close();
            return;
        }
        ctx.pipeline().remove(this);
        ctx.fireChannelActive();
    }
}
