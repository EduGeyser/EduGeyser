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

package org.geysermc.geyser.dump;

import com.google.common.hash.Hashing;
import com.google.common.io.ByteSource;
import com.google.common.io.MoreFiles;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.annotations.SerializedName;
import io.github.sendablemetatype.netty.channel.nethernet.engine.IceEndpoint;
import io.github.sendablemetatype.netty.channel.nethernet.nxs.ProviderClient;
import io.github.sendablemetatype.netty.channel.nethernet.nxs.transport.AddressFamily;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import lombok.Getter;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.floodgate.util.DeviceOs;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.util.MinecraftVersion;
import org.geysermc.geyser.network.ConnectionRequests;
import org.geysermc.geyser.network.bedrock.GameProtocol;
import org.geysermc.geyser.network.nethernet.NetherNetNxs;
import org.geysermc.geyser.network.nethernet.NetherNetServer;
import org.geysermc.geyser.network.nethernet.NethernetManagerImpl;
import org.geysermc.geyser.pack.ResourcePackHolder;
import org.geysermc.geyser.registry.BlockRegistries;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.text.AsteriskSerializer;
import org.geysermc.geyser.util.CpuUtils;
import org.geysermc.geyser.util.FileUtils;
import org.geysermc.geyser.util.WebUtils;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Getter
public class DumpInfo {
    private static final long MEGABYTE = 1024L * 1024L;

    private final DumpInfo.VersionInfo versionInfo;
    private final int cpuCount;
    private final String cpuName;
    private final Locale systemLocale;
    private final String systemEncoding;
    private final String systemTime;
    private final String systemTimeZone;
    private final GitInfo gitInfo;
    private Object config;
    private final Object2IntMap<DeviceOs> userPlatforms;
    private final int connectionAttempts;
    private final String hash;
    private final RamInfo ramInfo;
    private LogsInfo logsInfo;
    private final BootstrapDumpInfo bootstrapInfo;
    private final FlagsInfo flagsInfo;
    private final List<ExtensionInfo> extensionInfo;
    private final List<PackInfo> packInfo;
    private final MappingInfo mappingInfo;
    private final @Nullable NetherNetInfo netherNetInfo;

    public DumpInfo(GeyserImpl geyser, boolean addLog) {
        this.versionInfo = new VersionInfo();

        this.cpuCount = Runtime.getRuntime().availableProcessors();
        this.cpuName = CpuUtils.tryGetProcessorName();
        this.systemLocale = Locale.getDefault();
        this.systemEncoding = Charset.defaultCharset().displayName();
        this.systemTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS));
        this.systemTimeZone = ZoneId.systemDefault().getId();

        this.gitInfo = new GitInfo(GeyserImpl.BUILD_NUMBER, GeyserImpl.COMMIT.substring(0, 7), GeyserImpl.COMMIT, GeyserImpl.BRANCH, GeyserImpl.REPOSITORY);

        try {
            // Workaround for JsonAdapter not being allowed on methods
            ConfigurationNode configNode = CommentedConfigurationNode.root(AsteriskSerializer.dumpOptions());
            configNode.set(geyser.config());
            this.config = toGson(configNode);
        } catch (SerializationException e) {
            e.printStackTrace();
            if (geyser.config().debugMode()) {
                e.printStackTrace();
            }
        }

        String sha256Hash = "unknown";
        try {
            // https://stackoverflow.com/questions/320542/how-to-get-the-path-of-a-running-jar-file
            File file = new File(DumpInfo.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            ByteSource byteSource = MoreFiles.asByteSource(file.toPath());
            //noinspection UnstableApiUsage
            sha256Hash = byteSource.hash(Hashing.sha256()).toString();
        } catch (Exception e) {
            if (geyser.config().debugMode()) {
                e.printStackTrace();
            }
        }
        this.hash = sha256Hash;

        this.ramInfo = new RamInfo();

        if (addLog) {
            this.logsInfo = new LogsInfo(geyser);
        }

        this.userPlatforms = new Object2IntOpenHashMap<>();
        for (GeyserSession session : geyser.getSessionManager().getAllSessions()) {
            DeviceOs device = session.getClientData().getDeviceOs();
            userPlatforms.put(device, userPlatforms.getOrDefault(device, 0) + 1);
        }

        this.connectionAttempts = ConnectionRequests.attempts();

        this.bootstrapInfo = geyser.getBootstrap().getDumpInfo();

        this.flagsInfo = new FlagsInfo();

        this.extensionInfo = new ArrayList<>();
        for (Extension extension : GeyserApi.api().extensionManager().extensions()) {
            this.extensionInfo.add(new ExtensionInfo(extension.isEnabled(), extension.name(), extension.description().version(), extension.description().apiVersion(), extension.description().main(), extension.description().authors()));
        }

        this.packInfo = Registries.RESOURCE_PACKS.get().values().stream()
            .map(PackInfo::new)
            .toList();
        this.mappingInfo = new MappingInfo(BlockRegistries.CUSTOM_BLOCKS.get().length,
            BlockRegistries.CUSTOM_SKULLS.get().size(),
            Registries.ITEMS.forVersion(GameProtocol.DEFAULT_BEDROCK_PROTOCOL).getCustomIdMappings().size(),
            Registries.BEDROCK_ENTITY_DEFINITIONS.get().values().stream().filter(def -> def instanceof CustomEntityDefinition).toArray().length
        );

        if (geyser.nethernetManager() instanceof NethernetManagerImpl manager) {
            // Read once: a shutdown can clear it in between
            NetherNetNxs nxs = manager.nxs();
            this.netherNetInfo = NetherNetInfo.of(manager.announced(), manager.httpSignalingAddress(),
                iceSockets(manager), nxs == null ? null : nxs.published());
        } else {
            this.netherNetInfo = null;
        }
    }

    /**
     * Waits for the engine's answer, at most two seconds: the dump command builds and uploads the dump on the
     * calling thread anyway.
     *
     * @return what the ICE port holds, or null when the engine did not answer, which leaves it out of the dump
     */
    private static IceEndpoint.@Nullable Sockets iceSockets(NethernetManagerImpl manager) {
        try {
            return manager.iceSockets().get();
        } catch (ExecutionException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private JsonElement toGson(ConfigurationNode node) {
        if (node.isMap()) {
            JsonObject object = new JsonObject();
            node.childrenMap().forEach((key, value) -> {
                JsonElement json = toGson(value);
                object.add(key.toString(), json);
            });
            return object;
        } else if (node.isList()) {
            JsonArray array = new JsonArray();
            node.childrenList().forEach(childNode -> array.add(toGson(childNode)));
            return array;
        } else {
            return convertRawScalar(node);
        }
    }

    private JsonElement convertRawScalar(ConfigurationNode node) {
        final @Nullable Object value = node.rawScalar();
        return switch (value) {
            case null -> JsonNull.INSTANCE;
            case Number n -> new JsonPrimitive(n);
            case Boolean b -> new JsonPrimitive(b);
            default -> new JsonPrimitive(value.toString());
        };
    }

    @Getter
    public static class VersionInfo {
        private final String name;
        private final String version;
        private final String javaName;
        private final String javaVendor;
        private final String javaVersion;
        private final String architecture;
        private final String operatingSystem;
        private final String operatingSystemVersion;

        private final NetworkInfo network;
        private final MCInfo mcInfo;

        VersionInfo() {
            this.name = GeyserImpl.NAME;
            this.version = GeyserImpl.VERSION;
            this.javaName = System.getProperty("java.vm.name");
            this.javaVendor = System.getProperty("java.vendor");
            this.javaVersion = ManagementFactory.getRuntimeMXBean().getVmVersion(); // Gives a little more to the version we can use over the system property
            // Usually gives Java architecture but still may be helpful.
            this.architecture = System.getProperty("os.arch");
            this.operatingSystem = resolveOperatingSystemName();
            this.operatingSystemVersion = System.getProperty("os.version");

            this.network = new NetworkInfo();
            this.mcInfo = new MCInfo();
        }

        /**
         * On Linux, includes the distro name and version (e.g. "Linux (Ubuntu 24.04)").
         */
        private static String resolveOperatingSystemName() {
            String osName = System.getProperty("os.name");
            if (osName == null || !osName.toLowerCase().contains("linux")) {
                return osName;
            }

            for (String path : new String[]{"/etc/os-release", "/usr/lib/os-release"}) {
                File file = new File(path);
                if (!file.canRead()) {
                    continue;
                }
                try {
                    String name = null;
                    String versionId = null;
                    String prettyName = null;
                    for (String line : Files.readAllLines(file.toPath())) {
                        int split = line.indexOf('=');
                        if (split < 0) {
                            continue;
                        }
                        String key = line.substring(0, split).trim();
                        String value = line.substring(split + 1).trim();
                        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                                || value.startsWith("'") && value.endsWith("'"))) {
                            value = value.substring(1, value.length() - 1);
                        }
                        switch (key) {
                            case "NAME" -> name = value;
                            case "VERSION_ID" -> versionId = value;
                            case "PRETTY_NAME" -> prettyName = value;
                            default -> { }
                        }
                    }
                    if (name != null && !name.isEmpty()) {
                        String distro = versionId != null && !versionId.isEmpty() ? name + " " + versionId : name;
                        return "Linux (" + distro + ")";
                    }
                    if (prettyName != null && !prettyName.isEmpty()) {
                        return "Linux (" + prettyName + ")";
                    }
                } catch (IOException | RuntimeException ignored) {
                    // Fall back to the generic name
                }
            }
            return osName;
        }
    }

    @Getter
    public static class NetworkInfo {
        private final boolean dockerCheck;
        private String internalIP;

        NetworkInfo() {
            if (AsteriskSerializer.showSensitive) {
                try (Socket socket = new Socket()) {
                    // This is the most reliable for getting the main local IP
                    socket.connect(new InetSocketAddress("geysermc.org", 80));
                    this.internalIP = socket.getLocalAddress().getHostAddress();
                } catch (IOException e1) {
                    try {
                        // Fallback to the normal way of getting the local IP
                        this.internalIP = InetAddress.getLocalHost().getHostAddress();
                    } catch (UnknownHostException ignored) {
                    }
                }
            } else {
                // Sometimes the internal IP is the external IP...
                this.internalIP = "***";
            }

            this.dockerCheck = checkDockerBasic();
        }

        // By default, Geyser now sets the IP to the local IP in all cases on plugin versions so we don't notify the user of anything
        // However we still have this check for the potential future bug
        private boolean checkDockerBasic() {
            try {
                String OS = System.getProperty("os.name").toLowerCase();
                if (OS.contains("nix") || OS.contains("nux") || OS.indexOf("aix") > 0) {
                    String output = new String(Files.readAllBytes(Paths.get("/proc/1/cgroup")));

                    if (output.contains("docker")) {
                        return true;
                    }
                }
            } catch (Exception ignored) { } // Ignore any errors, inc ip failed to fetch, process could not run or access denied

            return false;
        }
    }

    @Getter
    public static class MCInfo {
        private final List<String> bedrockVersions;
        private final List<Integer> bedrockProtocols;
        private final int defaultBedrockProtocol;
        private final List<String> javaVersions;
        private final int javaProtocol;

        MCInfo() {
            this.bedrockVersions = GameProtocol.SUPPORTED_BEDROCK_VERSIONS.stream().map(MinecraftVersion::versionString).distinct().toList();
            this.bedrockProtocols = GameProtocol.SUPPORTED_BEDROCK_PROTOCOLS;
            this.defaultBedrockProtocol = GameProtocol.DEFAULT_BEDROCK_PROTOCOL;
            this.javaVersions = GameProtocol.getJavaVersions();
            this.javaProtocol = GameProtocol.getJavaProtocolVersion();
        }
    }

    @Getter
    public static class LogsInfo {
        private String link;

        public LogsInfo(GeyserImpl geyser) {
            try {
                Map<String, String> fields = new HashMap<>();
                fields.put("content", FileUtils.readAllLines(geyser.getBootstrap().getLogsPath()).collect(Collectors.joining("\n")));

                JsonObject logData = JsonParser.parseString(WebUtils.postForm("https://api.mclo.gs/1/log", fields)).getAsJsonObject();

                this.link = logData.get("url").getAsString();
            } catch (IOException ignored) { }
        }
    }

    public record RamInfo(long free, long total, long max) {
        public RamInfo() {
            this(Runtime.getRuntime().freeMemory() / MEGABYTE,
                    Runtime.getRuntime().totalMemory() / MEGABYTE,
                    Runtime.getRuntime().maxMemory() / MEGABYTE);
        }
    }

    /**
     * E.G. `-Xmx1024M` - all runtime JVM flags on this machine. The values of the system properties that carry
     * addresses are censored like the addresses in the config.
     */
    public record FlagsInfo(List<String> flags) {
        /** The system properties whose values are comma separated addresses or address-like entries. */
        private static final Set<String> ADDRESS_PROPERTIES =
            Set.of("geyserAdvertiseAddresses", "geyserStunServers", "geyserUdpAddress", "pluginUdpAddress");

        public FlagsInfo {
            flags = flags.stream().map(FlagsInfo::censor).toList();
        }

        public FlagsInfo() {
            this(ManagementFactory.getRuntimeMXBean().getInputArguments());
        }

        private static String censor(String flag) {
            int equals = flag.indexOf('=');
            if (!flag.startsWith("-D") || equals < 0 || !ADDRESS_PROPERTIES.contains(flag.substring(2, equals))) {
                return flag;
            }
            return flag.substring(0, equals + 1) + Arrays.stream(flag.substring(equals + 1).split(",", -1))
                .map(entry -> AsteriskSerializer.censor(entry.strip()))
                .collect(Collectors.joining(","));
        }
    }

    public record ExtensionInfo(boolean enabled, String name, String version, String apiVersion, String main, List<String> authors) {
    }

    public record GitInfo(String buildNumber, @SerializedName("git.commit.id.abbrev") String commitHashAbbrev, @SerializedName("git.commit.id") String commitHash,
                              @SerializedName("git.branch") String branchName, @SerializedName("git.remote.origin.url") String originUrl) {
    }

    public record PackInfo(String name, String type, String size) {

        public PackInfo(ResourcePackHolder holder) {
            this(holder.pack().manifest().header().name(),
                holder.codec().getClass().getSimpleName(),
                String.format("%.2f MB", holder.codec().size() / 1_000_000F));
        }
    }

    public record MappingInfo(int customBlocks, int customSkulls, int customItems, int customEntities) {
    }

    /**
     * The ports NetherNet listens on and what its answers announce about this server, see
     * {@link NetherNetServer#announced()}. Addresses are censored like the configured ones unless the dump is full.
     *
     * @param icePort       the UDP port NetherNet takes every player's traffic on
     * @param signalingPort the TCP port HTTP signaling listens on, or null when it is not running
     * @param advertised    the advertised addresses. When empty, answers carry this machine's own
     *                      addresses, the address an HTTP join's Host header names, and {@code discovered}
     * @param stunDiscovery whether STUN discovery runs
     * @param discovered    the public addresses STUN discovery currently finds
     * @param iceSockets    the local addresses the ICE port is open on and those it could not be opened on, or null
     *                      when the engine did not answer
     * @param nxs           what the signaling service hands joining players, or null when NXS is not running
     */
    public record NetherNetInfo(int icePort, @Nullable Integer signalingPort, List<String> advertised,
                                boolean stunDiscovery, List<String> discovered, @Nullable IceSockets iceSockets,
                                @Nullable Nxs nxs) {
        static @Nullable NetherNetInfo of(NetherNetServer.@Nullable Announced announced,
                                          @Nullable InetSocketAddress httpSignaling,
                                          IceEndpoint.@Nullable Sockets iceSockets,
                                          NetherNetNxs.@Nullable Published nxs) {
            return announced == null ? null : new NetherNetInfo(announced.icePort(),
                httpSignaling == null ? null : httpSignaling.getPort(), censor(announced.advertised()),
                announced.stunDiscovery(), censor(announced.discovered()),
                iceSockets == null ? null : IceSockets.of(iceSockets), nxs == null ? null : Nxs.of(nxs));
        }

        private static List<String> censor(List<InetSocketAddress> addresses) {
            return addresses.stream().map(AsteriskSerializer::censor).toList();
        }

        /**
         * The ICE port's sockets. The port opens on a local address when the first player's connection, or STUN
         * discovery, uses it, so an address shows up once something used it. The addresses are this machine's own and
         * are censored like the others: private and loopback addresses stay readable, the rest only in a full dump.
         * The reasons name no address.
         *
         * @param bound  the local addresses the port is open on
         * @param failed the local addresses it could not be opened on, with the system's reason for the latest attempt
         */
        public record IceSockets(List<String> bound, List<Failure> failed) {
            static IceSockets of(IceEndpoint.Sockets sockets) {
                return new IceSockets(censor(sockets.bound()), sockets.failed().stream()
                    .map(failure -> new Failure(AsteriskSerializer.censor(failure.address()), failure.reason()))
                    .toList());
            }

            /**
             * @param address the local address, censored like the others
             * @param reason  why the system refused it, such as "Address already in use"
             */
            public record Failure(String address, String reason) {
            }
        }

        /**
         * NXS as upstream Geyser's dumps show it. Its instance ID is not secret and stays readable: support looks the
         * server up by it, and registration logs it.
         *
         * @param confirmed         whether the service has confirmed {@code addresses}
         * @param instanceId        the ID the service knows this server by, null until registered
         * @param clockOffsetMillis the service's clock minus this machine's, which refuses requests when too far off
         * @param addresses         the addresses the service hands players, censored like the others
         * @param assistedFamilies  the address families the service assists joins over, 4 and 6
         */
        public record Nxs(boolean confirmed, @Nullable String instanceId, long clockOffsetMillis, List<String> addresses,
                          List<Integer> assistedFamilies) {
            static Nxs of(NetherNetNxs.Published published) {
                ProviderClient.PublishedEndpoints endpoints = published.endpoints();
                return new Nxs(endpoints != null, published.instanceId(), published.clockOffsetMillis(),
                    endpoints == null ? List.of() : censor(endpoints.endpoints()),
                    endpoints == null ? List.of() : endpoints.assistedFamilies().stream()
                        .map(AddressFamily::number).sorted().toList());
            }
        }
    }
}
