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

import io.github.sendablemetatype.webrtc.logging.Logging.Severity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.network.nethernet.NetherNetDebugLogging.JulLevels;
import org.geysermc.geyser.network.nethernet.NetherNetDebugLogging.Level;
import org.geysermc.geyser.network.nethernet.NetherNetDebugLogging.Log4j2Levels;
import org.geysermc.geyser.network.nethernet.NetherNetDebugLogging.Settings;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.geysermc.geyser.network.nethernet.NetherNetDebugLogging.NETWORKM_LOGGERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class NetherNetDebugLoggingTest {

    private static final Settings DEBUG = new Settings(Level.DEBUG, Severity.INFO);
    private static final Settings OFF = new Settings(null, Severity.WARNING);

    private final GeyserLogger logger = mock(GeyserLogger.class);

    private Settings read(boolean debug, Map<String, String> properties) {
        return NetherNetDebugLogging.read(debug, properties::get, logger);
    }

    @Test
    void offLeavesTheLoggersToTheHost() {
        assertEquals(new Settings(null, Severity.WARNING), read(false, Map.of()));
    }

    @Test
    void debugLowersLibwebrtcsThresholdToTheMessagesNetworkMLogsAtDebug() {
        assertEquals(new Settings(Level.DEBUG, Severity.INFO), read(true, Map.of()));
    }

    @Test
    void thePropertyWinsOverTheConfig() {
        assertEquals(new Settings(Level.WARN, Severity.WARNING), read(true, Map.of("geyserNetherNetLogLevel", "warn")));
        assertEquals(new Settings(Level.TRACE, Severity.VERBOSE), read(false, Map.of("geyserNetherNetLogLevel", " TRACE ")));
        assertEquals(new Settings(Level.TRACE, Severity.VERBOSE), read(false, Map.of("geyserNetherNetLogLevel", "all")));
        assertEquals(new Settings(Level.OFF, Severity.NONE), read(true, Map.of("geyserNetherNetLogLevel", "off")));
        // INFO messages from libwebrtc would log at DEBUG, below the level
        assertEquals(new Settings(Level.INFO, Severity.WARNING), read(true, Map.of("geyserNetherNetLogLevel", "info")));
    }

    @Test
    void anInvalidPropertyFallsBackToTheConfig() {
        assertEquals(new Settings(Level.DEBUG, Severity.INFO), read(true, Map.of("geyserNetherNetLogLevel", "loud")));
        verify(logger).warning(contains("Invalid -DgeyserNetherNetLogLevel \"loud\""));
    }

    @Test
    void aThresholdLibwebrtcAlreadyHoldsAboveTheWantedOneNeedsARestart() {
        NetherNetDebugLogging.noteFixedNativeThreshold(Severity.INFO, "WARNING", logger);
        verify(logger).info(contains("passes on only WARNING messages and above until a restart"));

        GeyserLogger quiet = mock(GeyserLogger.class);
        NetherNetDebugLogging.noteFixedNativeThreshold(Severity.INFO, null, quiet);
        NetherNetDebugLogging.noteFixedNativeThreshold(Severity.WARNING, "INFO", quiet);
        verify(quiet, never()).info(anyString());
    }

    @Test
    void theLoggersAreFoundUnderRelocatedPackagesToo() {
        assertEquals("io.github.sendablemetatype.netty", NetherNetDebugLogging.NETWORKM_LOGGERS);
        assertEquals("org.geysermc.geyser.shaded.io.github.sendablemetatype.netty",
            NetherNetDebugLogging.root("org.geysermc.geyser.shaded.io.github.sendablemetatype.netty.channel.nethernet"));
    }

    @Test
    void turningDebugOffPutsBackTheLevelTheHostConfiguredInLog4j2() {
        LoggerContext context = LoggerContext.getContext(false);
        Configurator.setRootLevel(org.apache.logging.log4j.Level.DEBUG);
        Configurator.setLevel(NETWORKM_LOGGERS, org.apache.logging.log4j.Level.WARN);
        try {
            Log4j2Levels levels = new Log4j2Levels(NETWORKM_LOGGERS);
            NetherNetDebugLogging.apply(DEBUG, logger, levels);
            assertTrue(LogManager.getLogger(NETWORKM_LOGGERS + ".channel").isDebugEnabled());

            NetherNetDebugLogging.apply(OFF, logger, levels);
            assertEquals(org.apache.logging.log4j.Level.WARN,
                context.getConfiguration().getLoggerConfig(NETWORKM_LOGGERS).getExplicitLevel());
            assertFalse(LogManager.getLogger(NETWORKM_LOGGERS + ".channel").isInfoEnabled(),
                "the host's WARN, not the root's DEBUG");
        } finally {
            context.getConfiguration().removeLogger(NETWORKM_LOGGERS);
            Configurator.setRootLevel(org.apache.logging.log4j.Level.ERROR);
        }
    }

    @Test
    void turningDebugOffRemovesTheLog4j2LoggerItAdded() {
        LoggerContext context = LoggerContext.getContext(false);
        Log4j2Levels levels = new Log4j2Levels(NETWORKM_LOGGERS);
        NetherNetDebugLogging.apply(new Settings(Level.TRACE, Severity.VERBOSE), logger, levels);
        // TRACE, then DEBUG: what comes back is still what the host had before the first change
        NetherNetDebugLogging.apply(DEBUG, logger, levels);
        assertEquals(NETWORKM_LOGGERS, context.getConfiguration().getLoggerConfig(NETWORKM_LOGGERS).getName());

        NetherNetDebugLogging.apply(OFF, logger, levels);
        assertNotEquals(NETWORKM_LOGGERS, context.getConfiguration().getLoggerConfig(NETWORKM_LOGGERS).getName());
    }

    @Test
    void turningDebugOffPutsBackTheLevelTheHostSetInJavaUtilLogging() {
        java.util.logging.Logger parent = java.util.logging.Logger.getLogger("io.github.sendablemetatype");
        java.util.logging.Logger networkM = java.util.logging.Logger.getLogger(NETWORKM_LOGGERS);
        parent.setLevel(java.util.logging.Level.FINE);
        networkM.setLevel(java.util.logging.Level.WARNING);
        try {
            JulLevels levels = new JulLevels(NETWORKM_LOGGERS);
            NetherNetDebugLogging.apply(DEBUG, logger, levels);
            assertEquals(java.util.logging.Level.FINE, networkM.getLevel());

            NetherNetDebugLogging.apply(OFF, logger, levels);
            assertEquals(java.util.logging.Level.WARNING, networkM.getLevel());

            // A logger without a level of its own takes its parent's again
            networkM.setLevel(null);
            JulLevels inheriting = new JulLevels(NETWORKM_LOGGERS);
            NetherNetDebugLogging.apply(DEBUG, logger, inheriting);
            NetherNetDebugLogging.apply(OFF, logger, inheriting);
            assertNull(networkM.getLevel());
        } finally {
            networkM.setLevel(null);
            parent.setLevel(null);
        }
    }
}
