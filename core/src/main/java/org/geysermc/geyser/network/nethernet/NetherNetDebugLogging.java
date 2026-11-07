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
import io.github.sendablemetatype.webrtc.logging.Logging.Severity;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.geyser.GeyserLogger;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.NOPLoggerFactory;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * Applies "nethernet-debug" and -DgeyserNetherNetLogLevel, which upstream Geyser reads the same way,
 * to NetworkM: the threshold of libwebrtc's native log, which NetworkM's WebRTC engine routes into
 * SLF4J, and the level of NetworkM's own loggers. NetworkM leaves logger levels to its host (ruling
 * B-9), so EduGeyser sets them here: in Log4j2, which every platform but BungeeCord logs with, else in
 * java.util.logging, and checks that the change reached the loggers NetworkM writes to. When debug
 * logging goes off again, the level the host had configured for NetworkM comes back exactly.
 * <p>
 * BungeeCord takes no levels for NetworkM at all. Its SLF4J binding gives every logger name the same
 * BungeeLogger, at level ALL, whose console and file handlers show INFO and above unless BungeeCord
 * starts with -Dnet.md_5.bungee.console-log-level or file-log-level set lower (BungeeCord's
 * JDK14LoggerFactory and BungeeLogger, unchanged since June 2025, so in the 1.21-R0.4 build EduGeyser
 * compiles against). A level set on a logger name reaches nothing there, and changing the shared
 * logger or its handlers would change every plugin's output, so nothing is changed there and the
 * operator is told why.
 */
final class NetherNetDebugLogging {

    private static final String LOG_PREFIX = "[Nethernet] ";
    private static final String LEVEL_PROPERTY = "geyserNetherNetLogLevel";
    /**
     * webrtc-java reads its native threshold from this property once, when it loads its library, and
     * formats every message at or above it, wanted or not.
     */
    private static final String NATIVE_SEVERITY_PROPERTY = "io.github.sendablemetatype.webrtc.logging.severity";
    /** The start of every NetworkM logger name, relocated where a platform shades NetworkM. */
    static final String NETWORKM_LOGGERS = root(NetherNetServerChannel.class.getPackageName());
    /**
     * The SLF4J logger NetworkM's WebRTC engine writes libwebrtc's native log to. NetworkM keeps the
     * name to itself (NativeLogBridge.LOGGER_NAME), so this repeats it.
     */
    private static final String NATIVE_LOGGER = NETWORKM_LOGGERS + ".nethernet.webrtc.native";
    /** The threshold NetworkM's WebRTC engine passes libwebrtc's messages on from by default. */
    private static final Severity DEFAULT_NATIVE_THRESHOLD = Severity.WARNING;

    // Guarded by the class. Found on first use
    private static @Nullable Backend platform;
    // Guarded by the class. The levels holding a change, put back when debug logging goes off on a reload
    private static @Nullable LoggerLevels changed;

    private NetherNetDebugLogging() {
    }

    /** A level for NetworkM's loggers, and the libwebrtc threshold that feeds it. */
    enum Level {
        OFF(Severity.NONE),
        ERROR(Severity.ERROR),
        WARN(Severity.WARNING),
        // NetworkM logs libwebrtc's INFO messages at DEBUG, so none of them would show
        INFO(Severity.WARNING),
        DEBUG(Severity.INFO),
        TRACE(Severity.VERBOSE);

        private final Severity nativeThreshold;

        Level(Severity nativeThreshold) {
            this.nativeThreshold = nativeThreshold;
        }

        /** Parses a level as upstream Geyser does, ignoring case, with ALL standing for TRACE. */
        static @Nullable Level parse(String value) {
            String name = value.trim().toUpperCase(Locale.ROOT);
            if (name.equals("ALL")) {
                return TRACE;
            }
            return Arrays.stream(values()).filter(level -> level.name().equals(name)).findFirst().orElse(null);
        }
    }

    /**
     * What to apply.
     *
     * @param loggers         the level for NetworkM's loggers, or null to leave them to the host's
     *                        logging configuration
     * @param nativeThreshold the lowest libwebrtc severity the WebRTC engine passes on
     */
    record Settings(@Nullable Level loggers, Severity nativeThreshold) {
    }

    /**
     * Reads the settings. A valid -DgeyserNetherNetLogLevel wins; "nethernet-debug" means DEBUG; without
     * either, NetworkM's loggers keep the host's levels and libwebrtc passes on warnings and errors.
     *
     * @param debug      "nethernet-debug"
     * @param properties looks up a system property, null when it is not set
     * @param logger     where the property and its mistakes are reported
     */
    static Settings read(boolean debug, UnaryOperator<String> properties, GeyserLogger logger) {
        String property = properties.apply(LEVEL_PROPERTY);
        if (property != null) {
            Level level = Level.parse(property);
            if (level != null) {
                logger.info(LOG_PREFIX + "NetherNet log level set from system property: " + level);
                return new Settings(level, level.nativeThreshold);
            }
            logger.warning(LOG_PREFIX + "Invalid -D" + LEVEL_PROPERTY + " \"" + property
                    + "\", expected OFF, ERROR, WARN, INFO, DEBUG or TRACE");
        }
        return debug ? new Settings(Level.DEBUG, Level.DEBUG.nativeThreshold) : new Settings(null, DEFAULT_NATIVE_THRESHOLD);
    }

    /**
     * Sets NetworkM's logger levels to the settings, or puts back the host's when an earlier call
     * changed them and these leave them alone. Logger levels are process wide, so this outlives the
     * NetherNet server that applies it.
     *
     * @return the threshold for libwebrtc's messages, which the settings ask for unless this
     *         platform's logging cannot show NetherNet's messages apart from everything else
     */
    static synchronized Severity apply(Settings settings, GeyserLogger logger) {
        if (platform == null) {
            platform = backend(NetherNetDebugLogging::slf4jLoggerName, log4j2Present());
        }
        return apply(settings, logger, platform);
    }

    static synchronized Severity apply(Settings settings, GeyserLogger logger, Backend backend) {
        Level level = settings.loggers();
        if (level == null) {
            if (changed != null) {
                changed.restore();
                changed = null;
            }
            return settings.nativeThreshold();
        }
        return switch (backend) {
            case Backend.Shared(String shared) -> {
                logger.warning(LOG_PREFIX + "nethernet-debug and -D" + LEVEL_PROPERTY + " have no effect on BungeeCord:"
                        + " it writes the messages of every plugin and library to one shared logger, \"" + shared
                        + "\", so NetherNet's messages cannot get a level of their own.");
                logger.warning(LOG_PREFIX + "NetherNet's warnings and errors, libwebrtc's included, still show."
                        + " NetherNet's debug messages, which include the libwebrtc warnings NetherNet treats as"
                        + " routine, show only if BungeeCord starts with -Dnet.md_5.bungee.console-log-level=FINE"
                        + " (and -Dnet.md_5.bungee.file-log-level=FINE for its log file), which shows every other"
                        + " plugin's debug messages too. libwebrtc's own debug messages never show on BungeeCord.");
                // Lower, libwebrtc would format messages that nothing shows
                yield DEFAULT_NATIVE_THRESHOLD;
            }
            case Backend.Separate(LoggerLevels levels) -> {
                if (levels.set(level)) {
                    changed = levels;
                }
                if (!takesEffect(level)) {
                    logger.warning(LOG_PREFIX + "This server's logging did not take NetherNet's log level " + level
                            + ". To see NetherNet's messages, set the level of the loggers whose names start with "
                            + NETWORKM_LOGGERS + " to " + level + " in this server's logging configuration.");
                }
                noteFixedNativeThreshold(settings.nativeThreshold(), System.getProperty(NATIVE_SEVERITY_PROPERTY), logger);
                yield settings.nativeThreshold();
            }
        };
    }

    /**
     * How this platform's logging takes levels for NetworkM.
     *
     * @param slf4jLoggerNames the name of the logger SLF4J hands out for a name
     * @param log4j2           whether Log4j2's core is present
     */
    static Backend backend(UnaryOperator<String> slf4jLoggerNames, boolean log4j2) {
        String actual = slf4jLoggerNames.apply(NATIVE_LOGGER);
        if (!NATIVE_LOGGER.equals(actual)) {
            return new Backend.Shared(actual);
        }
        return new Backend.Separate(log4j2 ? new Log4j2Levels(NETWORKM_LOGGERS) : new JulLevels(NETWORKM_LOGGERS));
    }

    /**
     * The name of the logger SLF4J hands out for a name. BungeeCord's binding names each after its one
     * shared logger. Without a binding, SLF4J drops every message, which is no shared logger: NetworkM's
     * own loggers then go through Netty to Log4j2 or java.util.logging.
     */
    private static String slf4jLoggerName(String name) {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        return factory instanceof NOPLoggerFactory ? name : factory.getLogger(name).getName();
    }

    /** How this platform's logging takes levels for NetworkM's loggers. */
    sealed interface Backend {
        /** Each logger name stays apart, so NetworkM's levels can be set. */
        record Separate(LoggerLevels levels) implements Backend {
        }

        /** One logger, so named, takes the messages of every logger name, as on BungeeCord. */
        record Shared(String logger) implements Backend {
        }
    }

    /** Tells the operator when libwebrtc already holds a higher threshold, which only a restart lowers. */
    static void noteFixedNativeThreshold(Severity wanted, @Nullable String fixed, GeyserLogger logger) {
        if (fixed == null) {
            // The WebRTC engine sets the property to the wanted threshold before the library loads
            return;
        }
        Severity current;
        try {
            current = Severity.valueOf(fixed.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return;
        }
        if (current.compareTo(wanted) > 0) {
            logger.info(LOG_PREFIX + "libwebrtc passes on only " + current + " messages and above until a restart:"
                    + " it reads -D" + NATIVE_SEVERITY_PROPERTY + " once, when it loads");
        }
    }

    /** The package all of NetworkM's packages are in, from its channel package, relocated or not. */
    static String root(String channelPackage) {
        String suffix = ".channel.nethernet";
        if (!channelPackage.endsWith(suffix)) {
            throw new IllegalStateException("Unexpected NetworkM package " + channelPackage);
        }
        return channelPackage.substring(0, channelPackage.length() - suffix.length());
    }

    private static boolean log4j2Present() {
        try {
            Class.forName("org.apache.logging.log4j.core.config.Configurator", false,
                    NetherNetDebugLogging.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * Whether the loggers NetworkM writes to log at exactly this level: its own, through Netty's
     * logging, and the native one, through SLF4J. The two can end in different backends.
     */
    private static boolean takesEffect(Level level) {
        InternalLogger own = InternalLoggerFactory.getInstance(NetherNetServerChannel.class);
        org.slf4j.Logger nativeLog = LoggerFactory.getLogger(NATIVE_LOGGER);
        return switch (level) {
            case OFF -> !own.isErrorEnabled() && !nativeLog.isErrorEnabled();
            case ERROR -> own.isErrorEnabled() && !own.isWarnEnabled()
                    && nativeLog.isErrorEnabled() && !nativeLog.isWarnEnabled();
            case WARN -> own.isWarnEnabled() && !own.isInfoEnabled()
                    && nativeLog.isWarnEnabled() && !nativeLog.isInfoEnabled();
            case INFO -> own.isInfoEnabled() && !own.isDebugEnabled()
                    && nativeLog.isInfoEnabled() && !nativeLog.isDebugEnabled();
            case DEBUG -> own.isDebugEnabled() && !own.isTraceEnabled()
                    && nativeLog.isDebugEnabled() && !nativeLog.isTraceEnabled();
            case TRACE -> own.isTraceEnabled() && nativeLog.isTraceEnabled();
        };
    }

    /**
     * Sets the level of one logger, and so of the loggers below it that have no level of their own, as
     * the platform's logging takes levels. The first change remembers the logger's own configured
     * level, which {@link #restore} puts back exactly.
     */
    interface LoggerLevels {
        /**
         * Sets the level, the first time remembering the logger's own.
         *
         * @return whether the level was set
         */
        boolean set(Level level);

        /** Puts back the logger's own level from before the first {@link #set}, if one changed it. */
        void restore();
    }

    /** Levels in Log4j2's configuration. Only loaded where Log4j2's core is present. */
    static final class Log4j2Levels implements LoggerLevels {
        private final String name;
        // Before the first change: whether the configuration had a logger of this name, and its own level,
        // null when it takes its parent's
        private boolean saved;
        private boolean configured;
        private org.apache.logging.log4j.@Nullable Level own;

        Log4j2Levels(String name) {
            this.name = name;
        }

        @Override
        public boolean set(Level level) {
            try {
                if (!saved) {
                    LoggerConfig current = LoggerContext.getContext(false).getConfiguration().getLoggerConfig(name);
                    configured = current.getName().equals(name);
                    own = configured ? current.getExplicitLevel() : null;
                    saved = true;
                }
                Configurator.setLevel(name, org.apache.logging.log4j.Level.valueOf(level.name()));
                return true;
            } catch (RuntimeException | LinkageError e) {
                // Not a usable Log4j2 core after all; the check after this reports it
                return false;
            }
        }

        @Override
        public void restore() {
            if (!saved) {
                return;
            }
            saved = false;
            if (configured) {
                Configurator.setLevel(name, own);
            } else {
                // The logger this added goes again, so its descendants take their old parent's level
                LoggerContext context = LoggerContext.getContext(false);
                context.getConfiguration().removeLogger(name);
                context.updateLoggers();
            }
        }
    }

    /** Levels in java.util.logging, for a platform without Log4j2. */
    static final class JulLevels implements LoggerLevels {
        // java.util.logging holds its loggers weakly, so a level set on one is lost with it
        private final java.util.logging.Logger logger;
        private boolean saved;
        private java.util.logging.@Nullable Level own;

        JulLevels(String name) {
            this.logger = java.util.logging.Logger.getLogger(name);
        }

        @Override
        public boolean set(Level level) {
            java.util.logging.Level before = logger.getLevel();
            try {
                logger.setLevel(switch (level) {
                    case OFF -> java.util.logging.Level.OFF;
                    case ERROR -> java.util.logging.Level.SEVERE;
                    case WARN -> java.util.logging.Level.WARNING;
                    case INFO -> java.util.logging.Level.INFO;
                    case DEBUG -> java.util.logging.Level.FINE;
                    case TRACE -> java.util.logging.Level.FINEST;
                });
            } catch (RuntimeException e) {
                // A bridge that takes java.util.logging elsewhere may refuse levels; the check after this reports it
                return false;
            }
            if (!saved) {
                own = before;
                saved = true;
            }
            return true;
        }

        @Override
        public void restore() {
            if (saved) {
                logger.setLevel(own);
                saved = false;
            }
        }
    }
}
