/*
 * Copyright (c) 2025 GeyserMC. http://geysermc.org
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

package org.geysermc.geyser.configuration;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.geyser.GeyserBootstrap;
import org.geysermc.geyser.GeyserLogger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.NodePath;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.transformation.ConfigurationTransformation;
import org.spongepowered.configurate.transformation.TransformAction;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.spongepowered.configurate.NodePath.path;
import static org.spongepowered.configurate.transformation.TransformAction.remove;
import static org.spongepowered.configurate.transformation.TransformAction.rename;

public class ConfigMigrations {

    /** Geyser's default until August 2026, which named the HAProxy switch "use-proxy-protocol". */
    private static final String OLD_HAPROXY_LIST_COMMENT = """
        A list of allowed HAPROXY protocol speaking proxy IP addresses/subnets. Only effective when "use-proxy-protocol" is enabled, and
        should really only be used when you are not able to use a proper firewall (usually true with shared hosting providers etc.).
        Keeping this list empty means there is no IP address whitelist.
        IP addresses, subnets, and links to plain text files are supported.""";
    /** The default up to EduGeyser's config version 7, from when the Bedrock port was RakNet's, UDP only. */
    private static final String OLD_PORT_COMMENT = """
        The port that Geyser will listen on for incoming Bedrock connections.
        Since Minecraft: Bedrock Edition uses UDP, this port must allow UDP traffic.""";
    /** Upstream Geyser's default, and EduGeyser's up to config version 7: it has operators firewall the Bedrock port on UDP only. */
    private static final String OLD_VALIDATE_LOGIN_COMMENT = """
        This option disables the auth step Geyser performs for connecting Bedrock players.
        It can be used to allow connections from ProxyPass and WaterdogPE. In these cases, make sure that users
        cannot directly connect to this Geyser instance. See https://www.spigotmc.org/wiki/firewall-guide/ for
        assistance - and use UDP instead of TCP.
        Disabling Bedrock authentication for other use-cases is NOT SUPPORTED, as it allows anyone to spoof usernames, and is therefore a security risk.
        All Floodgate functionality (including skin uploading and account linking) will also not work when this option is disabled.""";

    /**
     * Upstream Geyser's "port" comments, by the config versions it wrote them at: 5 to 8 (with its "will Geyser will"),
     * three times at 8, 8 to 10, and 10 to 12. Each says the port is UDP only, or that RakNet uses it, which EduGeyser
     * does not serve.
     */
    private static final List<String> UPSTREAM_PORT_COMMENTS = List.of("""
        The port that will Geyser will listen on for incoming Bedrock connections.
        Since Minecraft: Bedrock Edition uses UDP, this port must allow UDP traffic.""", """
        The port that will Geyser will listen on for incoming Bedrock connections.
        Since Minecraft: Bedrock Edition uses UDP, this port must allow UDP traffic.
        Built-in signalling also uses this port over TCP, unless it is the Java server's port.""", """
        The port that will Geyser will listen on for incoming Bedrock connections.
        Built-in signalling uses this port over TCP, unless it is the Java server's port, and RakNet uses it over UDP.""", """
        The port that will Geyser will listen on for incoming Bedrock connections.
        Built-in signaling uses this port over TCP, unless it is the Java server's port, and RakNet uses it over UDP.""", """
        The port that Geyser will listen on for incoming Bedrock connections.
        Built-in signaling uses this port over TCP, and RakNet uses it over UDP.""", """
        The port that Geyser will listen on for incoming Bedrock connections.
        Built-in signaling uses this port over TCP (and UDP if "webrtc-port" is not set), and RakNet uses it over UDP.""");
    /**
     * Upstream Geyser's "mode" comments, by the config versions it wrote them at: 8, 8 to 10, and 10 to 12. Each
     * promises a switch to the signaling service when built-in signaling cannot run, or a "none" mode upstream has
     * dropped since; EduGeyser does neither.
     */
    private static final List<String> UPSTREAM_MODE_COMMENTS = List.of("""
        Configures where signaling should be:
        "builtin" runs local HTTP signaling over TCP on the Bedrock address and port,
        "nxs" registers with the external NXS provider configured below, "hybrid" runs both, and "none" disables signaling.
        Built-in signaling cannot share the Java server's port, e.g. with clone-remote-port; the external signaling provider is used instead.""", """
        How Bedrock players find this server over NetherNet.
        "builtin": Geyser runs a local signaling server on the Bedrock port over TCP. This option is not compatible
        with running the Java server on the Bedrock port.
        "nxs": Geyser registers with the external signaling service configured below.
        "hybrid": uses both "builtin" and "nxs".
        "none" turns signaling off.""", """
        How Bedrock players find this server when using NetherNet.
        "builtin": Geyser runs its own signaling server on the Bedrock port, over TCP. This does not work if the Java server
        uses the same port (for example with "clone-remote-port"). In that case, Geyser automatically falls back to "nxs".
        "nxs": Geyser registers this server with the external signaling service configured in "nxs.endpoint" below.
        "hybrid": uses "builtin" and "nxs" at the same time.""");
    /** Upstream Geyser's comments on the "nxs" section, at config version 8 and from 8 to 12, with the same promise. */
    private static final List<String> UPSTREAM_NXS_COMMENTS = List.of(
        "Settings for the external NXS signaling provider. Only used in the \"nxs\" and \"hybrid\" modes, or when built-in signaling cannot run.",
        "Settings for the external signaling service. Only used in the \"nxs\" and \"hybrid\" modes, or when built-in signaling cannot run.");

    /**
     * Comments that upstream Geyser or an older EduGeyser wrote as defaults, each saying something untrue here. A
     * config keeps the comments of whatever wrote it, a Geyser config at EduGeyser's own version included, so
     * {@link #fixUp} replaces them at any version. Only these exact texts go: a comment the user wrote, or extended
     * with notes of their own, stays as it is.
     */
    private static final Map<NodePath, List<String>> STALE_COMMENTS = Map.of(
        path("bedrock", "port"), Stream.concat(Stream.of(OLD_PORT_COMMENT), UPSTREAM_PORT_COMMENTS.stream()).toList(),
        path("advanced", "bedrock", "validate-bedrock-login"), List.of(OLD_VALIDATE_LOGIN_COMMENT),
        path("bedrock", "signaling", "mode"), UPSTREAM_MODE_COMMENTS,
        path("bedrock", "signaling", "nxs"), UPSTREAM_NXS_COMMENTS);

    public static final BiFunction<Class<? extends GeyserConfig>, GeyserBootstrap, ConfigurationTransformation.Versioned> TRANSFORMER = (configClass, bootstrap) ->
        ConfigurationTransformation.versionedBuilder()
        .versionKey("config-version")
        .addVersion(5, ConfigurationTransformation.builder()
            // Java section
            .addAction(path("remote"), rename("java"))
            .addAction(path("remote", "address"), (path, value) -> {
                if ("auto".equals(value.getString())) {
                    // Auto-convert back to localhost
                    value.set("127.0.0.1");
                }
                return null;
            })

            // Motd section
            .addAction(path("bedrock", "motd1"), renameAndMove("motd", "primary-motd"))
            .addAction(path("bedrock", "motd2"), renameAndMove("motd", "secondary-motd"))
            .addAction(path("passthrough-motd"), moveTo("motd"))
            .addAction(path("passthrough-player-counts"), moveTo("motd"))
            .addAction(path("ping-passthrough-interval"), moveTo("motd"))
            .addAction(path("max-players"), moveTo("motd"))
            .addAction(path("legacy-ping-passthrough"), configClass == GeyserRemoteConfig.class ? remove() : (path, value) -> {
                // Invert value
                value.set(!value.getBoolean());
                return new Object[]{ "motd", "integrated-ping-passthrough" };
            })

            // gameplay
            .addAction(path("command-suggestions"), moveTo("gameplay"))
            .addAction(path("forward-player-ping"), moveTo("gameplay"))
            .addAction(path("show-cooldown"), (path, value) -> {
                String s = value.getString();
                if (s != null) {
                    switch (s) {
                        case "true" -> value.set("title");
                        case "false" -> value.set("disabled");
                    }
                }
                return new Object[]{ "gameplay", "show-cooldown" };
            })
            .addAction(path("bedrock", "server-name"), moveTo("gameplay"))
            .addAction(path("show-coordinates"), moveTo("gameplay"))
            .addAction(path("disable-bedrock-scaffolding"), moveTo("gameplay"))
            .addAction(path("custom-skull-render-distance"), moveTo("gameplay"))
            .addAction(path("force-resource-packs"), moveTo("gameplay"))
            .addAction(path("xbox-achievements-enabled"), moveTo("gameplay"))
            .addAction(path("unusable-space-block"), moveTo("gameplay"))
            .addAction(path("unusable-space-block"), moveTo("gameplay"))
            .addAction(path("add-non-bedrock-items"), renameAndMove("gameplay", "enable-custom-content"))
            .addAction(path("above-bedrock-nether-building"), renameAndMove("gameplay", "nether-roof-workaround"))
            .addAction(path("xbox-achievements-enabled"), moveTo("gameplay"))
            // NOTE: We're not explicitly removing the allow-custom-skulls option, it will already be removed since it
            // won't be written back. If we remove it, we can't query the value of it!
            .addAction(path("max-visible-custom-skulls"), (path, value) -> {
                ConfigurationNode parent = value.parent();
                if (parent != null && parent.isMap()) {
                    ConfigurationNode allowCustomSkulls = parent.childrenMap().get("allow-custom-skulls");
                    if (allowCustomSkulls != null && !allowCustomSkulls.getBoolean()) {
                        value.set(0);
                    }
                }
                return new Object[]{ "gameplay", "max-visible-custom-skulls" };
            })
            .addAction(path("emote-offhand-workaround"), (path,  value) -> {
                String previous = value.getString();
                if (!Objects.equals(previous, "disabled") && bootstrap != null) {
                    bootstrap.getGeyserLogger().warning("The emote-offhand-workaround option has been removed from Geyser. If you still wish to have this functionality, use this Geyser extension: https://github.com/GeyserMC/EmoteOffhandExtension/");
                }
                if (Objects.equals(previous, "no-emotes")) {
                    value.set(false);
                    return new Object[]{ "gameplay", "emotes-enabled" };
                }
                return null;
            })

            // For the warning!
            .addAction(path("allow-third-party-capes"), (node, value) -> {
                if (bootstrap != null) {
                    bootstrap.getGeyserLogger().warning("Third-party ears/capes have been removed from Geyser. If you still wish to have this functionality, use this Geyser extension: https://github.com/GeyserMC/ThirdPartyCosmetics");
                }
                return null;
            })
            .addAction(path("allow-third-party-ears"), (node, value) -> {
                if (bootstrap != null) {
                    bootstrap.getGeyserLogger().warning("Third-party ears/capes have been removed from Geyser. If you still wish to have this functionality, use this Geyser extension: https://github.com/GeyserMC/ThirdPartyCosmetics");
                }
                return null;
            })

            // Advanced section
            .addAction(path("cache-images"), moveTo("advanced"))
            .addAction(path("scoreboard-packet-threshold"), moveTo("advanced"))
            .addAction(path("add-team-suggestions"), moveTo("advanced"))
            .addAction(path("floodgate-key-file"), (path, value) -> {
                // Elimate any legacy config values
                if ("public-key.pem".equals(value.getString())) {
                    value.set("key.pem");
                }
                return new Object[]{ "advanced", "floodgate-key-file" };
            })

            // Bedrock
            .addAction(path("bedrock", "broadcast-port"), moveTo("advanced", "bedrock"))
            .addAction(path("bedrock", "compression-level"), moveTo("advanced", "bedrock"))
            .addAction(path("bedrock", "enable-proxy-protocol"), renameAndMove("advanced", "bedrock", "use-haproxy-protocol"))
            .addAction(path("bedrock", "proxy-protocol-whitelisted-ips"), renameAndMove("advanced", "bedrock", "haproxy-protocol-whitelisted-ips"))
            .addAction(path("mtu"), moveTo("advanced", "bedrock"))

            // Java
            .addAction(path("remote", "use-proxy-protocol"), renameAndMove("advanced", "java", "use-haproxy-protocol"))
            .addAction(path("disable-compression"), moveTo("advanced", "java"))
            .addAction(path("use-direct-connection"), moveTo("advanced", "java"))

            // Other
            .addAction(path("default-locale"), (path, value) -> {
                if (value.getString() == null) {
                    value.set("system");
                }
                return null;
            })
            .addAction(path("metrics", "uuid"), (path, value) -> {
                if ("generateduuid".equals(value.getString())) {
                    // Manually copied config without Metrics UUID creation?
                    value.set(UUID.randomUUID());
                }
                return new Object[]{ "metrics-uuid" };
            })
            .addAction(path("metrics", "enabled"), (path, value) -> {
                // Move to the root, not in the Metrics class.
                return new Object[]{ "enable-metrics" };
            })

            .build())
            .addVersion(6, ConfigurationTransformation.builder()
                .addAction(path("gameplay", "show-cooldown"), (path, value) -> {
                    String s = value.getString();
                    if (s != null && !"disabled".equals(s)) {
                        value.set("crosshair");
                    }
                    return new Object[]{ "gameplay", "show-cooldown" };
                })
                .build())
            .addVersion(7, ConfigurationTransformation.builder()
                .addAction(path("gameplay", "show-cooldown"), rename(new Object[] { "gameplay", "cooldown-type" }))
                .build())
            // From here on each version number means what it means in upstream Geyser, so a config moves between
            // Geyser and EduGeyser in both directions. Keys only EduGeyser has take no version number.
            // Upstream's NetherNet options (8), advertise-addresses (9) and NXS options (10) are new keys only
            .addVersion(8, ConfigurationTransformation.empty())
            .addVersion(9, ConfigurationTransformation.empty())
            .addVersion(10, ConfigurationTransformation.empty())
            // Upstream's one advertise-addresses list and stun-discovery switch for every signaling mode
            .addVersion(11, ConfigurationTransformation.builder()
                .addAction(path("bedrock", "signaling"), (path, value) -> {
                    Set<String> addresses = new LinkedHashSet<>();
                    for (String mode : List.of("builtin", "nxs")) {
                        ConfigurationNode side = value.node(mode);
                        side.node("advertise-addresses").getList(String.class, List.of()).stream()
                            .map(String::trim)
                            .filter(address -> !address.isEmpty())
                            .forEach(addresses::add);
                        side.removeChild("advertise-addresses");
                    }
                    if (!addresses.isEmpty()) {
                        value.node("advertise-addresses").setList(String.class, List.copyOf(addresses));
                    }
                    return null;
                })
                .addAction(path("advanced", "nxs", "stun-discovery"), (path, value) ->
                    new Object[]{ "bedrock", "signaling", "stun-discovery" })
                .build())
            // RakNet is gone: listing HAProxy proxies turns the protocol on for HTTP signaling, and the keys only
            // RakNet read ("mtu", "broadcast-port") are not written back. Unlike upstream's, the fold treats a
            // missing switch as off, and "prefer-signaling-proxy-address" keeps its default: upstream sets it for
            // configs with proxies, to keep a behavior of its own built-in signaling that EduGeyser never had
            .addVersion(12, ConfigurationTransformation.builder()
                .addAction(path("advanced", "bedrock"), (path, value) -> {
                    foldHaproxySwitch(value, bootstrap == null ? null : bootstrap.getGeyserLogger());
                    // 0, the default, broadcast the Bedrock port
                    if (value.node("broadcast-port").getInt(0) != 0 && bootstrap != null) {
                        bootstrap.getGeyserLogger().warning("\"broadcast-port\" under \"advanced.bedrock\" has been removed: "
                            + "NetherNet has no broadcast port, players connect to the port they enter.");
                    }
                    return null;
                })
                .build())
        .build();

    /**
     * EduGeyser's own fixes, which run at any config version after the migrations, as upstream Geyser runs its
     * moveRakNetOnly: they change nothing a version number stands for. For now they replace {@link #STALE_COMMENTS}.
     *
     * @return whether anything changed, so the loader writes the config back
     */
    static boolean fixUp(CommentedConfigurationNode root) {
        boolean changed = false;
        for (Map.Entry<NodePath, List<String>> stale : STALE_COMMENTS.entrySet()) {
            CommentedConfigurationNode node = root.node(stale.getKey());
            String comment = node.comment();
            if (comment != null && stale.getValue().stream().anyMatch(text -> sameText(comment, text))) {
                node.comment(null);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Folds the "use-haproxy-protocol" switch into "haproxy-protocol-whitelisted-ips", which turns HAProxy protocol on
     * by naming proxies. Fails closed: a list kept while the switch was off is emptied, also when the switch is missing,
     * since it defaulted to off, and a switch that was on with an empty list, which took a header from any address,
     * leaves HAProxy protocol off.
     */
    private static void foldHaproxySwitch(ConfigurationNode advancedBedrock, @Nullable GeyserLogger logger) throws SerializationException {
        ConfigurationNode proxies = advancedBedrock.node("haproxy-protocol-whitelisted-ips");
        // A comment naming the removed switch is stale, so the current one is written instead. The old default named
        // it wrong, so only that exact text goes, not a comment of the user's own.
        if (proxies instanceof CommentedConfigurationNode commented && commented.comment() != null
                && (commented.comment().contains("use-haproxy-protocol") || isComment(commented, OLD_HAPROXY_LIST_COMMENT))) {
            commented.comment(null);
        }

        // A missing switch is off, its default: version 5 moves the list of an older config here without one
        ConfigurationNode useHaproxy = advancedBedrock.node("use-haproxy-protocol");
        if (!useHaproxy.getBoolean(false)) {
            // Ignored while it was off, and would turn it on now. Emptied rather than removed, so a comment of the user's own stays
            if (!proxies.virtual()) {
                proxies.setList(String.class, List.of());
            }
        } else if (logger != null) {
            if (proxies.getList(String.class, List.of()).isEmpty()) {
                logger.warning("\"use-haproxy-protocol\" has been removed: listing proxies in \"haproxy-protocol-whitelisted-ips\" "
                    + "under \"advanced.bedrock\" now turns HAProxy protocol on. Yours was on with an empty list, which took a "
                    + "HAProxy header from any address, so it is off until you list your proxies there.");
            } else {
                logger.warning("\"use-haproxy-protocol\" has been removed: the proxies in \"haproxy-protocol-whitelisted-ips\" "
                    + "under \"advanced.bedrock\" now apply to HTTP signaling, the TCP side of the Bedrock port. A UDP proxy that "
                    + "sends PROXY protocol in front of NetherNet has to be listed in \"webrtc-proxy-protocol-relays\".");
            }
        }
        if (!useHaproxy.virtual()) {
            useHaproxy.raw(null);
        }
    }

    private static boolean isComment(CommentedConfigurationNode node, String text) {
        return node.comment() != null && sameText(node.comment(), text);
    }

    /** Whether a comment is the text, line by line: a YAML comment keeps neither indentation nor trailing spaces. */
    private static boolean sameText(String comment, String text) {
        return comment.strip().lines().map(String::strip).toList()
            .equals(text.strip().lines().map(String::strip).toList());
    }

    static TransformAction renameAndMove(String... newPath) {
        return ((path, value) -> Arrays.stream(newPath).toArray());
    }

    static TransformAction moveTo(String... newPath) {
        return (path, value) -> {
            Object[] arr = path.array();
            if (arr.length == 0) {
                throw new ConfigurateException(value, "The root node cannot be renamed!");
            } else {
                // create a new array with space for newPath segments + the original last segment
                Object[] result = new Object[newPath.length + 1];
                System.arraycopy(newPath, 0, result, 0, newPath.length);
                result[newPath.length] = arr[arr.length - 1];
                return result;
            }
        };
    }
}
