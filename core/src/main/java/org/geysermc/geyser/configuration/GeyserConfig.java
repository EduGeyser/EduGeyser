/*
 * Copyright (c) 2024 GeyserMC. http://geysermc.org
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

import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.geyser.Constants;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.network.AuthType;
import org.geysermc.geyser.api.network.BedrockListener;
import org.geysermc.geyser.api.network.RemoteServer;
import org.geysermc.geyser.network.bedrock.GameProtocol;
import org.geysermc.geyser.text.AsteriskSerializer;
import org.geysermc.geyser.text.GeyserLocale;
import org.geysermc.geyser.util.CooldownUtils;
import org.spongepowered.configurate.interfaces.meta.Exclude;
import org.spongepowered.configurate.interfaces.meta.defaults.DefaultBoolean;
import org.spongepowered.configurate.interfaces.meta.defaults.DefaultNumeric;
import org.spongepowered.configurate.interfaces.meta.defaults.DefaultString;
import org.spongepowered.configurate.interfaces.meta.range.NumericRange;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ConfigSerializable
public interface GeyserConfig {
    @Comment("Network settings for the Bedrock listener")
    BedrockConfig bedrock();

    @Comment("Network settings for the Java server connection")
    JavaConfig java();

    @Comment("MOTD settings")
    MotdConfig motd();

    @Comment("Gameplay options that affect Bedrock players")
    GameplayConfig gameplay();

    @Comment("The default locale if we don't have the one the client requested. If set to \"system\", the system's language will be used.")
    @DefaultString(GeyserLocale.SYSTEM_LOCALE)
    @NonNull
    String defaultLocale();

    @Comment("Whether player IP addresses will be logged by the server.")
    @DefaultBoolean(true)
    boolean logPlayerIpAddresses();

    @Comment("""
            For online mode authentication type only.
            Stores a list of Bedrock player usernames that should have their Java Edition account saved after login.
            This saves a token that can be reused to authenticate the player later. This does not save emails or passwords,
            but you should still be cautious when adding to this list and giving others access to this Geyser instance's files.
            Removing a name from this list will delete its cached login information on the next Geyser startup.
            The file that tokens will be saved in is in the same folder as this config, named "saved-refresh-tokens.json".""")
    default List<String> savedUserLogins() {
        return List.of("ThisExampleUsernameShouldBeLongEnoughToNeverBeAnXboxUsername",
            "ThisOtherExampleUsernameShouldAlsoBeLongEnough");
    }

    @Comment("""
            For online mode authentication type only.
            Specify how many seconds to wait while user authorizes Geyser to access their Microsoft account.
            User is allowed to disconnect from the server during this period.""")
    @DefaultNumeric(120)
    int pendingAuthenticationTimeout();

    @Comment("""
            Whether to alert the console and operators that a new Geyser version is available that supports a Bedrock version
            that this Geyser version does not support. It's recommended to keep this option enabled, as many Bedrock platforms
            auto-update.""")
    @DefaultBoolean(true)
    boolean notifyOnNewBedrockUpdate();

    @Comment("Advanced configuration options. These usually do not need modifications.")
    AdvancedConfig advanced();

    @Comment("""
            bStats is a stat tracker that is entirely anonymous and tracks only basic information
            about Geyser, such as how many people are online, how many servers are using Geyser,
            what OS is being used, etc. You can learn more about bStats here: https://bstats.org/.
            https://bstats.org/plugin/server-implementation/GeyserMC""")
    @DefaultBoolean(true)
    @ExcludePlatform(platforms = {"BungeeCord", "Spigot", "Velocity"}) // bStats platform versions used
    boolean enableMetrics();

    @Comment("The bstats metrics uuid. Do not touch!")
    @ExcludePlatform(platforms = {"BungeeCord", "Spigot", "Velocity"}) // bStats platform versions used
    default UUID metricsUuid() {
        return UUID.randomUUID();
    }

    @Comment("If debug messages should be sent through console")
    boolean debugMode();

    @Comment("Do not change!")
    @SuppressWarnings("unused")
    default int configVersion() {
        return Constants.CONFIG_VERSION;
    }

    @ConfigSerializable
    interface SignalingConfig {
        @Comment("""
            How players find this server besides Xbox signaling (join codes, connection IDs and friend list joins),
            which is always on.
            "builtin": Geyser runs HTTP signaling itself on the TCP port below. Players who add this server by address
            and the Education server list connect there. It cannot run on the Java server's port (as with
            "clone-remote-port"); Geyser then logs an error and does not switch to the signaling service by itself.
            "nxs": Geyser registers this server with the signaling service in "nxs.endpoint" below, and players join
            through the addresses that service hands out. HTTP signaling does not run, so players who add this server
            by its own address and the Education server list cannot join.
            "hybrid": "builtin" and "nxs" at the same time.""")
        default Mode mode() {
            return Mode.BUILTIN;
        }

        void mode(Mode mode);

        @Comment("""
            The TCP port for HTTP signaling, which players adding this server by address and the Education server list
            connect to. 0 uses the Bedrock "port". Set this to a second TCP port from your host when the Java server
            holds the TCP side of the Bedrock port, as it does with "clone-remote-port".""")
        @DefaultNumeric(0)
        @NumericRange(from = 0, to = 65535)
        int port();

        void port(int port);

        @Comment("""
            The addresses sent to joining players for the NetherNet connection, however they join,
            e.g. play.example.com, 198.51.100.1 or [2001:db8::1]:19133. If no port is given, the NetherNet UDP port is used.
            A host name is looked up when NetherNet starts, and NetherNet stops if it does not resolve.
            You can list an address this machine does not have, such as your public IP, if your router forwards
            the NetherNet UDP port to this machine. You need to set up the port forwarding yourself.
            Listing any address turns "stun-discovery" off for HTTP and Xbox signaling.
            Leave empty to use this machine's own addresses, plus the public address found with "stun-discovery".""")
        @AsteriskSerializer.Asterisk
        default List<String> advertiseAddresses() {
            return List.of();
        }

        @Comment("""
            Finds this server's public address by asking public STUN servers from the NetherNet UDP port, when
            "advertise-addresses" is empty. Players on other networks need that address when this server is behind a
            router, and joins over Xbox signaling (join codes, connection IDs and friend list joins) do not say which
            address the player reached. In the "nxs" and "hybrid" modes the signaling service uses this too, with its own
            STUN servers.""")
        @DefaultBoolean(true)
        boolean stunDiscovery();

        void stunDiscovery(boolean stunDiscovery);

        @Comment("Settings for built-in signaling, the HTTP signaling this server runs itself. Only used in the \"builtin\" and \"hybrid\" modes.")
        BuiltinConfig builtin();

        @Comment("Settings for the signaling service. Only used in the \"nxs\" and \"hybrid\" modes.")
        NxsConfig nxs();

        enum Mode {
            BUILTIN,
            NXS,
            HYBRID;

            /** Whether HTTP signaling runs. */
            public boolean builtin() {
                return this == BUILTIN || this == HYBRID;
            }

            /** Whether this server registers with the signaling service. */
            public boolean nxs() {
                return this == NXS || this == HYBRID;
            }
        }

        @ConfigSerializable
        interface BuiltinConfig {
            @Comment("""
                Your own certificate for HTTP signaling over HTTPS. Players who connect over HTTPS do not see the trust prompt.
                Plain HTTP stays on for players whose HTTPS connection fails.
                Leave "certificate" empty to use "cert.pem" and "key.pem" in the "nethernet" folder if both exist,
                or else a certificate for this server's public IP address that Geyser gets from Let's Encrypt.""")
            HttpsConfig https();

            @ConfigSerializable
            interface HttpsConfig {
                @Comment("""
                    Path to a TLS certificate, relative to the folder of this config. This can be a PEM chain (put its key in
                    "private-key") or a PKCS12 file (leave "private-key" empty). Geyser reloads it when the file changes.""")
                @DefaultString()
                String certificate();

                @Comment("Path to the PEM private key for \"certificate\". Leave empty if \"certificate\" is a PKCS12 file.")
                @DefaultString()
                String privateKey();

                @Comment("""
                    Password for the PKCS12 file, or for the PEM private key if it is encrypted. Leave empty if there is none.
                    To read it from a file, which is safer than writing it here, give the file's path instead: "file:/path/to/password",
                    or the path alone if it starts with "/", "./" or "../" or is absolute. A relative path is relative to the folder
                    of this config.""")
                @DefaultString()
                @AsteriskSerializer.Secret
                String password();
            }
        }

        @ConfigSerializable
        interface NxsConfig {
            @Comment("""
                Access token for the signaling service. Leave empty to register without an account.
                To read it from a file, which is safer than writing it here, give the file's path instead: "file:/path/to/token",
                or the path alone if it starts with "/", "./" or "../" or is absolute. A relative path is relative to the
                "nethernet" folder next to this config.""")
            @DefaultString()
            @AsteriskSerializer.Secret
            String token();

            @Comment("Address of the signaling service (NXS).")
            @DefaultString("https://agent.warden.cloud")
            String endpoint();
        }
    }

    @ConfigSerializable
    interface BedrockConfig extends BedrockListener {
        @Comment("""
                The IP address that Geyser will bind on to listen for incoming Bedrock connections.
                Generally, you should only change this if you want to limit what IPs can connect to your server.""")
        @NonNull
        @Override
        @DefaultString("0.0.0.0")
        @AsteriskSerializer.Asterisk
        String address();

        @Comment("""
            The port that Geyser listens on for Bedrock players, on both TCP and UDP. Open and forward both.
            TCP carries HTTP signaling, which direct connections and joins from the Education server list need.
            UDP carries every player's NetherNet traffic, joins over Xbox signaling (join codes, connection IDs and
            friend list joins) included.
            "webrtc-port" and "port" under "signaling" can move either side to a port of its own.""")
        @Override
        @DefaultNumeric(19132)
        @NumericRange(from = 0, to = 65535)
        int port();

        @Comment("""
            The NetherNet UDP port, which carries every player's NetherNet traffic. 0 uses "port" above.
            Players connect to it directly, so open it, and forward it when this server is behind a router. Players who
            join through the signaling service ("nxs" and "hybrid" signaling modes) cannot connect otherwise, unless
            "assisted-joins" under "advanced.nxs" is on.""")
        @DefaultNumeric(0)
        @NumericRange(from = 0, to = 65535)
        int webrtcPort();

        @Comment("""
                Some hosting services change your Java port everytime you start the server and require the same port to be used for Bedrock.
                This option makes the Bedrock port the same as the Java port every time you start the server.
                The Java server then holds the TCP side of that port, so HTTP signaling does not start unless "port" under
                "signaling" names a second TCP port. Without one, joins from the server list do not work. Joins over Xbox
                signaling (join codes, connection IDs and friend list joins) still work, and so do joins through the
                signaling service when "mode" under "signaling" is "nxs" or "hybrid".""")
        @DefaultBoolean
        @PluginSpecific
        boolean cloneRemotePort();

        @Comment("""
            Signaling is how Bedrock players set up their NetherNet connection to this server: HTTP signaling (TCP) for
            direct connections and server list joins, Xbox signaling for join codes, connection IDs and friend list joins,
            and optionally a signaling service (NXS), see "mode".""")
        SignalingConfig signaling();

        void address(String address);
        void port(int port);
        void webrtcPort(int port);

        /**
         * The UDP port the NetherNet endpoint takes every player's traffic on, also called the ICE port.
         *
         * @return "webrtc-port", or the Bedrock port when that is 0
         */
        @Exclude
        default int icePort() {
            return webrtcPort() == 0 ? port() : webrtcPort();
        }

        /**
         * The TCP port HTTP signaling listens on.
         *
         * @return "port" under "signaling", or the Bedrock port when that is 0
         */
        @Exclude
        default int signalingPort() {
            return signaling().port() == 0 ? port() : signaling().port();
        }

        /**
         * @deprecated NetherNet has no broadcast port: players connect to {@link #port()}
         */
        @Exclude
        @Override
        @Deprecated
        default int broadcastPort() {
            return port();
        }

        @Exclude
        @Override
        default String primaryMotd() {
            return GeyserImpl.getInstance().config().motd().primaryMotd();
        }

        @Exclude
        @Override
        default String secondaryMotd() {
            return GeyserImpl.getInstance().config().motd().secondaryMotd();
        }

        @Exclude
        @Override
        default String serverName() {
            return GeyserImpl.getInstance().config().gameplay().serverName();
        }
    }

    @ConfigSerializable
    interface JavaConfig extends RemoteServer {
        void address(String address);
        void port(int port);

        @Comment("""
                What type of authentication Bedrock players will be checked against when logging into the Java server.
                Can be "floodgate" (see https://wiki.geysermc.org/floodgate/), "online", or "offline".""")
        @NonNull
        @Override
        default AuthType authType() {
            return AuthType.ONLINE;
        }

        void authType(AuthType authType);
        boolean forwardHostname();

        @Override
        @Exclude
        default String minecraftVersion() {
            return GameProtocol.getJavaMinecraftVersion();
        }

        @Override
        @Exclude
        default int protocolVersion() {
            return GameProtocol.getJavaProtocolVersion();
        }

        @Override
        @Exclude
        default boolean resolveSrv() {
            return false;
        }
    }

    @ConfigSerializable
    interface MotdConfig {
        @Comment("""
            The MOTD that will be broadcasted to Minecraft: Bedrock Edition clients. This is irrelevant if "passthrough-motd" is set to true.
            If either of these are empty, the respective string will default to "Geyser\"""")
        @DefaultString("Geyser")
        String primaryMotd();
        @DefaultString("Another Geyser server.")
        String secondaryMotd();

        @Comment("Whether Geyser should relay the MOTD from the Java server to Bedrock players.")
        @DefaultBoolean(true)
        boolean passthroughMotd();

        @Comment("""
            Maximum amount of players that can connect.
            This is only visual, and is only applied if passthrough-motd is disabled.""")
        @DefaultNumeric(100)
        int maxPlayers();

        @Comment("Whether to relay the player count and max players from the Java server to Bedrock players.")
        @DefaultBoolean(true)
        boolean passthroughPlayerCounts();

        @Comment("""
            Whether to use server API methods to determine the Java server's MOTD and ping passthrough.
            There is no need to disable this unless your MOTD or player count does not appear properly.""")
        @DefaultBoolean(true)
        @PluginSpecific
        boolean integratedPingPassthrough();

        @Comment("How often to ping the Java server to refresh MOTD and player count, in seconds.")
        @DefaultNumeric(3)
        int pingPassthroughInterval();
    }

    @ConfigSerializable
    interface GameplayConfig {

        @Comment("The server name that will be sent to Minecraft: Bedrock Edition clients. This is visible in both the pause menu and the settings menu.")
        @DefaultString("Geyser")
        String serverName();

        @Comment("""
            Allow a fake cooldown indicator to be sent. Bedrock players otherwise do not see a cooldown as they still use 1.8 combat.
            Please note: with the integrated pack disabled, some users may see a black box during the cooldown sequence, like below:
            https://geysermc.org/img/external/cooldown_indicator.png
            This can be resolved by enabling the integrated pack, or by going into Bedrock settings under the accessibility tab and setting "Text Background Opacity" to 0.
            This setting can be set to "crosshair", "hotbar", or "disabled\"""")
        default CooldownUtils.CooldownType cooldownType() {
            return CooldownUtils.CooldownType.CROSSHAIR;
        }

        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        @Comment("""
            Bedrock clients can freeze when opening up the command prompt for the first time if given a lot of commands.
            Disabling this will prevent command suggestions from being sent and solve freezing for Bedrock clients.""")
        @DefaultBoolean(true)
        boolean commandSuggestions();

        @Comment("Controls if coordinates are shown to players.")
        @DefaultBoolean(true)
        boolean showCoordinates();

        @Comment("Whether Bedrock players are blocked from performing their scaffolding-style bridging.")
        boolean disableBedrockScaffolding();

        @Comment("""
            Bedrock prevents building and displaying blocks above Y127 in the Nether.
            This config option works around that by changing the Nether dimension ID to the End ID.
            The main downside to this is that the entire Nether will have the same red fog rather than having different fog for each biome.""")
        boolean netherRoofWorkaround();

        @Comment("""
            Whether to show Bedrock Edition emotes to other Bedrock Edition players.
            """)
        @DefaultBoolean(true)
        boolean emotesEnabled();

        @Comment("""
            Whether to remove legacy text formatting codes sent by Bedrock players.
            Unlike on Java Edition, typing section signs for legacy color codes is possible on Bedrock Edition.
            See https://minecraft.wiki/w/Formatting_codes for further information.
            """)
        @DefaultBoolean(true)
        boolean blockLegacyCodes();

        @Comment("""
            Which item to use to mark unavailable slots in a Bedrock player inventory. Examples of this are the 2x2 crafting grid while in creative,
            or custom inventory menus with sizes different from the usual 3x9. A barrier block is the default item.
            This config option can be set to any Bedrock item identifier. If you want to set this to a custom item, make sure that you specify the item in the following format: "geyser_custom:<mapping-name>"
            """)
        @DefaultString("minecraft:barrier")
        String unusableSpaceBlock();

        @Comment("""
            Whether to add any items and blocks which normally does not exist in Bedrock Edition.
            This should only need to be disabled if using a proxy that does not use the "transfer packet" style of server switching.
            If this is disabled, furnace minecart items will be mapped to hopper minecart items.
            Geyser's block, item, and skull mappings systems will also be disabled.
            This option requires a restart of Geyser in order to change its setting.""")
        @DefaultBoolean(true)
        boolean enableCustomContent();

        @Comment("""
            Force clients to load all resource packs if there are any.
            If set to false, it allows the user to connect to the server even if they don't
            want to download the resource packs.""")
        @DefaultBoolean(true)
        boolean forceResourcePacks();

        @Comment("""
            Whether to automatically serve a resource pack that is required for some Geyser features to all connecting Bedrock players.
            If enabled, force-resource-packs will be enabled.""")
        @DefaultBoolean(true)
        boolean enableIntegratedPack();

        @Comment("""
            Whether to report the player's network round trip time to the server, matching how ping
            is measured for Java players. When disabled, the server reads a near zero ping for Bedrock players.
            Geyser answers the server's keep alives itself either way, so slow clients are not kicked for timing out.""")
        boolean forwardPlayerPing();

        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        @Comment("""
            Allows Xbox achievements to be unlocked.
            If a player types in an unknown command, they will receive a message that states cheats are disabled.
            Otherwise, commands work as expected.""")
        boolean xboxAchievementsEnabled();

        @Comment("""
            The maximum number of custom skulls to be displayed per player. Increasing this may decrease performance on weaker devices.
            A value of 0 will disable all custom skulls.
            Setting this to -1 will cause all custom skulls to be displayed regardless of distance or number.""")
        @DefaultNumeric(128)
        int maxVisibleCustomSkulls();

        @Comment("The radius in blocks around the player in which custom skulls are displayed.")
        @DefaultNumeric(32)
        int customSkullRenderDistance();
    }

    @ConfigSerializable
    interface AdvancedBedrockConfig {
        @Comment("""
                How much to compress network traffic to the Bedrock client. The higher the number, the more CPU usage used, but
                the smaller the bandwidth used. Does not have any effect below -1 or above 9. Set to -1 to disable.""")
        @DefaultNumeric(6)
        @NumericRange(from = -1, to = 9)
        int compressionLevel();

        @Comment("""
                Proxies in front of HTTP signaling (TCP) that send a HAPROXY protocol header
                with each player's address. Listing one turns HAPROXY protocol on, so leave this empty unless you run such a proxy.
                HTTP signaling reads the header only from these proxies, and still accepts their connections without one.
                Players behind a listed proxy count toward the per-address connection limit by the address it sends.
                This does not cover NetherNet's UDP traffic, see "webrtc-proxy-protocol-relays".
                IP addresses, subnets, host names (looked up at startup) and https:// links to plain text lists are supported.
                IF YOU DON'T KNOW WHAT THIS IS, DON'T TOUCH IT!""")
        @AsteriskSerializer.Asterisk
        default List<String> haproxyProtocolWhitelistedIps() {
            return Collections.emptyList();
        }

        @Comment("""
                Reverse proxies in front of HTTP signaling, such as an HTTP load balancer, that send each player's address in the
                "X-Forwarded-For" header. HTTP signaling reads the header only from these proxies. This is separate from HAPROXY protocol.
                Players behind a listed proxy count toward the per-address connection limit by the address it sends.
                With more than one proxy in front of signaling, such as a CDN in front of nginx, list every one of them,
                or have the last one send only the player's address in "X-Forwarded-For".
                IP addresses, subnets, host names (looked up at startup) and https:// links to plain text lists are supported.
                IF YOU DON'T KNOW WHAT THIS IS, DON'T TOUCH IT!""")
        @AsteriskSerializer.Asterisk
        default List<String> signalingTrustedProxies() {
            return Collections.emptyList();
        }

        @Comment("""
                UDP relays in front of the NetherNet UDP port that send a PROXY protocol v2 header
                with each player's address. Leave this empty unless you run such a relay.
                While set, players can only reach that port through these relays, and answers no longer name this machine's addresses:
                players need the relays' public addresses announced to them ("advertise-addresses" under "bedrock.signaling"), or they can only connect
                through a TURN server, which only joins over Xbox signaling (join codes, connection IDs and friend list joins)
                hand out.
                NetherNet does not start if none of the listed relays can be read, as when a linked list cannot be downloaded.
                This is separate from the two lists above, which only cover HTTP signaling.
                IP addresses, subnets, host names (looked up at startup) and https:// links to plain text lists are supported.
                IF YOU DON'T KNOW WHAT THIS IS, DON'T TOUCH IT!""")
        @AsteriskSerializer.Asterisk
        default List<String> webrtcProxyProtocolRelays() {
            return Collections.emptyList();
        }

        @Comment("""
                Use the player address that a proxy in front of HTTP signaling sends ("haproxy-protocol-whitelisted-ips" or
                "signaling-trusted-proxies") instead of the one NetherNet's UDP traffic comes from. Enable this when the
                NetherNet UDP port is forwarded without PROXY protocol, so every player would otherwise show the forwarder's address.
                A player connected through a TURN server shows the TURN server's address either way.
                IF YOU DON'T KNOW WHAT THIS IS, DON'T TOUCH IT!""")
        @DefaultBoolean
        boolean preferSignalingProxyAddress();

        @Comment("""
               Should be enabled if this Geyser instance is behind a WaterdogPE proxy. If enabled, Geyser will
               use the xuid / address sent from WaterdogPE and reject clients that do not send it.
               """)
        @DefaultBoolean()
        boolean useWaterdogpeForwarding();

        @Comment("""
            This option disables the auth step Geyser performs for connecting Bedrock players.
            It can be used to allow connections from ProxyPass and WaterdogPE. In these cases, make sure that users
            cannot directly connect to this Geyser instance. See https://www.spigotmc.org/wiki/firewall-guide/ for
            assistance, and block the NetherNet UDP port and the HTTP signaling TCP port.
            Disabling Bedrock authentication for other use-cases is NOT SUPPORTED, as it allows anyone to spoof usernames, and is therefore a security risk.
            All Floodgate functionality (including skin uploading and account linking) will also not work when this option is disabled.""")
        @DefaultBoolean(true)
        boolean validateBedrockLogin();

        @Comment("""
            Whether to log NetherNet's debug messages, from NetherNet itself and from libwebrtc, its WebRTC library,
            such as what signaling and STUN discovery do. Useful when players cannot connect over NetherNet.
            Some servers write debug messages only to their log files, not to the console.
            libwebrtc's own debug messages start only after a restart, not after a reload.""")
        @DefaultBoolean
        boolean nethernetDebug();
    }

    @ConfigSerializable
    interface AdvancedJavaConfig {
        @Comment("""
                Whether to enable HAPROXY protocol when connecting to the Java server.
                This is useful only when:
                1) Your Java server supports HAPROXY protocol (it probably doesn't)
                2) You run Velocity or BungeeCord with the option enabled in the proxy's main config.
                IF YOU DON'T KNOW WHAT THIS IS, DON'T TOUCH IT!""")
        boolean useHaproxyProtocol();

        @Comment("""
            Selects which BungeeCord listener Geyser should connect to, based on the listener's bind address and port.
            This config option must only be set when there are more than one listeners configured in the BungeeCord config.
            Example: "0.0.0.0:25577"
            """)
        @DefaultString() // without annotation, the node is virtual and not written
        @IncludePlatform(platforms = {"BungeeCord"})
        String bungeeListener();

        @Comment("""
        Whether to connect directly into the Java server without creating a TCP connection.
        This should only be disabled if a plugin that interfaces with packets or the network does not work correctly with Geyser.
        If enabled, the remote address and port sections are ignored.
        If disabled, expect performance decrease and latency increase.
        """)
        @DefaultBoolean(true)
        @PluginSpecific
        boolean useDirectConnection();

        @Comment("""
        Whether Geyser should attempt to disable packet compression (from the Java Server to Geyser) for Bedrock players.
        This should be a benefit as there is no need to compress data when Java packets aren't being handled over the network.
        This requires use-direct-connection to be true.
        """)
        @DefaultBoolean(true)
        @PluginSpecific
        boolean disableCompression();
    }

    @ConfigSerializable
    interface AdvancedNxsConfig {
        @Comment("""
            Lets the signaling service help players connect when they cannot reach this server directly,
            for example when the server is behind a NAT without port forwarding.""")
        @DefaultBoolean()
        boolean assistedJoins();

        @Comment("""
            Lets the signaling service check from several regions that players can reach this server,
            and stop offering addresses that fail.""")
        @DefaultBoolean(true)
        boolean connectionChecks();

        @Comment("""
            Where this server is: "country" (a two-letter code), "city", "latitude" and "longitude".
            Leave empty to let the signaling service estimate or configure it.""")
        default Map<String, String> location() {
            return Map.of();
        }

        @Comment("""
            Removes this server from the signaling service when Geyser shuts down.
            Enable this if the "nethernet" folder is lost on every restart (for example, in a container without a volume).
            Otherwise, every restart registers this server again as a new one.""")
        @DefaultBoolean()
        boolean deregisterOnShutdown();
    }

    @ConfigSerializable
    interface AdvancedConfig {
        @Comment("""
            Specify how many days player skin images will be cached to disk to save downloading them from the internet.
            A value of 0 is disabled. (Default: 0)""")
        int cacheImages();

        @Comment("""
            Geyser updates the Scoreboard after every Scoreboard packet, but when Geyser tries to handle
            a lot of scoreboard packets per second, this can cause serious lag.
            This option allows you to specify after how many Scoreboard packets per seconds
            the Scoreboard updates will be limited to four updates per second.""")
        @DefaultNumeric(20)
        int scoreboardPacketThreshold();

        @Comment("""
            Whether Geyser should send team names in command suggestions.
            Disable this if you have a lot of teams used that you don't need as suggestions.""")
        @DefaultBoolean(true)
        boolean addTeamSuggestions();

        @Comment("""
            A list of remote resource pack urls to send to the Bedrock client for downloading.
            The Bedrock client is very picky about how these are delivered - please see our wiki page for further info: https://geysermc.org/wiki/geyser/packs/
            """)
        default List<String> resourcePackUrls() {
            return Collections.emptyList();
        }

        // Cannot be type File yet because we may want to hide it in plugin instances.
        @Comment("""
            Floodgate uses encryption to ensure use from authorized sources.
            This should point to the public key generated by Floodgate (BungeeCord, Spigot or Velocity)
            You can ignore this when not using Floodgate.
            If you're using a plugin version of Floodgate on the same server, the key will automatically be picked up from Floodgate.""")
        @DefaultString("key.pem")
        String floodgateKeyFile();

        @Comment("Advanced networking options for the Geyser to Java server connection")
        AdvancedJavaConfig java();

        @Comment("Advanced networking options for Geyser's Bedrock listener")
        AdvancedBedrockConfig bedrock();

        @Comment("Advanced options for the signaling service. Only used in the \"nxs\" and \"hybrid\" signaling modes.")
        AdvancedNxsConfig nxs();
    }
}
