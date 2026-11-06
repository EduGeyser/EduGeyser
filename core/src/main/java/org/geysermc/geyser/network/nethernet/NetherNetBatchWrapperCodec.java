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

import io.github.sendablemetatype.netty.channel.nethernet.NetherNetPacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;

import java.util.List;

/**
 * Converts between the whole messages a NetherNet channel exchanges and the
 * BedrockBatchWrapper messages the transport neutral Bedrock layers
 * exchange. This is the NetherNet counterpart of what FrameIdCodec does for
 * RakNet, minus the 0xFE frame id byte, which does not exist on NetherNet:
 * the wrapper conversion is the part of that codec every transport needs,
 * and CompressionCodec and BedrockBatchDecoder silently pass through
 * anything that is not a BedrockBatchWrapper.
 *
 * Reliable messages arrive as a ByteBuf, unreliable ones as a NetherNetPacket.
 * Both carry a batch and are read alike; batches are always written reliably.
 */
@Sharable
public class NetherNetBatchWrapperCodec extends MessageToMessageCodec<Object, BedrockBatchWrapper> {

    public static final String NAME = "nethernet-batch-wrapper-codec";
    public static final NetherNetBatchWrapperCodec INSTANCE = new NetherNetBatchWrapperCodec();

    private NetherNetBatchWrapperCodec() {
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, BedrockBatchWrapper msg, List<Object> out) throws Exception {
        if (msg.getCompressed() == null) {
            throw new IllegalStateException("Bedrock batch was not compressed");
        }
        out.add(msg.getCompressed().retainedSlice());
    }

    @Override
    public boolean acceptInboundMessage(Object msg) {
        return msg instanceof ByteBuf || msg instanceof NetherNetPacket;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, Object msg, List<Object> out) throws Exception {
        ByteBuf batch = switch (msg) {
            case ByteBuf reliable -> reliable;
            case NetherNetPacket unreliable -> unreliable.content();
            default -> throw new IllegalArgumentException("Not a NetherNet message: " + msg.getClass().getName());
        };
        out.add(BedrockBatchWrapper.newInstance(batch.readRetainedSlice(batch.readableBytes()), null));
    }
}
