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

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.local.LocalIoHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.network.NethernetManager;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of {@link NethernetManager} that wraps {@link NetherNetServer}.
 * All state transitions are synchronized to prevent concurrent start/stop races.
 *
 * Owns the player threads every NetherNet session ticks on, so they outlive a
 * stop and start through the API; Geyser's shutdown releases them.
 */
public class NethernetManagerImpl implements NethernetManager {

    private final GeyserImpl geyser;
    private final EventLoopGroup playerEventLoopGroup;
    private final String connectionId;
    private final String playfabCustomId;
    private final String playfabDeviceId;
    private final Object lock = new Object();
    private volatile NetherNetServer server;

    public NethernetManagerImpl(GeyserImpl geyser, String connectionId, String playfabCustomId, String playfabDeviceId) {
        this.geyser = geyser;
        this.playerEventLoopGroup = new MultiThreadIoEventLoopGroup(new DefaultThreadFactory("Geyser player thread"),
                LocalIoHandler.newFactory());
        this.connectionId = connectionId;
        this.playfabCustomId = playfabCustomId;
        this.playfabDeviceId = playfabDeviceId;
    }

    @Override
    public boolean start() {
        synchronized (lock) {
            if (server != null && server.isRunning()) {
                return true;
            }
            // Shut down any existing zombie server before replacing
            if (server != null) {
                server.shutdown();
            }
            server = new NetherNetServer(geyser, playerEventLoopGroup, connectionId, playfabCustomId, playfabDeviceId);
            if (!server.start()) {
                server = null;
                return false;
            }
            return true;
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (server != null) {
                server.shutdown();
                server = null;
            }
        }
    }

    @Override
    public boolean isRunning() {
        NetherNetServer s = server;
        return s != null && s.isRunning();
    }

    @Override
    public boolean isSignalingAlive() {
        NetherNetServer s = server;
        return s != null && s.isSignalingAlive();
    }

    @Override
    public boolean restartSignaling() {
        synchronized (lock) {
            if (server == null) {
                return start();
            }
            return server.restartSignaling();
        }
    }

    @Override
    @NonNull
    public String getConnectionId() {
        return connectionId;
    }

    @Override
    public String getPmsgId() {
        NetherNetServer s = server;
        return s != null ? s.getPmsgId() : null;
    }

    /** Where the running server takes ICE traffic, or null when it is not running. */
    public @Nullable InetSocketAddress iceAddress() {
        NetherNetServer s = server;
        return s != null ? s.iceAddress() : null;
    }

    /** Where the running server serves HTTP signaling, or null when it is not running or HTTP signaling failed. */
    public @Nullable InetSocketAddress httpSignalingAddress() {
        NetherNetServer s = server;
        return s != null ? s.httpSignalingAddress() : null;
    }

    /**
     * Called during Geyser shutdown to release all Nethernet resources,
     * the player threads included.
     */
    public void shutdown() {
        stop();
        playerEventLoopGroup.shutdownGracefully(100, 500, TimeUnit.MILLISECONDS).syncUninterruptibly();
    }
}
