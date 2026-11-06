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

import io.github.sendablemetatype.netty.channel.nethernet.NetherNetChildChannel;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.PacketDirection;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.network.GeyserServerInitializer;
import org.geysermc.geyser.network.netty.BedrockEncryptionControl;

/**
 * Channel initializer for incoming Nethernet (WebRTC) connections. Session
 * setup is shared with RakNet through GeyserServerInitializer, but NetherNet
 * is a first class transport here: no FrameIdCodec (the 0xFE byte is RakNet
 * framing; data channel payloads are bare batch bytes) and no RakMessage
 * wrapping. The channel itself splits and reassembles messages, so each read
 * is one whole batch. The pipeline is:
 *
 * NetherNetBatchWrapperCodec (whole messages to batch wrappers)
 *   -> CompressionCodec (NOOP until NetworkSettings negotiates, rak v11 semantics)
 *     -> BedrockBatchDecoder/Encoder
 *       -> BedrockPacketCodec_v3
 *         -> NetherNetGeyserPeer -> GeyserSession
 *
 * Bedrock layer encryption stays disabled: DTLS already secures the
 * transport, and clients expect none on NetherNet.
 */
public class NetherNetServerInitializer extends GeyserServerInitializer {

    private final ChannelGroup playerChannels;
    private final NetherNetConnectionGate connectionGate;
    private final NetherNetJoinLog joinLog;

    public NetherNetServerInitializer(GeyserImpl geyser, EventLoopGroup eventLoopGroup, ChannelGroup playerChannels,
                                      NetherNetJoinLog joinLog) {
        super(geyser, eventLoopGroup);
        this.playerChannels = playerChannels;
        this.connectionGate = new NetherNetConnectionGate(geyser);
        this.joinLog = joinLog;
    }

    @Override
    protected void preInitChannel(Channel channel) {
        // No super call: the parent adds the RakNet framing.

        // Runs as the endpoint exposes the join, before ICE, so joins that
        // never connect are logged too.
        if (channel instanceof NetherNetChildChannel child) {
            joinLog.joined(child);
        }

        // Register for clean disconnect on a real teardown. The group removes
        // the channel automatically when it closes.
        playerChannels.add(channel);

        // Disable Bedrock encryption - Nethernet uses DTLS for transport encryption
        BedrockEncryptionControl.disableEncryption(channel);

        // Set packet direction for server-side codec
        channel.attr(PacketDirection.ATTRIBUTE).set(PacketDirection.CLIENT_BOUND);

        channel.pipeline()
                .addLast(NetherNetConnectionGate.NAME, this.connectionGate)
                .addLast(NetherNetBatchWrapperCodec.NAME, NetherNetBatchWrapperCodec.INSTANCE)
                .addLast(CompressionCodec.NAME, new CompressionCodec(
                        getCompression(PacketCompressionAlgorithm.ZLIB, 11, true), false));
    }

    @Override
    protected void initPacketCodec(Channel channel) {
        channel.pipeline().addLast(BedrockPacketCodec.NAME, new BedrockPacketCodec_v3());
    }

    @Override
    protected BedrockPeer createPeer(Channel channel) {
        return new NetherNetGeyserPeer(channel, this::createSession);
    }
}
