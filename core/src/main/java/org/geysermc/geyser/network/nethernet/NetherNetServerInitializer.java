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
import io.github.sendablemetatype.netty.channel.nethernet.nxs.admission.AdmissionPrincipal;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.PacketDirection;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.network.GeyserServerInitializer;
import org.geysermc.geyser.network.bedrock.GameProtocol;

/**
 * Channel initializer for incoming Nethernet (WebRTC) connections, the only
 * Bedrock transport. Data channel payloads are bare batch bytes, without the
 * 0xFE frame ID the Protocol library's initializer expects. The channel
 * itself splits and reassembles messages, so each read is one whole batch.
 * The pipeline is:
 *
 * NetherNetBatchWrapperCodec (whole messages to batch wrappers)
 *   -> CompressionCodec (NOOP until NetworkSettings negotiates, rak v11 semantics)
 *     -> BedrockBatchDecoder/Encoder
 *       -> BedrockPacketCodec_v3
 *         -> NetherNetGeyserPeer -> GeyserSession
 *
 * There is no Bedrock encryption layer: DTLS secures the transport.
 */
public class NetherNetServerInitializer extends GeyserServerInitializer {

    private final ChannelGroup playerChannels;
    private final NetherNetConnectionGate connectionGate;
    private final NetherNetJoinLog joinLog;
    private final @Nullable GameOutcomeReporter gameOutcomes;

    /**
     * @param gameOutcomes reports what became of the players NXS admitted, or null when NXS does not run
     */
    public NetherNetServerInitializer(GeyserImpl geyser, EventLoopGroup eventLoopGroup, ChannelGroup playerChannels,
                                      NetherNetJoinLog joinLog, @Nullable GameOutcomeReporter gameOutcomes) {
        super(geyser, eventLoopGroup);
        this.playerChannels = playerChannels;
        this.connectionGate = new NetherNetConnectionGate(geyser);
        this.joinLog = joinLog;
        this.gameOutcomes = gameOutcomes;
    }

    @Override
    protected void preInitChannel(Channel channel) {
        // No super call: the parent adds the 0xFE frame ID codec, which
        // NetherNet does not use.

        // Runs as the endpoint exposes the join, before ICE, so joins that
        // never connect are logged too.
        if (channel instanceof NetherNetChildChannel child) {
            joinLog.joined(child);
        }

        // Register for clean disconnect on a real teardown. The group removes
        // the channel automatically when it closes.
        playerChannels.add(channel);

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
    protected void postInitChannel(Channel channel) throws Exception {
        super.postInitChannel(channel);
        // NXS marks the children it admitted before the endpoint exposes them
        if (gameOutcomes != null && channel.attr(AdmissionPrincipal.KEY).get() != null) {
            channel.pipeline().addBefore(BedrockPeer.NAME, GameOutcomeReporter.HANDLER_NAME,
                    gameOutcomes.observer(GameProtocol.SUPPORTED_BEDROCK_PROTOCOLS::contains));
        }
    }

    @Override
    protected BedrockPeer createPeer(Channel channel) {
        return new NetherNetGeyserPeer(channel, this::createSession);
    }
}
