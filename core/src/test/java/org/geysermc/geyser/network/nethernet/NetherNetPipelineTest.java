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
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.protocol.bedrock.PacketDirection;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.netty.codec.batch.BedrockBatchDecoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.batch.BedrockBatchEncoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockChannelInitializer;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assembles the NetherNet pipeline as NetherNetServerInitializer builds it on
 * top of the channel (batch wrapper, compression, batch, packet codec) and
 * feeds it the first message a Bedrock client sends. The channel delivers
 * whole messages, so each read is one batch.
 */
class NetherNetPipelineTest {

    private static final int PROTOCOL_VERSION = Bedrock_v2168.CODEC.getProtocolVersion();

    @Test
    void reliableMessageDecodesAndTheAnswerLeavesAsOneBatch() {
        List<Object> received = new ArrayList<>();
        EmbeddedChannel channel = pipeline(received);

        channel.writeInbound(requestNetworkSettings());

        assertReceivedRequestNetworkSettings(received);

        NetworkSettingsPacket response = new NetworkSettingsPacket();
        response.setCompressionAlgorithm(PacketCompressionAlgorithm.ZLIB);
        response.setCompressionThreshold(512);
        assertTrue(channel.writeOutbound(BedrockPacketWrapper.create(0, 0, 0, response, null)));

        // A plain ByteBuf is a reliable write, which every batch must be
        Object outbound = channel.readOutbound();
        ByteBuf batch = assertInstanceOf(ByteBuf.class, outbound);
        assertTrue(batch.readableBytes() > 0);
        batch.release();

        assertFalse(channel.finish());
    }

    @Test
    void unreliableMessageDecodesLikeAReliableOne() {
        List<Object> received = new ArrayList<>();
        EmbeddedChannel channel = pipeline(received);

        channel.writeInbound(new NetherNetPacket(requestNetworkSettings(), false));

        assertReceivedRequestNetworkSettings(received);
        assertFalse(channel.finish());
    }

    /**
     * The first message of a Bedrock client: one batch holding RequestNetworkSettings, packet
     * length 6, the v3 header varuint for packet id 193, then the protocol version as a big
     * endian int.
     */
    private static ByteBuf requestNetworkSettings() {
        ByteBuf batch = Unpooled.buffer(7);
        batch.writeByte(0x06);
        batch.writeByte(0xC1);
        batch.writeByte(0x01);
        batch.writeInt(PROTOCOL_VERSION);
        return batch;
    }

    private static void assertReceivedRequestNetworkSettings(List<Object> received) {
        assertEquals(1, received.size());
        BedrockPacketWrapper wrapper = assertInstanceOf(BedrockPacketWrapper.class, received.getFirst());
        RequestNetworkSettingsPacket packet = assertInstanceOf(RequestNetworkSettingsPacket.class, wrapper.getPacket());
        assertEquals(PROTOCOL_VERSION, packet.getProtocolVersion());
        wrapper.release();
    }

    private static EmbeddedChannel pipeline(List<Object> received) {
        BedrockPacketCodec packetCodec = new BedrockPacketCodec_v3();
        packetCodec.setCodec(Bedrock_v2168.CODEC);

        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(PacketDirection.ATTRIBUTE).set(PacketDirection.CLIENT_BOUND);
        channel.pipeline()
                .addLast(NetherNetBatchWrapperCodec.NAME, NetherNetBatchWrapperCodec.INSTANCE)
                .addLast(CompressionCodec.NAME, new CompressionCodec(
                        BedrockChannelInitializer.getCompression(PacketCompressionAlgorithm.ZLIB, 11, true), false))
                .addLast(BedrockBatchDecoder.NAME, new BedrockBatchDecoder())
                .addLast(BedrockBatchEncoder.NAME, new BedrockBatchEncoder())
                .addLast(BedrockPacketCodec.NAME, packetCodec)
                .addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        received.add(msg);
                    }
                });
        return channel;
    }
}
