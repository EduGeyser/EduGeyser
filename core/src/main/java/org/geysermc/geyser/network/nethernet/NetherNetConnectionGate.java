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
import io.github.sendablemetatype.netty.channel.nethernet.address.SourcedAddress;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.network.ConnectionRequests;

/**
 * Applies the {@link ConnectionRequests} gate to NetherNet connections when the transport opens, with the address
 * the channel reports from then on and where it came from. The channel is handed to the initializer before ICE
 * finishes, while an Xbox signaled connection only carries a placeholder address, and its session only starts with
 * the first packet after activation. The address is the remote side of the selected ICE pair: the TURN relay for a
 * relayed connection, and behind PROXY protocol relays the player the relay declared, with the relay as the proxy.
 * With "prefer-signaling-proxy-address" it is the player a trusted signaling proxy declared, with that proxy.
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
        // The endpoint only creates NetherNet channels
        SourcedAddress reported = ((NetherNetChannel) ctx.channel()).reportedAddress();
        if (!ConnectionRequests.accept(this.geyser, reported.address(), reported.via(), reported.source().name())) {
            ctx.close();
            return;
        }
        ctx.pipeline().remove(this);
        ctx.fireChannelActive();
    }
}
