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

import io.github.sendablemetatype.netty.channel.nethernet.NetherNetChannelFactory;
import io.github.sendablemetatype.netty.channel.nethernet.NetherNetServerChannel;
import io.github.sendablemetatype.netty.channel.nethernet.NetherNetServerStatus;
import io.github.sendablemetatype.netty.channel.nethernet.config.NetherChannelOption;
import io.github.sendablemetatype.netty.channel.nethernet.engine.NetherNetEngine;
import io.github.sendablemetatype.netty.channel.nethernet.engine.webrtc.WebRtcEngine;
import io.github.sendablemetatype.netty.channel.nethernet.signaling.NetherNetHttpSignaling;
import io.github.sendablemetatype.netty.channel.nethernet.signaling.NetherNetXboxRpcSignaling;
import io.github.sendablemetatype.netty.channel.nethernet.signaling.SignalingHealth;
import io.github.sendablemetatype.netty.channel.nethernet.signaling.SignalingState;
import io.github.sendablemetatype.netty.channel.nethernet.signaling.http.StatusAnswer;
import io.github.sendablemetatype.netty.channel.nethernet.signaling.http.StatusRequest;
import io.github.sendablemetatype.netty.channel.nethernet.signaling.http.TlsContextSelector;
import io.github.sendablemetatype.netty.util.address.TrustedProxies;
import io.github.sendablemetatype.netty.util.nethernet.ServerIdentity;
import io.github.sendablemetatype.webrtc.logging.Logging.Severity;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.configuration.GeyserConfig;
import org.geysermc.geyser.configuration.GeyserPluginConfig;
import org.geysermc.geyser.network.BedrockPingHandler;
import org.geysermc.geyser.ping.GeyserPingInfo;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Nethernet (WebRTC) server: one NetherNet endpoint that takes joins from two
 * sources. Connections pipe directly into Geyser's session handling via the
 * Bedrock protocol pipeline. This is EduGeyser's only Bedrock transport.
 *
 * Xbox RPC signaling (Type 7, JSON-RPC: the messaging endpoint with PmsgId,
 * used by Bedrock 26.20+ and Education 26.30+), Xbox signaling for short,
 * carries join codes, connection IDs and friend list joins. HTTP signaling, served on TCP at the Bedrock address and
 * the signaling port, carries direct connections and joins from the
 * Education server list. Every player's ICE traffic arrives on one UDP port,
 * the NetherNet UDP port, for joins from either source. Both ports are the
 * Bedrock port unless configured apart. LAN discovery stays off.
 *
 * The endpoint admits an offer only when it proves the client's key, attested
 * by Minecraft's issuer or not (Education's self signed tokens prove theirs
 * too), on both sources; the Login is then checked against that key in
 * LoginEncryptionUtils. One server identity signs every answer.
 *
 * Behind proxies, HTTP signaling takes the player's address from the
 * proxies the config lists for it, and the ICE port from its PROXY protocol
 * relays; {@link NetherNetProxies} keeps the roles apart.
 *
 * The Xbox source keeps its signaling socket up by itself: it reconnects in
 * place with backoff, so the endpoint, the WebRTC engine, and every
 * established peer connection stay untouched and players never notice a
 * signaling drop; only new joins wait until the socket is back. This class
 * supplies its MCTokens and logs its health.
 */
public class NetherNetServer {

    private static final String LOG_PREFIX = "[Nethernet] ";
    /** ServerData schema version vanilla 1.26.50 reports. */
    private static final int SERVER_DATA_VERSION = 7;
    /** Vanilla generates the status nonce once per process start. */
    private static final String STATUS_NONCE = NetherNetServerStatus.randomNonce();
    /**
     * The name the server identity carries as idp.domain. BDS uses "self" for
     * self issued server identities; kept so the existing key file and the
     * pins players hold for it carry on unchanged.
     */
    private static final String IDENTITY_DOMAIN = "self";
    private static final String IDENTITY_KEY_FILE = "identity-key.pem";
    /**
     * How long restartSignaling waits for its fresh token and the connection
     * made with it. An authentication takes seconds and the Xbox source gives
     * every attempt 30 seconds, so this mostly bounds services that never
     * answer.
     */
    private static final Duration RESTART_WAIT = Duration.ofSeconds(60);
    /**
     * How long start waits for the engine to set up the ICE port after the
     * bind found it free. The first setup loads the WebRTC natives.
     */
    private static final Duration READY_WAIT = Duration.ofSeconds(30);
    /**
     * How long shutdown waits for the endpoint to release its peers, its
     * ports and its engine, so that a start right after it can bind the ports
     * again.
     */
    private static final Duration TERMINATION_WAIT = Duration.ofSeconds(10);

    private final GeyserImpl geyser;
    private final GeyserLogger logger;
    private final EventLoopGroup playerEventLoopGroup;
    private final PlayFabTokenManager tokenManager;
    private final String connectionId;
    private final BedrockPingHandler pingResponder;
    /**
     * Creates the WebRTC engine each endpoint owns and closes with itself,
     * passing on libwebrtc's messages from the given severity up.
     */
    private final Function<Severity, ? extends NetherNetEngine> engines;

    // Tracks live player connections so a real teardown (shutdown or an
    // API-initiated stop with players online) can cleanly disconnect them
    // before the event loops they run on go away. On a normal Geyser shutdown
    // the session manager has already disconnected everyone and this group is
    // empty. Signaling reconnects never touch it. Self-managing: channels are
    // removed automatically when they close.
    private final ChannelGroup playerChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    private volatile NetherNetServerChannel endpointChannel;
    private volatile NetherNetAddressing addressing;
    private volatile NetherNetXboxRpcSignaling xboxSignaling;
    private volatile NetherNetHttpSignaling httpSignaling;
    private NetherNetCertificateManager certificateManager;
    private NetherNetJoinLog joinLog;

    private volatile XboxSignalingTokens signalingTokens;
    // Only touched by the health listener, which runs on the signaling's
    // single event loop.
    private boolean signalingConnectedBefore;

    // Asks the Java ping passthrough off the I/O threads; see serveServerStatus.
    // Both replaced per start(), so an ask left in flight by a previous start
    // writes into snapshots that start no longer serves.
    private ExecutorService passthroughExecutor;
    private volatile NetherNetPingSnapshots pingSnapshots;
    private volatile boolean running;
    private volatile InetSocketAddress statusPingAddress;
    private volatile Severity nativeLogThreshold = Severity.WARNING;

    public NetherNetServer(GeyserImpl geyser, EventLoopGroup playerEventLoopGroup,
                           String connectionId, String playfabCustomId, String playfabDeviceId) {
        this(geyser, playerEventLoopGroup, connectionId, playfabCustomId, playfabDeviceId,
                NetherNetServer::createEngine);
    }

    NetherNetServer(GeyserImpl geyser, EventLoopGroup playerEventLoopGroup, String connectionId,
                    String playfabCustomId, String playfabDeviceId, Function<Severity, ? extends NetherNetEngine> engines) {
        this.engines = engines;
        this.geyser = geyser;
        this.logger = geyser.getLogger();
        this.playerEventLoopGroup = playerEventLoopGroup;
        this.tokenManager = new PlayFabTokenManager(logger, playfabCustomId, playfabDeviceId);
        this.connectionId = connectionId;
        this.pingResponder = new BedrockPingHandler(geyser);
    }

    /**
     * Starts the Nethernet server: binds the endpoint and waits for its ICE
     * port, serves HTTP signaling, acquires an MCToken via PlayFab, and adds
     * Xbox signaling, whose socket connects in the background.
     *
     * Without a token Xbox signaling keeps retrying in the background, so
     * this still returns true. A server identity that cannot be loaded, an
     * endpoint that cannot bind or set up its ICE port, and an endpoint that
     * closes itself before this returns fail the start.
     *
     * @return true if the server started successfully
     */
    public synchronized boolean start() {
        if (running) {
            return true;
        }

        // Before anything logs, so every NetherNet line follows it from the first
        NetherNetDebugLogging.Settings logging = NetherNetDebugLogging.read(
                geyser.config().advanced().bedrock().nethernetDebug(), System::getProperty, logger);
        NetherNetDebugLogging.apply(logging, logger);
        nativeLogThreshold = logging.nativeThreshold();

        passthroughExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Nethernet Status");
            thread.setDaemon(true);
            return thread;
        });
        // A previous shutdown's shutdownNow can discard a queued ask whose
        // gate then stays closed, and restartSignaling starts this instance
        // again, so each start gets snapshots of its own.
        NetherNetPingSnapshots snapshots = new NetherNetPingSnapshots(
                host -> pingResponder.passthrough(statusPingAddress(), host), passthroughExecutor,
                geyser.config().bedrock().signalingPort(), System::nanoTime, logger);
        // Before HTTP signaling listens, so early probes find an answer
        snapshots.prime();
        pingSnapshots = snapshots;

        if (!bind()) {
            shutdown();
            return false;
        }

        running = true;

        // Fetched here, after HTTP signaling serves, rather than left to the
        // Xbox source's first attempt: callers read the pmid as soon as this
        // returns (the extension registers join codes with it). The source
        // starts only afterwards, since PlayFabTokenManager takes one
        // authentication at a time; its first attempt reuses this token.
        String mcToken = tokenManager.authenticate();
        if (!startXboxSignaling() || !endpointChannel.isOpen()) {
            // NetworkM closes the endpoint by itself, for example when an
            // advertised name does not resolve, and logs why
            logEndpointClosed();
            shutdown();
            return false;
        }
        warnWhenClosedByItself(endpointChannel, () -> running, logger);

        // The connection ID clients type is the connection id followed by the
        // 32 hex pmid of the signaling MCToken. The pmid is bound to the
        // anonymous PlayFab account (verified: a full re-auth with the same
        // CustomId returns the same pmid), and the account identity is
        // persisted in connection-id.yml, so this value is stable across
        // restarts.
        String pmid = tokenManager.getPmsgId();
        if (mcToken == null) {
            logger.warning(LOG_PREFIX + "No MCToken from PlayFab yet: joins over Xbox signaling (join codes,"
                    + " connection IDs and friend list joins) wait until it gets one, which it keeps trying in the"
                    + " background");
        } else if (pmid != null) {
            logger.info(LOG_PREFIX + "Listening on connection ID: "
                    + connectionId + pmid.replace("-", "").toLowerCase());
        } else {
            logger.warning(LOG_PREFIX + "The MCToken has no pmid claim: there is no connection ID to give out"
                    + " and joins through Xbox signaling will not work (connection id number: " + connectionId + ")");
        }
        return true;
    }

    /**
     * Reconnects the signaling WebSocket in place with a fresh MCToken,
     * preserving the same connection ID. Existing WebRTC peer connections and
     * player sessions are unaffected. On failure the server keeps running;
     * established players keep playing and the Xbox source keeps retrying.
     * Blocks for at most {@link #RESTART_WAIT}.
     *
     * @return true once the signaling socket is up with a token fetched by this call
     */
    public boolean restartSignaling() {
        NetherNetXboxRpcSignaling xbox;
        XboxSignalingTokens tokens;
        synchronized (this) {
            if (!running) {
                return start();
            }
            xbox = this.xboxSignaling;
            tokens = this.signalingTokens;
        }
        if (xbox == null || tokens == null) {
            return false; // racing a shutdown
        }

        try {
            // A manual restart is usually a response to something being
            // wrong, so it does not trust the cached token. The health
            // listener logs the outcome.
            tokens.reconnectWithFreshToken(xbox::reconnect, RESTART_WAIT);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            logger.warning(LOG_PREFIX + "Signaling reconnect failed; will keep retrying in the background");
            return false;
        }
    }

    public synchronized void shutdown() {
        running = false;
        if (passthroughExecutor != null) {
            // shutdownNow: an ask can sit blocked on the Java server's ping
            // event chain and deserves the interrupt
            passthroughExecutor.shutdownNow();
            passthroughExecutor = null;
        }
        closeChannels();
        if (signalingTokens != null) {
            // After the Xbox source closed, so no attempt is left to ask
            signalingTokens.close();
            signalingTokens = null;
        }
    }

    public boolean isRunning() {
        return running && endpointChannel != null && endpointChannel.isActive();
    }

    public boolean isSignalingAlive() {
        NetherNetXboxRpcSignaling xbox = xboxSignaling;
        return xbox != null && xbox.health().state() == SignalingState.CONNECTED;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public String getPmsgId() {
        return tokenManager.getPmsgId();
    }

    /** Where the endpoint takes ICE traffic: the Bedrock address and the NetherNet UDP port; null when not bound. */
    public @Nullable InetSocketAddress iceAddress() {
        Channel endpoint = endpointChannel;
        return endpoint != null ? (InetSocketAddress) endpoint.localAddress() : null;
    }

    /** Where HTTP signaling listens, TCP; null when it is not bound. */
    public @Nullable InetSocketAddress httpSignalingAddress() {
        NetherNetHttpSignaling http = httpSignaling;
        return http != null ? http.boundAddress() : null;
    }

    /**
     * What answers currently announce about this server, from the endpoint's own snapshots.
     *
     * @return the announced addresses, or null when the endpoint is not bound
     */
    public @Nullable Announced announced() {
        NetherNetAddressing addressing = this.addressing;
        if (!(endpointChannel instanceof NetherNetServerChannel endpoint) || !endpoint.isActive() || addressing == null) {
            return null;
        }
        int icePort = ((InetSocketAddress) endpoint.localAddress()).getPort();
        List<InetSocketAddress> advertised = endpoint.advertisedAddresses().stream()
                // Port 0 stands for the ICE port
                .map(address -> address.getPort() == 0 ? new InetSocketAddress(address.getAddress(), icePort) : address)
                .toList();
        return new Announced(icePort, advertised, addressing.stunDiscovery(), endpoint.discoveredAddresses());
    }

    /**
     * What answers announce about this server. Without advertised addresses, answers carry the
     * addresses ICE gathers on this machine, the address an HTTP join's Host header names, and the
     * discovered ones.
     *
     * @param icePort       the UDP port the endpoint takes ICE traffic on, which candidates name unless an
     *                      advertised address gives another
     * @param advertised    the advertised addresses, names resolved once their lookup finished
     * @param stunDiscovery whether STUN discovery runs
     * @param discovered    the public addresses STUN discovery currently finds
     */
    public record Announced(int icePort, List<InetSocketAddress> advertised, boolean stunDiscovery,
                            List<InetSocketAddress> discovered) {
    }

    private boolean bind() {
        ServerIdentity identity;
        try {
            identity = ServerIdentity.loadOrCreate(
                    geyser.getBootstrap().getConfigFolder().resolve("nethernet").resolve(IDENTITY_KEY_FILE),
                    IDENTITY_DOMAIN);
        } catch (IOException | GeneralSecurityException e) {
            // Every client checks the identity on every answer, so there is
            // nothing to serve without it.
            logger.error(LOG_PREFIX + "Server identity unavailable: " + e.getMessage());
            return false;
        }

        GeyserConfig.AdvancedBedrockConfig advanced = geyser.config().advanced().bedrock();
        NetherNetProxies proxies;
        try {
            proxies = NetherNetProxies.parse(advanced, logger);
        } catch (IllegalArgumentException e) {
            // A list refused outright, or relays none of which could be read:
            // starting without the relays would let players past them
            logger.error(LOG_PREFIX + "NetherNet will not start: " + e.getMessage());
            return false;
        }
        if (advanced.preferSignalingProxyAddress() && !proxies.signalingProxied()) {
            logger.warning(LOG_PREFIX + "\"prefer-signaling-proxy-address\" does nothing, as neither"
                    + " \"haproxy-protocol-whitelisted-ips\" nor \"signaling-trusted-proxies\" names a proxy"
                    + " in front of HTTP signaling");
        }
        NetherNetAddressing addressing;
        try {
            addressing = NetherNetAddressing.read(geyser.config().bedrock().signaling(), proxies.relayed(),
                    System::getProperty, logger);
        } catch (IllegalArgumentException e) {
            logger.error(LOG_PREFIX + "NetherNet will not start: " + e.getMessage());
            return false;
        }
        this.addressing = addressing;
        warnIfLoopback();
        boolean httpSignaling = !signalingPortIsTheJavaServers(geyser.config());
        if (!httpSignaling) {
            logSignalingPortIsTheJavaServers();
        }

        this.bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        this.workerGroup = new MultiThreadIoEventLoopGroup(workerThreads(), NioIoHandler.newFactory());
        this.joinLog = new NetherNetJoinLog(logger, geyser.config().logPlayerIpAddresses());

        NetherNetServerInitializer childHandler = new NetherNetServerInitializer(geyser, playerEventLoopGroup,
                playerChannels, joinLog);

        try {
            // Bound at the Bedrock address and the NetherNet UDP port: with the
            // shared ICE port every peer gathers its host candidates there, and
            // answers announce that port whichever port signaling took the
            // join on. The bind fails at once when the UDP port is
            // taken; the engine sets it up afterwards on its own threads. The
            // endpoint owns its engine and closes it with itself. The
            // advertised addresses and STUN mappings go into the answers of
            // both sources alike.
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channelFactory(NetherNetChannelFactory.server(() -> engines.apply(nativeLogThreshold)))
                    .option(NetherChannelOption.NETHER_SERVER_IDENTITY, identity)
                    .option(NetherChannelOption.NETHER_SERVER_SHARED_ICE_PORT, true)
                    // The relays only: a proxy trusted on HTTP signaling gains
                    // no trust on the ICE port
                    .option(NetherChannelOption.NETHER_SERVER_TRUSTED_PROXIES, proxies.relays())
                    .option(NetherChannelOption.NETHER_SERVER_PREFER_PROXIED_ADDRESS,
                            advanced.preferSignalingProxyAddress())
                    .childHandler(childHandler);
            addressing.applyTo(bootstrap);
            ChannelFuture bound = bootstrap.bind(iceBindAddress());
            // Kept before the bind completes: a channel whose bind failed may
            // have created its engine, and shutdown waits for its termination
            this.endpointChannel = (NetherNetServerChannel) bound.channel();
            bound.sync();
        } catch (IllegalStateException e) {
            // NetworkM refuses a configuration it cannot serve before it
            // touches a socket, such as STUN discovery behind PROXY protocol
            // relays, which would announce an address players cannot reach
            logger.error(LOG_PREFIX + "NetherNet will not start: " + e.getMessage());
            return false;
        } catch (Exception e) {
            // The caller's shutdown releases what was created
            logger.error(LOG_PREFIX + "Failed to bind UDP " + describe(iceBindAddress()) + ": " + describe(e));
            return false;
        }
        if (!awaitReady(endpointChannel)) {
            return false;
        }
        int relays = proxies.relays().proxyProtocol().entries().size();
        logger.info(LOG_PREFIX + "ICE on UDP port " + iceAddress().getPort() + ", shared by every player"
                + (relays == 0 ? "" : ", reached through the PROXY protocol relays in " + ranges(relays)));

        // HTTP signaling: the only way into this server for direct connections
        // and Education server list joins. A failure leaves the endpoint
        // running for Xbox signaling.
        if (!httpSignaling) {
            return true;
        }
        try {
            bindHttpSignaling(endpointChannel, proxies.signaling());
        } catch (Exception e) {
            if (!endpointChannel.isOpen()) {
                logEndpointClosed();
                return false;
            }
            logger.error(LOG_PREFIX + "HTTP signaling unavailable on TCP " + describe(signalingBindAddress()) + " ("
                    + describe(e) + "): direct and server list joins will not work, only joins over Xbox signaling"
                    + " (join codes, connection IDs and friend list joins)"
                    + (geyser.config().bedrock().signalingPort() == geyser.config().java().port()
                    ? ". The signaling port is the Java server's port, which a Java server on this machine may hold" : ""));
        }
        return true;
    }

    /**
     * Waits for the endpoint to take joins. Its bind returned once the ICE
     * port was found free; the engine then checks the port again and sets up
     * its endpoint, on its own threads, and an endpoint that cannot closes
     * itself. That is all this confirms: the port is not held yet. WebRTC
     * binds the socket on each local address when the first join gathers
     * there, so a process that takes the port in between costs those joins
     * their host candidates on that address, and nothing reports it.
     */
    private boolean awaitReady(NetherNetServerChannel endpoint) {
        try {
            endpoint.ready().toCompletableFuture().get(READY_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ClosedChannelException) {
                logEndpointClosed();
            } else {
                logger.error(LOG_PREFIX + "Failed to open the ICE port, UDP " + describe(iceBindAddress()) + ": "
                        + describe(e.getCause()));
            }
        } catch (TimeoutException e) {
            logger.error(LOG_PREFIX + "The ICE port, UDP " + describe(iceBindAddress()) + ", did not open within "
                    + READY_WAIT.toSeconds() + " s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error(LOG_PREFIX + "Interrupted while the ICE port opened");
        }
        return false;
    }

    /**
     * Whether HTTP signaling would listen on the Java server's own port. On a
     * plugin platform the Java server holds that port's TCP side, as it does
     * when clone-remote-port makes the Bedrock port the Java port, so the bind
     * could only fail, or worse, take the port from a Java server that binds
     * after Geyser. Upstream Geyser checks the same before it starts.
     */
    static boolean signalingPortIsTheJavaServers(GeyserConfig config) {
        int javaPort = config.java().port();
        return config instanceof GeyserPluginConfig && javaPort > 0 && config.bedrock().signalingPort() == javaPort;
    }

    private void logSignalingPortIsTheJavaServers() {
        GeyserConfig.BedrockConfig bedrock = geyser.config().bedrock();
        logger.error(LOG_PREFIX + "------------------------------------------------------------------------");
        logger.error(LOG_PREFIX + "HTTP signaling does not start: its TCP port, " + bedrock.signalingPort()
                + ", is the Java server's port" + (bedrock.cloneRemotePort() ? ", as \"clone-remote-port\" makes it" : "")
                + ".");
        logger.error(LOG_PREFIX + "Joins from the server list will not work: retail players who add this server by"
                + " address, and Education players who join from the Education server list.");
        logger.error(LOG_PREFIX + "Joins over Xbox signaling (join codes, connection IDs and friend list joins) still"
                + " work.");
        logger.error(LOG_PREFIX + "To bring server list joins back, get a second TCP port from your host and set it as"
                + " \"port\" under \"bedrock.signaling\" in the config, or with -DgeyserSignalingPort.");
        logger.error(LOG_PREFIX + "------------------------------------------------------------------------");
    }

    private void logEndpointClosed() {
        logger.error(LOG_PREFIX + "The NetherNet endpoint closed itself during startup; NetworkM logged why");
    }

    /**
     * Warns when the endpoint closes while the server still runs: NetworkM
     * closes it by itself, as when an advertised name does not resolve, and
     * logs why, but nothing else tells operators that Bedrock players can no
     * longer join. Shutdown clears running before it closes the endpoint, so
     * its own close stays quiet.
     */
    static void warnWhenClosedByItself(Channel endpoint, BooleanSupplier running, GeyserLogger logger) {
        endpoint.closeFuture().addListener(closed -> {
            if (running.getAsBoolean()) {
                logger.warning(LOG_PREFIX + "The NetherNet endpoint closed itself, NetworkM logged why: Bedrock players"
                        + " can no longer join until a restart or reload");
            }
        });
    }

    /**
     * Adds Xbox signaling to the endpoint. Its socket connects in the
     * background and recovers from outages by itself, asking for a token on
     * every attempt.
     *
     * @return false when the endpoint closed itself meanwhile
     */
    private boolean startXboxSignaling() {
        XboxSignalingTokens tokens = new XboxSignalingTokens(tokenManager);
        NetherNetXboxRpcSignaling xbox = new NetherNetXboxRpcSignaling(connectionId, tokens);
        xbox.addHealthListener(this::logSignalingHealth);
        try {
            endpointChannel.addSource(xbox);
        } catch (IllegalStateException e) {
            xbox.close();
            tokens.close();
            return false;
        }
        this.signalingTokens = tokens;
        this.xboxSignaling = xbox;
        return true;
    }

    /**
     * Warns when the Bedrock address is a loopback address, where no join
     * can work. From the source, not measured: libwebrtc leaves loopback
     * networks out by default (kDefaultNetworkIgnoreMask in
     * rtc_base/network.h, applied in p2p/client/basic_port_allocator.cc),
     * and the shared ICE port's allocator offers only the networks at its
     * bound address, so peers gather no candidate at all, no host one and no
     * STUN or TURN one either, as those start from a network too. NetworkM
     * announces advertised addresses and the address in a Host header as
     * translations of a gathered host candidate, and never a loopback Host,
     * so answers name no address a client could check.
     */
    private void warnIfLoopback() {
        InetAddress address = iceBindAddress().getAddress();
        if (address != null && address.isLoopbackAddress()) {
            logger.warning(LOG_PREFIX + "The Bedrock \"address\" " + geyser.config().bedrock().address()
                    + " is a loopback address. WebRTC gathers no connection candidates there, so NetherNet answers"
                    + " give players no address to connect to, even with \"advertise-addresses\", and every join"
                    + " times out, including joins from this machine. Set \"address\" in the \"bedrock\" section to"
                    + " 0.0.0.0, or to an address of this machine that players can reach.");
        }
    }

    private InetSocketAddress iceBindAddress() {
        return new InetSocketAddress(geyser.config().bedrock().address(), geyser.config().bedrock().icePort());
    }

    private InetSocketAddress signalingBindAddress() {
        return new InetSocketAddress(geyser.config().bedrock().address(), geyser.config().bedrock().signalingPort());
    }

    private static String describe(InetSocketAddress address) {
        return address.getHostString() + ":" + address.getPort();
    }

    /**
     * Binds the HTTP signaling front end on the Bedrock address and the
     * signaling port, TCP, and adds it to the endpoint. Retail clients joining
     * by address and Education clients joining from the server list signal
     * here; Education tries HTTPS first and plain HTTP after a refused TLS
     * connection, and would fall back to RakNet only after both, which
     * EduGeyser does not serve.
     *
     * Plain HTTP stays on next to HTTPS, so a join works whatever happens
     * with TLS: a TLS problem, a client that rejects the certificate and tries
     * again over plain HTTP, or a client that goes straight to plain HTTP.
     *
     * @param proxies the proxies in front of HTTP signaling, never the ICE
     *                port's relays
     */
    private void bindHttpSignaling(NetherNetServerChannel endpoint, TrustedProxies proxies) throws ConnectException {
        // Manual certificate files win and are the ACME opt out (never
        // silent ACME enrollment against the operator's intent); only when
        // no files exist at bind does automatic management run. The manual
        // source re-reads the files whenever they change, so certificates
        // can be swapped live. Both sources pick per connection and withdraw
        // TLS rather than serve anything a stock client would reject, such
        // as an IP certificate to a client that dialed a domain.
        Path nethernetDir = geyser.getBootstrap().getConfigFolder().resolve("nethernet");
        Path manualCert = nethernetDir.resolve("cert.pem");
        Path manualKey = nethernetDir.resolve("key.pem");
        TlsContextSelector tlsSelector;
        String tlsMode;
        if (Files.exists(manualCert) && Files.exists(manualKey)) {
            NetherNetManualTlsSource manualSource = new NetherNetManualTlsSource(manualCert, manualKey, logger);
            tlsSelector = manualSource;
            String problem = manualSource.currentProblem();
            tlsMode = problem == null
                    ? "TLS, manual certificate, reloaded on change"
                    : "plaintext; manual certificate withheld: " + problem;
        } else {
            this.certificateManager = new NetherNetCertificateManager(nethernetDir, logger);
            tlsSelector = certificateManager;
            tlsMode = "automatic certificate management";
        }
        NetherNetHttpSignaling http = NetherNetHttpSignaling.builder(workerGroup)
                .tls(tlsSelector)
                .tlsHandshakeFailures(joinLog::tlsHandshakeFailed)
                .statusProvider(this::serveServerStatus)
                .trustedProxies(proxies)
                .build();
        try {
            listen(endpoint, http, signalingBindAddress());
        } catch (ConnectException | RuntimeException e) {
            if (certificateManager != null) {
                certificateManager.close();
                certificateManager = null;
            }
            throw e;
        }
        this.httpSignaling = http;
        logger.info(LOG_PREFIX + "HTTP signaling on TCP port " + http.boundAddress().getPort() + " (" + tlsMode
                + describeProxies(proxies) + ")");
        if (certificateManager != null) {
            certificateManager.start();
        }
    }

    /** Which proxies HTTP signaling trusts, for its startup line; counts only, never addresses. */
    private static String describeProxies(TrustedProxies proxies) {
        int proxyProtocol = proxies.proxyProtocol().entries().size();
        int forwardedFor = proxies.forwardedFor().entries().size();
        return (proxyProtocol == 0 ? "" : "; PROXY protocol from " + ranges(proxyProtocol))
                + (forwardedFor == 0 ? "" : "; X-Forwarded-For from " + ranges(forwardedFor));
    }

    private static String ranges(int count) {
        return count + (count == 1 ? " range" : " ranges");
    }

    /**
     * Binds HTTP signaling and adds it to the endpoint. Any failure leaves
     * it closed, its TCP listener included: NetworkM closes the endpoint by
     * itself when the engine cannot open the ICE port, and a listener left
     * bound for that endpoint would keep the TCP port from the next start.
     *
     * @throws IllegalStateException if the endpoint closed meanwhile
     */
    static void listen(NetherNetServerChannel endpoint, NetherNetHttpSignaling http, InetSocketAddress address)
            throws ConnectException {
        // A failed bind closes the signaling and leaves no state behind
        http.bind(address);
        try {
            endpoint.addSource(http);
        } catch (RuntimeException e) {
            http.close();
            throw e;
        }
    }

    /** Logs the Xbox source's state changes; runs on its event loop. */
    private void logSignalingHealth(SignalingHealth health) {
        switch (health.state()) {
            case CONNECTED -> {
                logger.info(LOG_PREFIX + (signalingConnectedBefore
                        ? "Signaling reconnected (players unaffected)" : "Signaling connected"));
                signalingConnectedBefore = true;
            }
            case STARTING, RECONNECTING -> {
                Throwable failure = health.lastFailure();
                if (failure == null) {
                    return; // a manual reconnect; its outcome is logged when it lands
                }
                String retry = health.retryIn() == null ? ""
                        : ", next attempt in " + Math.max(1, health.retryIn().toSeconds()) + "s";
                if (health.failedAttempts() == 0) {
                    logger.info(LOG_PREFIX + "Signaling lost (" + describe(failure) + "), reconnecting in place" + retry);
                } else {
                    logger.warning(LOG_PREFIX + "Signaling connection attempt " + health.failedAttempts()
                            + " failed (" + describe(failure) + ")" + retry);
                }
            }
            case NEW, CLOSED -> {
            }
        }
    }

    private static String describe(Throwable failure) {
        return failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
    }

    /**
     * Answers an HTTP status request. HTTP signaling calls this off its I/O
     * threads, at most 64 at a time with a 1 s deadline. Only the Java ping
     * passthrough can block for long, so its answer comes from
     * {@link NetherNetPingSnapshots}, one per virtual host when the
     * passthrough answers by host, and is never waited for. The rest is built
     * per request: Geyser's ping event sees the requester, through trusted
     * proxies as configured, and changes only that requester's answer (see
     * {@link #statusAnswer}), and a player count without passthrough is
     * current.
     */
    private StatusAnswer serveServerStatus(StatusRequest request) {
        GeyserPingInfo passthrough = null;
        if (pingResponder.asksPassthrough()) {
            passthrough = pingSnapshots.latest(pingResponder.passthroughUsesVirtualHost() ? request.host() : null);
        }
        return statusAnswer(pingResponder.pong(request.source().address(), passthrough));
    }

    /**
     * NetworkM repeats a shared document to whoever names the same Host
     * when a later status call fails, is late or finds no room. A document
     * the ping event changed may have been chosen for this requester, so it
     * goes to this requester alone.
     */
    static StatusAnswer statusAnswer(BedrockPingHandler.PingAnswer answer) {
        NetherNetServerStatus status = serverStatus(answer.pong());
        return answer.eventChanged() ? StatusAnswer.serveToRequester(status) : StatusAnswer.serve(status);
    }

    /**
     * The status document, matching vanilla's fourteen member ServerData
     * document, from the status {@link BedrockPingHandler} built: MOTD
     * passthrough, the ping event, and the fallbacks included.
     *
     * Both auth bits are true because we accept Microsoft attested and self
     * signed identities alike: the endpoint requires a proven client key, not
     * an attested token.
     */
    private static NetherNetServerStatus serverStatus(BedrockPong pong) {
        return NetherNetServerStatus.builder()
                .dataVersion(SERVER_DATA_VERSION)
                .name(pong.motd())
                .protocol(pong.protocolVersion())
                .version(pong.version())
                .level(pong.subMotd())
                .players(pong.playerCount())
                .maxPlayers(pong.maximumPlayerCount())
                .gameType(gameTypeId(pong.gameType()))
                .editor(false)
                .hardcore(false)
                .onlineAuth(true)
                .selfSignedAuth(true)
                .nonce(STATUS_NONCE)
                .connection(NetherNetServerStatus.CONNECTION_LAN_WEBRTC_SIGNALING)
                .build();
    }

    /** The pong carries the game type as a display string; ServerData wants the numeric GameType. */
    private static int gameTypeId(String gameType) {
        if ("Creative".equalsIgnoreCase(gameType)) {
            return NetherNetServerStatus.GAME_TYPE_CREATIVE;
        }
        if ("Adventure".equalsIgnoreCase(gameType)) {
            return NetherNetServerStatus.GAME_TYPE_ADVENTURE;
        }
        return NetherNetServerStatus.GAME_TYPE_SURVIVAL;
    }

    /**
     * The address handed to the Java ping passthrough, resolved once and
     * cached on success: a configured hostname would do a blocking DNS lookup
     * per ask, and an unresolved address would hand listeners an object
     * whose getAddress() is null. Falls back to loopback when resolution
     * fails. Runs on the passthrough's thread.
     */
    private InetSocketAddress statusPingAddress() {
        InetSocketAddress address = this.statusPingAddress;
        if (address != null) {
            return address;
        }
        // Status requests come over HTTP signaling
        int port = geyser.config().bedrock().signalingPort();
        try {
            InetSocketAddress resolved = new InetSocketAddress(geyser.config().bedrock().address(), port);
            if (resolved.getAddress() != null) {
                this.statusPingAddress = resolved;
                return resolved;
            }
        } catch (Exception ignored) {
        }
        // Not cached: resolution can fail once at boot before DNS is up, and
        // pinning loopback for the process lifetime would quietly feed the
        // ping passthrough and its event the wrong address forever.
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }

    /**
     * Sizes the netty worker group that runs every Nethernet player's Bedrock
     * pipeline. Scales with the host but stays bounded; the previous fixed
     * value of 2 was a bottleneck at high player counts.
     */
    private static int workerThreads() {
        return Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
    }

    /**
     * Creates the WebRTC engine for one start. One factory equals one native
     * network thread carrying the DTLS and SCTP work of every peer connection
     * assigned to it, so the pool size caps how many threads the data plane
     * can spread players across. Each factory spawns three native threads
     * once used, so the pool stays small on small hosts and caps at four.
     */
    private static NetherNetEngine createEngine(Severity nativeLogThreshold) {
        int factories = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        return WebRtcEngine.builder().factories(factories).nativeLogThreshold(nativeLogThreshold).build();
    }

    /**
     * Waits for the closed endpoint to release its peers, the ports of both
     * sources, the ICE port and its engine, so that a start right after this,
     * as a restart or a reload does, can bind the ports again.
     */
    private void awaitTermination(NetherNetServerChannel endpoint) {
        try {
            endpoint.termination().toCompletableFuture().get(TERMINATION_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            logger.warning(LOG_PREFIX + "The NetherNet endpoint did not release its ports within "
                    + TERMINATION_WAIT.toSeconds() + " s; a start right after this may find them taken");
        }
    }

    private void closeChannels() {
        // Cleanly disconnect any players still online first. Closing each child
        // channel fires channelInactive, which disconnects the GeyserSession and
        // synchronously cancels its tick — so nothing is left ticking against the
        // event loops we shut down below (which would otherwise flood
        // RejectedExecutionException). On a normal Geyser shutdown the session
        // manager has already emptied this group.
        if (!playerChannels.isEmpty()) {
            logger.info(LOG_PREFIX + "Disconnecting " + playerChannels.size() + " player(s) for teardown");
            playerChannels.close().awaitUninterruptibly(3, TimeUnit.SECONDS);
        }

        // Closes both sources too
        if (endpointChannel != null) {
            endpointChannel.close().syncUninterruptibly();
            awaitTermination(endpointChannel);
            endpointChannel = null;
        }
        if (certificateManager != null) {
            certificateManager.close();
            certificateManager = null;
        }
        xboxSignaling = null;
        httpSignaling = null;
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            workerGroup = null;
        }
    }
}
