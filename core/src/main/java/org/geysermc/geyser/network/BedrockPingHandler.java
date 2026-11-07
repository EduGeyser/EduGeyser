/*
 * Copyright (c) 2019-2026 GeyserMC. http://geysermc.org
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

package org.geysermc.geyser.network;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.util.JoinAddress;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.configuration.GeyserConfig;
import org.geysermc.geyser.event.type.GeyserBedrockPingEventImpl;
import org.geysermc.geyser.network.bedrock.GameProtocol;
import org.geysermc.geyser.ping.GeyserPingInfo;
import org.geysermc.geyser.ping.IGeyserPingPassthrough;
import org.geysermc.geyser.text.GeyserLocale;
import org.geysermc.geyser.translator.text.MessageTranslator;

import java.net.InetSocketAddress;

/**
 * Builds the server status that NetherNet's HTTP signaling serves, from the MOTD settings, ping
 * passthrough and the ping event.
 */
public final class BedrockPingHandler {
    private static final boolean PRINT_DEBUG_PINGS = Boolean.parseBoolean(System.getProperty("Geyser.PrintPingsInDebugMode", "true"));

    private final GeyserImpl geyser;

    public BedrockPingHandler(GeyserImpl geyser) {
        this.geyser = geyser;
    }

    /**
     * @param inetSocketAddress the address the query came from
     * @param hostname the hostname the client queried, such as an HTTP Host header; null if unknown
     * @return the status to answer the query with
     */
    public BedrockPong onQuery(InetSocketAddress inetSocketAddress, @Nullable String hostname) {
        return pong(inetSocketAddress, passthrough(inetSocketAddress, hostname)).pong();
    }

    /**
     * @return whether answering a query asks the Java server through the ping passthrough, which can block
     */
    public boolean asksPassthrough() {
        GeyserConfig config = geyser.config();
        return (config.motd().passthroughMotd() || config.motd().passthroughPlayerCounts())
            && geyser.getBootstrap().getGeyserPingPassthrough() != null;
    }

    /**
     * @return whether the passthrough's answer can differ by virtual host, so a cache of its answers keeps one per host
     */
    public boolean passthroughUsesVirtualHost() {
        IGeyserPingPassthrough pingPassthrough = geyser.getBootstrap().getGeyserPingPassthrough();
        return pingPassthrough != null && pingPassthrough.usesVirtualHost();
    }

    /**
     * Asks the Java server for the MOTD and player counts the passthrough settings relay. Can block for as long as
     * the server's ping event chain runs.
     *
     * @param inetSocketAddress the address the Java server's ping event sees
     * @param hostname the hostname the client queried, such as an HTTP Host header; null if unknown
     * @return the answer, or null when neither passthrough setting is on, there is no passthrough, or it gave none
     */
    public @Nullable GeyserPingInfo passthrough(InetSocketAddress inetSocketAddress, @Nullable String hostname) {
        GeyserConfig config = geyser.config();
        if (!config.motd().passthroughMotd() && !config.motd().passthroughPlayerCounts()) {
            return null;
        }
        IGeyserPingPassthrough pingPassthrough = geyser.getBootstrap().getGeyserPingPassthrough();
        if (pingPassthrough == null) {
            return null;
        }
        InetSocketAddress virtualHost = hostname == null || hostname.isBlank() ? null
            : JoinAddress.parse(hostname, config.bedrock().signalingPort()).toUnresolved();
        return pingPassthrough.getPingInformation(inetSocketAddress, virtualHost);
    }

    /**
     * Builds the status for one query from a passthrough answer, which the caller may have cached, and fires the
     * ping event with the address the query came from.
     *
     * @param inetSocketAddress the address the query came from
     * @param pingInfo the answer from {@link #passthrough}, or null for none
     * @return the status to answer the query with, and whether the ping event changed it
     */
    public PingAnswer pong(InetSocketAddress inetSocketAddress, @Nullable GeyserPingInfo pingInfo) {
        if (geyser.config().debugMode() && PRINT_DEBUG_PINGS) {
            String ip = geyser.config().logPlayerIpAddresses() ? inetSocketAddress.toString() : "<IP address withheld>";
            geyser.getLogger().debug(GeyserLocale.getLocaleStringLog("geyser.network.pinged", ip));
        }

        GeyserConfig config = geyser.config();

        BedrockPong pong = new BedrockPong()
                .gameType("Survival") // Can only be Survival or Creative as of 1.16.210.59
                .protocolVersion(GameProtocol.DEFAULT_BEDROCK_PROTOCOL)
                .version(GameProtocol.DEFAULT_BEDROCK_VERSION);

        if (config.motd().passthroughMotd() && pingInfo != null && pingInfo.getDescription() != null) {
            String[] motd = MessageTranslator.convertToPlainTextLenient(pingInfo.getDescription(), GeyserLocale.getDefaultLocale()).split("\n");
            String mainMotd = (motd.length > 0) ? motd[0] : config.motd().primaryMotd(); // First line of the motd.
            String subMotd = (motd.length > 1) ? motd[1] : config.motd().secondaryMotd(); // Second line of the motd if present, otherwise default.

            pong.motd(mainMotd.trim());
            pong.subMotd(subMotd.trim()); // Trimmed to shift it to the left, prevents the universe from collapsing on us just because we went 2 characters over the text box's limit.
        } else {
            pong.motd(config.motd().primaryMotd());
            pong.subMotd(config.motd().secondaryMotd());
        }

        // Placed here to prevent overriding values set in the ping event.
        if (config.motd().passthroughPlayerCounts() && pingInfo != null) {
            pong.playerCount(pingInfo.getPlayers().getOnline());
            pong.maximumPlayerCount(pingInfo.getPlayers().getMax());
        } else {
            pong.playerCount(geyser.getSessionManager().getSessions().size());
            pong.maximumPlayerCount(config.motd().maxPlayers());
        }

        GeyserBedrockPingEventImpl event = new GeyserBedrockPingEventImpl(pong, inetSocketAddress);
        this.geyser.eventBus().fire(event);

        // Fallbacks to prevent errors and allow Bedrock to see the server
        if (pong.motd() == null || pong.motd().isBlank()) {
            pong.motd(GeyserImpl.NAME);
        }
        if (pong.subMotd() == null || pong.subMotd().isBlank()) {
            // Sub-MOTD cannot be empty as of 1.16.210.59
            pong.subMotd(GeyserImpl.NAME);
        }

        //Bedrock will not even attempt a connection if the client thinks the server is full
        //so we have to fake it not being full
        if (pong.playerCount() >= pong.maximumPlayerCount()) {
            pong.maximumPlayerCount(pong.playerCount() + 1);
        }

        return new PingAnswer(pong, event.changedPong());
    }

    /**
     * @param pong the status to answer the query with
     * @param eventChanged whether the ping event changed it, so it may have been chosen for the address that asked
     */
    public record PingAnswer(BedrockPong pong, boolean eventChanged) {
    }
}
