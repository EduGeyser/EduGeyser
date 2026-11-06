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
import io.github.sendablemetatype.netty.channel.nethernet.engine.webrtc.WebRtcEngine;
import io.github.sendablemetatype.netty.channel.nethernet.signaling.NetherNetHttpSignaling;
import io.github.sendablemetatype.netty.channel.nethernet.signaling.NetherNetXboxRpcSignaling;
import io.github.sendablemetatype.netty.channel.nethernet.signaling.SignalingHealth;
import io.github.sendablemetatype.netty.channel.nethernet.signaling.SignalingState;
import io.github.sendablemetatype.netty.channel.nethernet.signaling.http.StatusAnswer;
import io.github.sendablemetatype.netty.channel.nethernet.signaling.http.StatusRequest;
import io.github.sendablemetatype.netty.channel.nethernet.signaling.http.TlsContextSelector;
import io.github.sendablemetatype.netty.util.nethernet.ServerIdentity;
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
import org.geysermc.geyser.network.BedrockPingHandler;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Nethernet (WebRTC) server: one NetherNet endpoint that takes joins from two
 * sources. Connections pipe directly into Geyser's session handling via the
 * Bedrock protocol pipeline. This is Geyser's only Bedrock transport: RakNet
 * is not bound.
 *
 * Xbox RPC signaling (Type 7, JSON-RPC: the messaging endpoint with PmsgId,
 * used by Bedrock 26.20+ and Education 26.30+) carries join codes and
 * connection IDs. HTTP signaling, served on TCP at the Bedrock address and
 * port, carries direct connections and joins from the Education server list.
 * Every player's ICE traffic arrives on one UDP port, the Bedrock port, for
 * joins from either source. LAN discovery stays off.
 *
 * The endpoint admits an offer only when it proves the client's key, attested
 * by Minecraft's issuer or not (Education's self signed tokens prove theirs
 * too), on both sources; the Login is then checked against that key in
 * LoginEncryptionUtils. One server identity signs every answer.
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
    /** How stale the served status snapshot may grow before a probe triggers a refresh. */
    private static final long STATUS_MAX_AGE_NANOS = TimeUnit.SECONDS.toNanos(1);
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
     * How long shutdown waits for the WebRTC engine to release its peers and
     * the ICE port, so that a start right after it can bind the port again.
     */
    private static final Duration ENGINE_CLOSE_WAIT = Duration.ofSeconds(10);

    private final GeyserImpl geyser;
    private final GeyserLogger logger;
    private final EventLoopGroup playerEventLoopGroup;
    private final PlayFabTokenManager tokenManager;
    private final String connectionId;
    private final BedrockPingHandler pingResponder;

    // Tracks live player connections so a real teardown (shutdown or an
    // API-initiated stop with players online) can cleanly disconnect them
    // before the event loops they run on go away. On a normal Geyser shutdown
    // the session manager has already disconnected everyone and this group is
    // empty. Signaling reconnects never touch it. Self-managing: channels are
    // removed automatically when they close.
    private final ChannelGroup playerChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    // Owned here rather than by the endpoint: closing the endpoint closes the
    // ICE port later, on the engine's threads, and only the engine's close
    // stage says when the port is free again.
    private WebRtcEngine engine;
    private Channel endpointChannel;
    private volatile NetherNetXboxRpcSignaling xboxSignaling;
    private volatile NetherNetHttpSignaling httpSignaling;
    private NetherNetCertificateManager certificateManager;
    private NetherNetJoinLog joinLog;

    private volatile XboxSignalingTokens signalingTokens;
    // Only touched by the health listener, which runs on the signaling's
    // single event loop.
    private boolean signalingConnectedBefore;

    // Status refreshes get their own thread so that a probe finding a stale
    // snapshot is answered at once with the previous one: the build can
    // block unboundedly on the Java ping passthrough's plugin event chain.
    // Volatile: probe threads read it to schedule refreshes.
    private volatile ExecutorService statusRefreshExecutor;
    private volatile boolean running;

    // The served status snapshot; see serveServerStatus. The initial age is
    // in the past so the first probe (or the startup prime) refreshes.
    private volatile NetherNetServerStatus cachedStatus;
    private volatile long statusBuiltNanos = System.nanoTime() - 2 * STATUS_MAX_AGE_NANOS;
    // Replaced per start() so a refresh left in flight by a previous
    // generation clears its own gate rather than the new one's, which would
    // otherwise let two builds run at once and let the older snapshot win.
    private volatile AtomicBoolean statusRefreshing = new AtomicBoolean();
    private volatile InetSocketAddress statusPingAddress;

    public NetherNetServer(GeyserImpl geyser, EventLoopGroup playerEventLoopGroup,
                           String connectionId, String playfabCustomId, String playfabDeviceId) {
        this.geyser = geyser;
        this.logger = geyser.getLogger();
        this.playerEventLoopGroup = playerEventLoopGroup;
        this.tokenManager = new PlayFabTokenManager(logger, playfabCustomId, playfabDeviceId);
        this.connectionId = connectionId;
        this.pingResponder = new BedrockPingHandler(geyser);
    }

    /**
     * Starts the Nethernet server: binds the endpoint and its ICE port, serves
     * HTTP signaling, acquires an MCToken via PlayFab, and adds Xbox
     * signaling, whose socket connects in the background.
     *
     * Without a token Xbox signaling keeps retrying in the background, so
     * this still returns true; only a server identity that cannot be loaded
     * or an endpoint that cannot bind fails the start.
     *
     * @return true if the server started successfully
     */
    public synchronized boolean start() {
        if (running) {
            return true;
        }

        statusRefreshExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Nethernet Status");
            thread.setDaemon(true);
            return thread;
        });
        // A previous shutdown's shutdownNow can discard a queued refresh
        // whose finally never ran, and restartSignaling starts this instance
        // again. A fresh gate both clears that stuck state and keeps any
        // still running refresh from the old generation pointed at its own.
        statusRefreshing = new AtomicBoolean();
        // Prime the status snapshot before HTTP signaling listens, so early
        // probes find a document.
        scheduleStatusRefresh();

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
        startXboxSignaling();

        // The connection ID clients type is the connection id followed by the
        // 32 hex pmid of the signaling MCToken. The pmid is bound to the
        // anonymous PlayFab account (verified: a full re-auth with the same
        // CustomId returns the same pmid), and the account identity is
        // persisted in connection-id.yml, so this value is stable across
        // restarts.
        String pmid = tokenManager.getPmsgId();
        if (mcToken == null) {
            logger.warning(LOG_PREFIX + "No MCToken from PlayFab yet: join codes and connection IDs wait until"
                    + " Xbox signaling gets one, which it keeps trying in the background");
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
        if (statusRefreshExecutor != null) {
            // shutdownNow: a refresh can sit blocked on the ping event chain
            // and deserves the interrupt. This can discard a queued refresh
            // whose finally never runs; start() resets the flag, since
            // restartSignaling can start this same instance again.
            statusRefreshExecutor.shutdownNow();
            statusRefreshExecutor = null;
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

    /** Where the endpoint takes ICE traffic: the Bedrock address and port, UDP; null when not bound. */
    public @Nullable InetSocketAddress iceAddress() {
        Channel endpoint = endpointChannel;
        return endpoint != null ? (InetSocketAddress) endpoint.localAddress() : null;
    }

    /** Where HTTP signaling listens, TCP; null when it is not bound. */
    public @Nullable InetSocketAddress httpSignalingAddress() {
        NetherNetHttpSignaling http = httpSignaling;
        return http != null ? http.boundAddress() : null;
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

        this.bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        this.workerGroup = new MultiThreadIoEventLoopGroup(workerThreads(), NioIoHandler.newFactory());
        this.engine = createEngine();
        this.joinLog = new NetherNetJoinLog(logger, geyser.config().logPlayerIpAddresses());

        NetherNetServerInitializer childHandler = new NetherNetServerInitializer(geyser, playerEventLoopGroup,
                playerChannels, joinLog);

        try {
            // Bound at the Bedrock address and port: with the shared ICE port
            // every peer gathers its host candidates there, UDP, next to HTTP
            // signaling on TCP. The bind fails at once when the UDP port is
            // taken; the engine opens it afterwards on its own threads.
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channelFactory(NetherNetChannelFactory.server(engine))
                    .option(NetherChannelOption.NETHER_SERVER_IDENTITY, identity)
                    .option(NetherChannelOption.NETHER_SERVER_SHARED_ICE_PORT, true)
                    .childHandler(childHandler);
            this.endpointChannel = bindChannel(bootstrap, bedrockAddress());
        } catch (Exception e) {
            // The caller's shutdown releases what was created
            logger.error(LOG_PREFIX + "Failed to bind UDP " + describe(bedrockAddress()) + ": " + describe(e));
            return false;
        }
        logger.info(LOG_PREFIX + "ICE on UDP port " + iceAddress().getPort() + ", shared by every player");

        // HTTP signaling: the only way into this server for direct connections
        // and Education server list joins. A failure leaves the endpoint
        // running for Xbox signaling.
        try {
            bindHttpSignaling((NetherNetServerChannel) endpointChannel);
        } catch (Exception e) {
            logger.error(LOG_PREFIX + "HTTP signaling unavailable on TCP " + describe(bedrockAddress()) + " ("
                    + describe(e) + "): direct and server list joins will not work, only join codes and"
                    + " connection IDs" + (geyser.config().bedrock().port() == geyser.config().java().port()
                    ? ". The Bedrock port is the Java server's port, whose TCP side a Java server on this machine"
                    + " holds (clone-remote-port does this)" : ""));
        }
        return true;
    }

    /**
     * Adds Xbox signaling to the endpoint. Its socket connects in the
     * background and recovers from outages by itself, asking for a token on
     * every attempt.
     */
    private void startXboxSignaling() {
        XboxSignalingTokens tokens = new XboxSignalingTokens(tokenManager);
        NetherNetXboxRpcSignaling xbox = new NetherNetXboxRpcSignaling(connectionId, tokens);
        xbox.addHealthListener(this::logSignalingHealth);
        try {
            ((NetherNetServerChannel) endpointChannel).addSource(xbox);
        } catch (IllegalStateException e) {
            // The endpoint closed itself meanwhile, as when the engine could
            // not open the ICE port after all; it logged why.
            logger.error(LOG_PREFIX + "Xbox signaling not started: " + e.getMessage());
            xbox.close();
            tokens.close();
            return;
        }
        this.signalingTokens = tokens;
        this.xboxSignaling = xbox;
    }

    private InetSocketAddress bedrockAddress() {
        return new InetSocketAddress(geyser.config().bedrock().address(), geyser.config().bedrock().port());
    }

    private static String describe(InetSocketAddress address) {
        return address.getHostString() + ":" + address.getPort();
    }

    /**
     * Binds a bootstrap and returns the bound channel, closing the netty
     * channel object when the bind fails: netty does not close a channel
     * whose registration succeeded but whose bind did not, which would
     * otherwise leak it (idle but registered) on the event loop.
     */
    private static Channel bindChannel(ServerBootstrap bootstrap, InetSocketAddress address) throws Exception {
        ChannelFuture future = bootstrap.bind(address);
        try {
            future.sync();
        } catch (Exception e) {
            future.channel().close();
            throw e;
        }
        return future.channel();
    }

    /**
     * Binds the HTTP signaling front end on the Bedrock address and port,
     * TCP, and adds it to the endpoint. Retail clients joining by address and
     * Education clients joining from the server list signal here; Education
     * tries HTTPS first and plain HTTP after a refused TLS connection, and
     * would fall back to RakNet only after both, which this server no longer
     * serves.
     */
    private void bindHttpSignaling(NetherNetServerChannel endpoint) throws ConnectException {
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
                .tls(joinLog.observe(tlsSelector))
                .statusProvider(this::serveServerStatus)
                .build();
        try {
            listen(endpoint, http, bedrockAddress());
        } catch (ConnectException | RuntimeException e) {
            if (certificateManager != null) {
                certificateManager.close();
                certificateManager = null;
            }
            throw e;
        }
        this.httpSignaling = http;
        logger.info(LOG_PREFIX + "HTTP signaling on TCP port " + http.boundAddress().getPort() + " (" + tlsMode + ")");
        if (certificateManager != null) {
            certificateManager.start();
        }
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
     * Answers HTTP status probes with the status snapshot and keeps it fresh.
     * HTTP signaling calls this off its I/O threads, and building the
     * document can block (the Java ping passthrough and the ping event's
     * listener chain), so a probe finding the snapshot stale schedules one
     * refresh on the dedicated status thread and is served the previous
     * snapshot meanwhile. Vanilla behaves the same way, serving one cached
     * record to every requester. Only probes racing the first build after
     * start find no snapshot; they build one themselves.
     */
    private StatusAnswer serveServerStatus(StatusRequest request) {
        if (System.nanoTime() - statusBuiltNanos >= STATUS_MAX_AGE_NANOS) {
            scheduleStatusRefresh();
        }
        NetherNetServerStatus status = cachedStatus;
        return StatusAnswer.serve(status != null ? status : buildServerStatus());
    }

    private void scheduleStatusRefresh() {
        ExecutorService executor = this.statusRefreshExecutor;
        // Capture the gate so this refresh always clears the one it claimed,
        // even if start() installs a fresh gate while it is still running.
        AtomicBoolean gate = this.statusRefreshing;
        if (executor == null || !gate.compareAndSet(false, true)) {
            return;
        }
        boolean submitted = false;
        try {
            executor.execute(() -> {
                try {
                    cachedStatus = buildServerStatus();
                } catch (Exception e) {
                    logger.debug(LOG_PREFIX + "Server status refresh failed: " + e.getMessage());
                } finally {
                    // Advance the age on failure too: the throttle must bound
                    // the retry rate, or probes against a throwing ping
                    // passthrough drive the event chain as fast as it runs
                    // instead of once a second.
                    statusBuiltNanos = System.nanoTime();
                    gate.set(false);
                }
            });
            submitted = true;
        } catch (RejectedExecutionException e) {
            // Shutdown raced the probe; the stale snapshot serves fine.
        } finally {
            if (!submitted) {
                gate.set(false);
            }
        }
    }

    /**
     * Builds the status document: the NetherNet equivalent of the RakNet
     * unconnected pong, matching vanilla's fourteen member ServerData
     * document. Built by {@link BedrockPingHandler}, MOTD passthrough, the
     * ping event, and the fallbacks included, as the RakNet pong was.
     *
     * Both auth bits are true because we accept Microsoft attested and self
     * signed identities alike: the endpoint requires a proven client key, not
     * an attested token.
     *
     * Runs on the dedicated status thread, or on the probe's own thread for
     * probes that find no snapshot yet; never on I/O threads.
     */
    private NetherNetServerStatus buildServerStatus() {
        BedrockPong pong = this.pingResponder.onQuery(0L, statusPingAddress(), null);
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
     * The address handed to the ping passthrough and the ping event,
     * resolved once and cached on success: a configured hostname would do a
     * blocking DNS lookup per build, and an unresolved address would hand
     * listeners an object whose getAddress() is null. Falls back to
     * loopback when resolution fails. Runs wherever the status is built.
     */
    private InetSocketAddress statusPingAddress() {
        InetSocketAddress address = this.statusPingAddress;
        if (address != null) {
            return address;
        }
        int port = geyser.config().bedrock().port();
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
    private static WebRtcEngine createEngine() {
        int factories = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        return WebRtcEngine.builder().factories(factories).build();
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
            endpointChannel = null;
        }
        if (engine != null) {
            // Waits for every peer and the ICE port, so a start right after
            // this, as a restart or a reload does, can bind the port again.
            try {
                engine.close().toCompletableFuture().get(ENGINE_CLOSE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException e) {
                logger.warning(LOG_PREFIX + "The WebRTC engine did not close cleanly within "
                        + ENGINE_CLOSE_WAIT.toSeconds() + " s; the ICE port may still be held");
            }
            engine = null;
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
