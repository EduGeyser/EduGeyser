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
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.transformation.ConfigurationTransformation;
import org.spongepowered.configurate.transformation.TransformAction;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;

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
    /** The default up to config version 7, from when the Bedrock port was RakNet's, UDP only. */
    private static final String OLD_PORT_COMMENT = """
        The port that Geyser will listen on for incoming Bedrock connections.
        Since Minecraft: Bedrock Edition uses UDP, this port must allow UDP traffic.""";
    /** The default up to config version 7, which had operators firewall the Bedrock port on UDP only. */
    private static final String OLD_VALIDATE_LOGIN_COMMENT = """
        This option disables the auth step Geyser performs for connecting Bedrock players.
        It can be used to allow connections from ProxyPass and WaterdogPE. In these cases, make sure that users
        cannot directly connect to this Geyser instance. See https://www.spigotmc.org/wiki/firewall-guide/ for
        assistance - and use UDP instead of TCP.
        Disabling Bedrock authentication for other use-cases is NOT SUPPORTED, as it allows anyone to spoof usernames, and is therefore a security risk.
        All Floodgate functionality (including skin uploading and account linking) will also not work when this option is disabled.""";

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
            // RakNet is gone: listing HAProxy proxies turns the protocol on for HTTP signaling, and the keys only
            // RakNet read ("mtu", "broadcast-port") are not written back
            .addVersion(8, ConfigurationTransformation.builder()
                .addAction(path("advanced", "bedrock"), (path, value) -> {
                    foldHaproxySwitch(value, bootstrap == null ? null : bootstrap.getGeyserLogger());
                    // 0, the default, broadcast the Bedrock port
                    if (value.node("broadcast-port").getInt(0) != 0 && bootstrap != null) {
                        bootstrap.getGeyserLogger().warning("\"broadcast-port\" under \"advanced.bedrock\" has been removed: "
                            + "NetherNet has no broadcast port, players connect to the port they enter.");
                    }
                    return null;
                })
                .addAction(path("bedrock", "port"), replaceOldComment(OLD_PORT_COMMENT))
                .addAction(path("advanced", "bedrock", "validate-bedrock-login"), replaceOldComment(OLD_VALIDATE_LOGIN_COMMENT))
                .build())
        .build();

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

    /** Drops a comment that is still an old default, so the current one is written; a comment of the user's own stays. */
    private static TransformAction replaceOldComment(String oldDefault) {
        return (path, value) -> {
            if (value instanceof CommentedConfigurationNode commented && isComment(commented, oldDefault)) {
                commented.comment(null);
            }
            return null;
        };
    }

    private static boolean isComment(CommentedConfigurationNode node, String text) {
        return node.comment() != null && node.comment().strip().equals(text.strip());
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
