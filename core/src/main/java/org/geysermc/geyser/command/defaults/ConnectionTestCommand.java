/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
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

package org.geysermc.geyser.command.defaults;

import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.util.TriState;
import org.geysermc.geyser.command.GeyserCommand;
import org.geysermc.geyser.command.GeyserCommandSource;
import org.geysermc.geyser.configuration.GeyserConfig;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;

import static org.incendo.cloud.parser.standard.StringParser.greedyStringParser;

/**
 * Explains why the connection test is not available, and points out proxy settings that keep
 * players out. The test pinged the server through GeyserMC's checker, which pings over RakNet,
 * and EduGeyser serves NetherNet only, so every test would report the server unreachable.
 */
public class ConnectionTestCommand extends GeyserCommand {

    private static final String ADDRESS = "address";

    private final GeyserImpl geyser;

    public ConnectionTestCommand(GeyserImpl geyser, String name, String description, String permission) {
        super(name, description, permission, TriState.NOT_SET);
        this.geyser = geyser;
    }

    @Override
    public void register(CommandManager<GeyserCommandSource> manager) {
        manager.command(baseBuilder(manager)
            // Still accepted, so the old usage gets the explanation rather than a syntax error
            .optional(ADDRESS, greedyStringParser())
            .handler(this::execute));
    }

    @Override
    public void execute(CommandContext<GeyserCommandSource> context) {
        GeyserConfig.BedrockConfig listener = geyser.config().bedrock();
        GeyserCommandSource source = context.sender();
        source.sendMessage("The connection test is not available: it pinged over RakNet, which EduGeyser does not "
                + "serve, so it cannot check this server.");
        // The "nxs" signaling mode runs no HTTP signaling, so its TCP port needs nothing
        source.sendMessage("Bedrock players join over NetherNet: make sure UDP port " + listener.icePort() + " (NetherNet)"
                + (listener.signaling().mode().builtin()
                ? " and TCP port " + listener.signalingPort() + " (HTTP signaling) are" : " is")
                + " open and forwarded to this server.");

        GeyserConfig.AdvancedBedrockConfig bedrock = geyser.config().advanced().bedrock();
        // Issue: did someone list HAProxy proxies, and they didn't mean it?
        if (!bedrock.haproxyProtocolWhitelistedIps().isEmpty()) {
            source.sendMessage("You have proxies listed in the `haproxy-protocol-whitelisted-ips` setting, which turns HAProxy protocol on. "
                    + "Unless you're deliberately using additional software that REQUIRES this setting, you may not need it.");
        }
        // Issue: did someone list WebRTC relays, and they didn't mean it?
        if (!bedrock.webrtcProxyProtocolRelays().isEmpty()) {
            source.sendMessage("You have relays listed in the `webrtc-proxy-protocol-relays` setting, so players can only reach NetherNet through them. "
                    + "Unless you're deliberately using additional software that REQUIRES this setting, you may not need it.");
        }
    }
}
