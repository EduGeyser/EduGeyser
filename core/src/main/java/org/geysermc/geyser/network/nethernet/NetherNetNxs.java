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

import io.github.sendablemetatype.netty.channel.nethernet.NetherNetServerChannel;
import io.github.sendablemetatype.netty.channel.nethernet.ice.AdvertisedAddresses;
import io.github.sendablemetatype.netty.channel.nethernet.nxs.ProviderClient;
import io.github.sendablemetatype.netty.channel.nethernet.nxs.ProviderDiagnostic;
import io.github.sendablemetatype.netty.channel.nethernet.nxs.ProviderStateStore;
import io.github.sendablemetatype.netty.channel.nethernet.nxs.ServerStatus;
import io.github.sendablemetatype.netty.channel.nethernet.nxs.admission.TicketId;
import io.github.sendablemetatype.netty.channel.nethernet.nxs.host.DiagnosticResult;
import io.github.sendablemetatype.netty.channel.nethernet.nxs.host.HostTransport;
import io.github.sendablemetatype.netty.channel.nethernet.nxs.host.NxsHost;
import io.github.sendablemetatype.netty.channel.nethernet.nxs.provider.ProviderRuntimeConfiguration;
import io.github.sendablemetatype.netty.channel.nethernet.nxs.provider.ProviderShutdown;
import io.github.sendablemetatype.netty.util.nethernet.DtlsCertificate;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.SessionJoinEvent;
import org.geysermc.geyser.configuration.GeyserConfig;
import org.geysermc.geyser.event.type.SessionDisconnectEventImpl;
import org.geysermc.geyser.network.bedrock.GameProtocol;
import org.geysermc.geyser.session.GeyserSession;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * NXS on the NetherNet endpoint, for the "nxs" and "hybrid" signaling modes: this server registers with the
 * signaling service the config names, which hands joining players short-lived tokens that the shared ICE port checks
 * before it creates a peer. It starts after the endpoint binds and registers in the background, as that takes round
 * trips to the service; a failure leaves the endpoint's other join sources running.
 * <p>
 * Its files are where upstream Geyser keeps them, so a server keeps its registration and certificate when it moves
 * between the two: the registration in "provider-state" in the "nethernet" folder, the DTLS certificate every NXS
 * peer presents as "host-cert.pem" and "host-key.pem" in that same directory, and the operator's link in
 * "operator-link.txt" in the "nethernet" folder.
 */
public final class NetherNetNxs implements EventRegistrar {

    private static final String LOG_PREFIX = "[Nethernet] NXS: ";
    /** Holds the service's link for the operator, in the "nethernet" folder. */
    private static final String OPERATOR_LINK_FILE = "operator-link.txt";
    /** How long opening NXS on the endpoint may take before the start counts as failed. */
    private static final Duration OPEN_WAIT = Duration.ofSeconds(30);

    private final GeyserImpl geyser;
    private final GeyserLogger logger;
    private final Path dataFolder;
    private final Supplier<BedrockPong> pongs;
    private final GameOutcomeReporter gameOutcomes = new GameOutcomeReporter(this::reportGameOutcome);
    private volatile Supplier<ServerStatus> statusSupplier;

    private final Object lifecycle = new Object();
    // Guarded by lifecycle
    private boolean stopping;
    private boolean subscribed;
    private @Nullable ProviderClient client;
    private @Nullable ProviderShutdown shutdown;
    private CompletionStage<Void> stopped = CompletableFuture.completedFuture(null);
    /** The one stop, once asked for. */
    private @Nullable CompletionStage<Void> stop;

    private volatile @Nullable HostTransport transport;
    private volatile @Nullable String instanceId;
    private volatile @Nullable String origin;
    // Guarded by lifecycle
    private CompletableFuture<Void> opening = CompletableFuture.completedFuture(null);

    /**
     * @param dataFolder the "nethernet" folder
     * @param pongs      the status this server answers queries with, for the listing the service shows
     */
    NetherNetNxs(GeyserImpl geyser, Path dataFolder, Supplier<BedrockPong> pongs) {
        this.geyser = geyser;
        this.logger = geyser.getLogger();
        this.dataFolder = dataFolder;
        this.pongs = pongs;
        this.statusSupplier = this::automaticStatus;
    }

    /**
     * Opens NXS on the bound endpoint and registers with the signaling service, both in the background.
     *
     * @param endpoint   the bound endpoint; NXS takes its tokens on its shared ICE port
     * @param advertised the advertised addresses, port 0 standing for the ICE port, names not yet looked up
     * @param relayed    whether PROXY protocol relays front the ICE port
     */
    void start(NetherNetServerChannel endpoint, List<InetSocketAddress> advertised, boolean relayed) {
        synchronized (lifecycle) {
            if (stopping || subscribed) {
                return;
            }
            geyser.eventBus().subscribe(this, SessionJoinEvent.class, this::onSessionJoin);
            geyser.eventBus().subscribe(this, SessionDisconnectEventImpl.class, event -> refreshStatus());
            subscribed = true;
            opening = CompletableFuture.runAsync(() -> open(endpoint, advertised, relayed),
                    task -> Thread.ofVirtual().name("NetherNet NXS start").start(task));
        }
    }

    private void open(NetherNetServerChannel endpoint, List<InetSocketAddress> advertised, boolean relayed) {
        ProviderStateStore store = null;
        HostTransport opened = null;
        try {
            GeyserConfig config = geyser.config();
            ProviderRuntimeConfiguration runtime = ProviderRuntimeConfiguration.resolve(
                    settings(config.bedrock().signaling(), resolve(advertised), config.advanced().nxs(), relayed),
                    dataFolder, ((InetSocketAddress) endpoint.localAddress()).getPort(), pongs.get().maximumPlayerCount());
            origin = runtime.origin().getHost();
            synchronized (lifecycle) {
                if (stopping) {
                    return;
                }
            }
            // Locks the registration before anything else in its directory is touched
            store = new ProviderStateStore(runtime.stateDirectory());
            Path state = runtime.stateDirectory();
            DtlsCertificate certificate = DtlsCertificate.loadOrCreate(state.resolve("host-cert.pem"),
                    state.resolve("host-key.pem"));
            NxsHost.Host host = awaitHost(NxsHost.open(endpoint, runtime, certificate).toCompletableFuture());
            opened = host.transport();
            host.warnings().forEach(warning -> logger.warning(LOG_PREFIX + warning));
            ProviderClient created = new ProviderClient(runtime.clientConfiguration(), store, opened,
                    () -> statusSupplier.get(), () -> health(runtime.capacity()), this::log);
            // The client owns both from here on: stopping it closes the transport and releases the store
            store = null;
            HostTransport owned = opened;
            opened = null;
            synchronized (lifecycle) {
                if (stopping) {
                    stopped = created.stop();
                    return;
                }
                client = created;
                transport = owned;
                try {
                    shutdown = new ProviderShutdown(created::stop, () -> { },
                            message -> logger.warning(LOG_PREFIX + message));
                } catch (RuntimeException e) {
                    // The JVM is already shutting down and takes no hook, so nothing else would stop the client
                    stopClient();
                    throw e;
                }
            }
            created.start().whenComplete((registration, failure) -> registered(created, registration, failure));
        } catch (Throwable e) {
            if (opened != null) {
                CompletionStage<Void> closing = opened.close();
                synchronized (lifecycle) {
                    stopped = closing;
                }
            }
            if (stopping()) {
                // A stop that closed the endpoint ends the start this way; nothing went wrong
                logger.debug(LOG_PREFIX + "The start ended with the stop: " + failure(e));
            } else {
                logFailure("NXS did not start", e);
            }
        } finally {
            if (store != null) {
                try {
                    store.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Waits for NXS to start on the endpoint. NetworkM attached its source to the endpoint before this, and a start
     * that fails closes it by itself; one that finishes after the wait is left to this, and stop waits for it.
     */
    private NxsHost.Host awaitHost(CompletableFuture<NxsHost.Host> hosting) throws Exception {
        try {
            return hosting.get(OPEN_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException e) {
            CompletionStage<Void> late = hosting.handle((host, failure) -> host)
                    .thenCompose(host -> host == null ? CompletableFuture.<Void>completedFuture(null)
                            : host.transport().close());
            synchronized (lifecycle) {
                stopped = late;
            }
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw e;
        }
    }

    private boolean stopping() {
        synchronized (lifecycle) {
            return stopping;
        }
    }

    private void registered(ProviderClient registering, ProviderClient.@Nullable Registration registration,
                            @Nullable Throwable failure) {
        if (failure != null) {
            logFailure("Could not register with the signaling service", failure);
            synchronized (lifecycle) {
                if (client == registering) {
                    stopClient();
                }
            }
            return;
        }
        instanceId = registration.instanceId();
        logger.info(LOG_PREFIX + (registration.publicAddress() != null
                ? "Registered with the signaling service at " + origin + ". Players can join at "
                    + registration.publicAddress() + " (instance " + registration.instanceId() + ")."
                : "Registered with the signaling service at " + origin + " (instance " + registration.instanceId()
                    + "). The addresses players join with are managed by the service."));
        // Says who vouches for these logins, without putting credentials in the log
        logger.debug(LOG_PREFIX + "Players who join through the signaling service at " + origin
                + " carry its tokens, which bind the key their login has to be signed with.");
        // The service may offer the operator a link, such as one to claim this server
        registering.operatorAction().thenAccept(this::announceOperatorAction);
    }

    /** Stops the client and forgets it; the caller holds the lifecycle lock. */
    private void stopClient() {
        if (shutdown != null) {
            // Asks the client to stop, the same stop client.stop() returns
            shutdown.close();
        }
        stopped = Objects.requireNonNull(client).stop();
        client = null;
        shutdown = null;
        transport = null;
        instanceId = null;
    }

    /**
     * Stops NXS: drains, tells the service this server stopped accepting players, deregisters when configured to,
     * and releases the registration's directory. A start still in progress finishes first. Every call returns the
     * same stage, so no caller goes on before the cleanup another call set off.
     *
     * @return completes once the registration's directory is free for the next start
     */
    CompletionStage<Void> stop() {
        synchronized (lifecycle) {
            if (stop == null) {
                stopping = true;
                if (subscribed) {
                    geyser.eventBus().unregisterAll(this);
                }
                stop = opening.handle((ignored, failure) -> null).thenCompose(ignored -> {
                    synchronized (lifecycle) {
                        if (client != null) {
                            stopClient();
                        }
                        return stopped;
                    }
                });
            }
            return stop;
        }
    }

    /** The running client, or null before it registered its start and after it stopped. */
    public @Nullable ProviderClient client() {
        synchronized (lifecycle) {
            return client;
        }
    }

    /** What the signaling service hands joining players, or null while it is not running. */
    public @Nullable Published published() {
        ProviderClient current = client();
        return current == null ? null
                : new Published(current.publishedEndpoints().orElse(null), instanceId, current.clockOffsetMillis());
    }

    /**
     * What the signaling service hands joining players.
     *
     * @param endpoints         what the service last confirmed, or null while it has confirmed nothing
     * @param instanceId        the ID the service knows this server by, or null until it registered
     * @param clockOffsetMillis the service's clock minus this machine's, positive when this machine is behind
     */
    public record Published(ProviderClient.@Nullable PublishedEndpoints endpoints, @Nullable String instanceId,
                            long clockOffsetMillis) {
    }

    public GameOutcomeReporter gameOutcomes() {
        return gameOutcomes;
    }

    /**
     * Takes the results of the connectivity checks the service ran since the last call, oldest first. NetworkM keeps
     * at most 32 and drops newer ones while that many wait.
     */
    public List<DiagnosticResult> pollDiagnosticResults() {
        HostTransport current = transport;
        return current == null ? List.of() : current.pollDiagnosticResults(32);
    }

    /** The listing the service shows: the automatic one, or one an operator set. */
    public ServerStatus serverStatus() {
        return statusSupplier.get();
    }

    /** Replaces the automatic listing with a fixed one; fixed values the service's panel sets still take precedence. */
    public void setServerStatus(ServerStatus status) {
        statusSupplier = () -> status;
        refreshStatus();
    }

    public void restoreAutomaticServerStatus() {
        statusSupplier = this::automaticStatus;
        refreshStatus();
    }

    /**
     * The listing from what this server answers status queries with: the name, the level (sub-MOTD), the maximum
     * player count and the game type. The service counts the players itself, from the heartbeat.
     */
    private ServerStatus automaticStatus() {
        BedrockPong pong = pongs.get();
        return new ServerStatus(pong.motd(), pong.subMotd(), pong.maximumPlayerCount(),
                NetherNetServer.gameTypeId(pong.gameType()));
    }

    private ProviderClient.Health health(int capacity) {
        return new ProviderClient.Health(true, capacity, new ProviderClient.Software(GeyserImpl.NAME, GeyserImpl.VERSION),
                new ProviderClient.ConnectedPlayers(geyser.getSessionManager().size(), System.currentTimeMillis()));
    }

    private boolean reportGameOutcome(TicketId ticketId, ProviderClient.GameOutcome outcome, String reason) {
        ProviderClient current = client();
        return current != null && current.reportGameOutcome(ticketId, outcome, reason);
    }

    private void refreshStatus() {
        ProviderClient current = client();
        if (current != null) {
            current.requestStatusRefresh();
        }
    }

    private void onSessionJoin(SessionJoinEvent event) {
        if (event.connection() instanceof GeyserSession session && !session.getUpstream().getSession().isSubClient()) {
            gameOutcomes.joined(session.getUpstream().getSession().getPeer().getChannel());
        }
        refreshStatus();
    }

    private void log(ProviderDiagnostic diagnostic) {
        String message = LOG_PREFIX + diagnostic.message();
        switch (diagnostic.level()) {
            case DEBUG -> logger.debug(message);
            case INFO -> logger.info(message);
            case WARN -> logger.warning(message);
        }
    }

    private void announceOperatorAction(Optional<ProviderClient.OperatorAction> action) {
        try {
            String message = storeOperatorAction(action);
            if (message != null) {
                logger.info(LOG_PREFIX + message);
            }
        } catch (IOException e) {
            logger.warning(LOG_PREFIX + "Could not keep the signaling service's link in "
                    + dataFolder.resolve(OPERATOR_LINK_FILE) + ": " + e.getMessage());
        }
    }

    /**
     * Keeps the service's link for the operator, such as one to claim this server, in the "nethernet" folder. See
     * {@link #storeOperatorAction(Path, Optional)}.
     */
    public @Nullable String storeOperatorAction(Optional<ProviderClient.OperatorAction> action) throws IOException {
        return storeOperatorAction(dataFolder.resolve(OPERATOR_LINK_FILE), action);
    }

    /**
     * Writes the service's link to a file only this user can read, as it grants access to this server and a console
     * is often logged. With no link the file is removed. As upstream Geyser does it, but owner-only on Windows too.
     *
     * @return the service's text and where its link is, or null when there is none
     */
    static @Nullable String storeOperatorAction(Path file, Optional<ProviderClient.OperatorAction> action)
            throws IOException {
        if (action.isEmpty()) {
            Files.deleteIfExists(file);
            return null;
        }
        ProviderClient.OperatorAction value = action.get();
        NetherNetKeyFiles.writeAtomically(file, value.text() + "\n" + value.url() + "\nExpires "
                + Instant.ofEpochMilli(value.expiresAt()) + "\n");
        return value.text() + " The link is in " + file + ".";
    }

    /**
     * What the NXS settings in the config hand NetworkM. Game outcomes are on, as Geyser reports them.
     *
     * @param advertised the advertised addresses, looked up
     * @param relayed    whether PROXY protocol relays front the ICE port
     * @throws IOException if "location" is invalid
     */
    static ProviderRuntimeConfiguration.Settings settings(GeyserConfig.SignalingConfig signaling,
                                                          List<InetSocketAddress> advertised,
                                                          GeyserConfig.AdvancedNxsConfig advanced,
                                                          boolean relayed) throws IOException {
        ProviderClient.Location location;
        try {
            location = ProviderClient.Location.of(advanced.location());
        } catch (IllegalArgumentException invalid) {
            throw new IOException("\"location\" under \"advanced.nxs\" needs a two-letter country code, a latitude and"
                    + " longitude pair, or both; \"city\" is optional");
        }
        return ProviderRuntimeConfiguration.Settings.builder(signaling.nxs().endpoint())
                .token(signaling.nxs().token())
                .advertiseAddresses(advertised)
                .assistedJoins(advanced.assistedJoins())
                .connectionChecks(advanced.connectionChecks())
                .stunDiscovery(signaling.stunDiscovery())
                .deregisterOnShutdown(advanced.deregisterOnShutdown())
                .location(location)
                .bedrockProtocols(bedrockProtocols())
                .gameOutcomes(true)
                .relayed(relayed)
                .build();
    }

    /** Every protocol a login is accepted with, one per supported codec, Education's included. */
    static ProviderClient.BedrockProtocols bedrockProtocols() {
        List<Integer> accepted = GameProtocol.SUPPORTED_BEDROCK_PROTOCOLS.intStream().distinct().sorted().boxed().toList();
        return new ProviderClient.BedrockProtocols(accepted, GameProtocol.DEFAULT_BEDROCK_VERSION);
    }

    /** Looks up the advertised names, as NetworkM wants the service handed addresses. */
    private static List<InetSocketAddress> resolve(List<InetSocketAddress> advertised) throws IOException {
        try {
            return AdvertisedAddresses.resolve(advertised);
        } catch (UnknownHostException e) {
            throw new IOException("an entry in \"advertise-addresses\" does not resolve: " + e.getMessage(), e);
        }
    }

    /**
     * Why a request to the signaling service failed: the service's own explanation when it gave one, else the
     * exception's type and message.
     */
    public static String failure(Throwable failure) {
        failure = unwrapped(failure);
        if (failure instanceof ProviderClient.ProviderException) {
            return failure.getMessage();
        }
        String type = failure.getClass().getSimpleName();
        return failure.getMessage() == null ? type : type + ": " + failure.getMessage();
    }

    /**
     * Logs why NXS failed: the service's own explanation, a settings problem by its message, else the exception, whose
     * causes say what is in the way, such as a firewall or a proxy.
     */
    private void logFailure(String what, Throwable failure) {
        Throwable cause = unwrapped(failure);
        if (cause instanceof ProviderClient.ProviderException
                || cause instanceof IOException && cause.getMessage() != null) {
            logger.error(LOG_PREFIX + what + ": " + cause.getMessage());
        } else {
            logger.error(LOG_PREFIX + what, cause);
        }
    }

    private static Throwable unwrapped(Throwable failure) {
        while (failure.getCause() != null
                && (failure instanceof CompletionException || failure instanceof ExecutionException)) {
            failure = failure.getCause();
        }
        return failure;
    }
}
