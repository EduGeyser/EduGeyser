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

package org.geysermc.geyser.command.defaults;

import io.github.sendablemetatype.netty.channel.nethernet.nxs.ProviderClient;
import io.github.sendablemetatype.netty.channel.nethernet.nxs.ServerStatus;
import io.github.sendablemetatype.netty.channel.nethernet.nxs.host.DiagnosticResult;
import io.github.sendablemetatype.netty.channel.nethernet.nxs.transport.AddressFamily;
import io.netty.util.NetUtil;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.util.TriState;
import org.geysermc.geyser.command.GeyserCommand;
import org.geysermc.geyser.command.GeyserCommandSource;
import org.geysermc.geyser.network.nethernet.NetherNetNxs;
import org.geysermc.geyser.network.nethernet.NetherNetServer;
import org.geysermc.geyser.network.nethernet.NethernetManagerImpl;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.incendo.cloud.parser.standard.StringParser.greedyStringParser;

/**
 * {@code geyser nethernet}: what NetherNet's signaling does, from the console only, as upstream Geyser's command.
 * {@code status}, {@code action} and {@code diagnostics} concern the signaling service (NXS), {@code advertised} every
 * source.
 */
public class NetherNetCommand extends GeyserCommand {
    private static final String ARGUMENTS = "arguments";
    private static final String NXS_NOT_RUNNING = "The signaling service (NXS) is not running.";

    private final GeyserImpl geyser;

    public NetherNetCommand(GeyserImpl geyser, String name, String description, String permission) {
        super(name, description, permission, TriState.NOT_SET);
        this.geyser = geyser;
    }

    @Override
    public void register(CommandManager<GeyserCommandSource> manager) {
        manager.command(baseBuilder(manager).handler(this::execute));
        manager.command(baseBuilder(manager)
            .literal("status")
            .optional(ARGUMENTS, greedyStringParser())
            .handler(this::status));
        manager.command(baseBuilder(manager).literal("action").handler(this::action));
        manager.command(baseBuilder(manager).literal("diagnostics").handler(this::diagnostics));
        manager.command(baseBuilder(manager).literal("advertised").handler(this::advertised));
    }

    @Override
    public void execute(CommandContext<GeyserCommandSource> context) {
        context.sender().sendMessage("Usage: " + rootCommand() + " " + name() + " <status | action | diagnostics | advertised>");
    }

    /**
     * Shows or replaces the listing the signaling service shows for this server
     */
    private void status(CommandContext<GeyserCommandSource> context) {
        GeyserCommandSource source = context.sender();
        NetherNetNxs nxs = nxs(source);
        if (nxs == null) {
            return;
        }

        String[] args = context.<String>optional(ARGUMENTS).map(value -> value.trim().split("\\s+")).orElse(new String[0]);
        try {
            if (args.length == 0) {
                source.sendMessage(GeyserImpl.GSON.toJson(nxs.serverStatus()));
            } else if (args.length == 1 && args[0].equals("automatic")) {
                nxs.restoreAutomaticServerStatus();
                source.sendMessage("Automatic Geyser status restored.");
            } else if (args.length == 2 && args[0].equals("set") && args[1].length() <= 4096) {
                String json = new String(Base64.getUrlDecoder().decode(args[1]), StandardCharsets.UTF_8);
                nxs.setServerStatus(GeyserImpl.GSON.fromJson(json, ServerStatus.class));
                source.sendMessage("Complete provider status queued.");
            } else {
                source.sendMessage("Usage: " + rootCommand() + " " + name() + " status [automatic | set <base64url JSON snapshot>]");
            }
        } catch (RuntimeException invalid) {
            source.sendMessage("Invalid complete server status snapshot.");
        }
    }

    /**
     * Fetches the signaling service's current link for the operator, such as one to claim this server, into its file
     */
    private void action(CommandContext<GeyserCommandSource> context) {
        GeyserCommandSource source = context.sender();
        NetherNetNxs nxs = nxs(source);
        if (nxs == null) {
            return;
        }
        ProviderClient client = nxs.client();
        if (client == null) {
            source.sendMessage(NXS_NOT_RUNNING);
            return;
        }
        client.operatorAction().whenComplete((action, failure) -> {
            if (failure != null) {
                source.sendMessage("Could not get a link from the signaling service: " + NetherNetNxs.failure(failure));
                return;
            }
            try {
                String message = nxs.storeOperatorAction(action);
                source.sendMessage(message != null ? message : "The signaling service has no link for you right now.");
            } catch (IOException e) {
                source.sendMessage("Could not keep the link in a file: " + e.getMessage());
            }
        });
    }

    /**
     * Shows where NXS takes its players, what it could not report, the connectivity checks the service ran, and
     * whether the service routes players here
     */
    private void diagnostics(CommandContext<GeyserCommandSource> context) {
        GeyserCommandSource source = context.sender();
        NetherNetNxs nxs = nxs(source);
        if (nxs == null) {
            return;
        }
        ProviderClient client = nxs.client();
        if (client == null) {
            source.sendMessage(NXS_NOT_RUNNING);
            return;
        }

        if (geyser.nethernetManager() instanceof NethernetManagerImpl manager && manager.iceAddress() != null) {
            source.sendMessage("NXS players connect to UDP " + NetUtil.toSocketAddressString(manager.iceAddress()) + ".");
        }
        source.sendMessage("Game outcomes the signaling service did not take: " + nxs.gameOutcomes().droppedEvents() + ".");
        source.sendMessage(connectivityChecks(nxs.pollDiagnosticResults()));
        client.readiness().whenComplete((ready, failure) -> source.sendMessage(failure != null
            ? "Could not ask the signaling service whether it routes players here: " + NetherNetNxs.failure(failure)
            : ready.routable() ? "The signaling service routes players to this server."
            : "The signaling service does not route players to this server: " + String.join(", ", ready.reasons()) + "."));
    }

    /**
     * Prints the addresses each running signaling source sends to joining players
     */
    private void advertised(CommandContext<GeyserCommandSource> context) {
        GeyserCommandSource source = context.sender();
        NethernetManagerImpl manager = manager(source);
        if (manager == null) {
            return;
        }

        NetherNetServer.Announced announced = manager.announced();
        if (announced != null) {
            source.sendMessage(announced(announced, manager.httpSignalingAddress() != null));
        }

        NetherNetNxs nxs = manager.nxs();
        NetherNetNxs.Published published = nxs == null ? null : nxs.published();
        if (published != null) {
            source.sendMessage(nxsPublished(published.endpoints()));
        } else if (geyser.config().bedrock().signaling().mode().nxs()) {
            source.sendMessage(NXS_NOT_RUNNING);
        }
    }

    /** What this server's own answers announce, over HTTP signaling when it runs, and over Xbox signaling. */
    static String announced(NetherNetServer.Announced announced, boolean httpSignaling) {
        String subject = httpSignaling ? "HTTP and Xbox signaling advertise" : "Xbox signaling advertises";
        if (!announced.advertised().isEmpty()) {
            return subject + " " + join(announced.advertised()) + ".";
        }
        String own = subject + " this machine's own addresses";
        if (!announced.stunDiscovery()) {
            return own + ". STUN discovery is off.";
        }
        return announced.discovered().isEmpty()
            ? own + ". STUN discovery has not found a public address yet."
            : own + ", and " + join(announced.discovered()) + " found with STUN.";
    }

    static String nxsPublished(ProviderClient.@Nullable PublishedEndpoints published) {
        if (published == null) {
            return "The signaling service has confirmed no addresses right now.";
        }
        String message = published.endpoints().isEmpty()
            ? "The signaling service publishes no addresses."
            : "The signaling service publishes " + join(published.endpoints()) + ".";
        if (published.assistedFamilies().isEmpty()) {
            return message;
        }
        return message + " Assisted joins are offered over "
            + published.assistedFamilies().stream().sorted(Comparator.comparingInt(AddressFamily::number))
                .map(family -> "IPv" + family.number()).collect(Collectors.joining(" and ")) + ".";
    }

    /** The connectivity checks the service ran since the last look, by how they ended. */
    static String connectivityChecks(List<DiagnosticResult> results) {
        if (results.isEmpty()) {
            return "No connectivity checks since the last look.";
        }
        Map<DiagnosticResult.Outcome, Long> outcomes = results.stream()
            .collect(Collectors.groupingBy(DiagnosticResult::outcome, TreeMap::new, Collectors.counting()));
        return "Connectivity checks since the last look: " + outcomes.entrySet().stream()
            .map(entry -> entry.getValue() + " " + entry.getKey())
            .collect(Collectors.joining(", ")) + ".";
    }

    private static String join(List<InetSocketAddress> addresses) {
        return addresses.stream().map(NetUtil::toSocketAddressString).collect(Collectors.joining(", "));
    }

    /**
     * @return the running NetherNet manager, or null (after telling the source) if unavailable to this source
     */
    private @Nullable NethernetManagerImpl manager(GeyserCommandSource source) {
        if (!source.isConsole()) {
            source.sendMessage("This operation requires the server console.");
            return null;
        }
        if (!(geyser.nethernetManager() instanceof NethernetManagerImpl manager) || !manager.isRunning()) {
            source.sendMessage("NetherNet is not running.");
            return null;
        }
        return manager;
    }

    /**
     * @return NXS, or null (after telling the source) if unavailable to this source
     */
    private @Nullable NetherNetNxs nxs(GeyserCommandSource source) {
        NethernetManagerImpl manager = manager(source);
        if (manager == null) {
            return null;
        }
        NetherNetNxs nxs = manager.nxs();
        if (nxs == null) {
            source.sendMessage(NXS_NOT_RUNNING);
        }
        return nxs;
    }
}
