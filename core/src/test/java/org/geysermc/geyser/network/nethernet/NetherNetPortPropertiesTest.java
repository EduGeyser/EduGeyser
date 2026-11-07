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

import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.configuration.GeyserConfig;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.interfaces.InterfaceDefaultOptions;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NetherNetPortPropertiesTest {

    private static final int JAVA_PORT = 25577;

    private final GeyserLogger logger = mock(GeyserLogger.class);

    private static GeyserConfig.BedrockConfig config(String yaml) throws Exception {
        return YamlConfigurationLoader.builder()
                .source(() -> new BufferedReader(new StringReader(yaml)))
                .defaultOptions(InterfaceDefaultOptions::addTo)
                .build().load().get(GeyserConfig.BedrockConfig.class);
    }

    private GeyserConfig.BedrockConfig apply(String yaml, Map<String, String> properties) throws Exception {
        GeyserConfig.BedrockConfig bedrock = config(yaml);
        NetherNetPortProperties.apply(bedrock, true, JAVA_PORT, properties::get, logger);
        return bedrock;
    }

    @Test
    void bothSidesUseTheBedrockPortByDefault() throws Exception {
        GeyserConfig.BedrockConfig bedrock = apply("port: 19200", Map.of());

        assertEquals(19200, bedrock.icePort());
        assertEquals(19200, bedrock.signalingPort());
    }

    @Test
    void theConfigMovesEitherSide() throws Exception {
        GeyserConfig.BedrockConfig bedrock = apply("""
                port: 19200
                webrtc-port: 19201
                signaling:
                  port: 19202
                """, Map.of());

        assertEquals(19201, bedrock.icePort());
        assertEquals(19202, bedrock.signalingPort());
    }

    @Test
    void theUdpPortPropertyWinsOverWebrtcPortButNotOverTheSignalingPort() throws Exception {
        GeyserConfig.BedrockConfig bedrock = apply("""
                port: 19200
                webrtc-port: 19201
                signaling:
                  port: 19202
                """, Map.of("geyserUdpPort", "19300"));

        assertEquals(19300, bedrock.port());
        assertEquals(19300, bedrock.icePort());
        assertEquals(19202, bedrock.signalingPort());
        verify(logger).info("Port set from system property: 19300");
    }

    @Test
    void theUdpPortPropertyMovesTheSignalingPortWithTheBedrockPort() throws Exception {
        GeyserConfig.BedrockConfig bedrock = apply("port: 19200", Map.of("pluginUdpPort", "19300"));

        assertEquals(19300, bedrock.icePort());
        assertEquals(19300, bedrock.signalingPort());
        verify(logger).info("Port set from generic system property: 19300");
    }

    @Test
    void theJavaServersPortComesFromCloneRemotePortOrTheUdpPortProperty() throws Exception {
        GeyserConfig.BedrockConfig cloned = apply("""
                port: 19200
                webrtc-port: 19201
                clone-remote-port: true
                """, Map.of());
        // clone-remote-port moves the Bedrock port, which "webrtc-port" still overrides on UDP
        assertEquals(JAVA_PORT, cloned.port());
        assertEquals(19201, cloned.icePort());
        assertEquals(JAVA_PORT, cloned.signalingPort());

        GeyserConfig.BedrockConfig server = apply("""
                port: 19200
                webrtc-port: 19201
                """, Map.of("geyserUdpPort", "server"));
        assertEquals(JAVA_PORT, server.icePort());
        assertEquals(JAVA_PORT, server.signalingPort());
    }

    @Test
    void standaloneLeavesTheUdpPortPropertyAndCloneRemotePortAlone() throws Exception {
        GeyserConfig.BedrockConfig bedrock = config("""
                port: 19200
                clone-remote-port: true
                """);
        NetherNetPortProperties.apply(bedrock, false, -1, Map.of("geyserUdpPort", "19300")::get, logger);

        assertEquals(19200, bedrock.icePort());
        assertEquals(19200, bedrock.signalingPort());
    }

    @Test
    void theirOwnPropertiesWinOverEverything() throws Exception {
        GeyserConfig.BedrockConfig bedrock = apply("""
                port: 19200
                webrtc-port: 19201
                signaling:
                  port: 19202
                """, Map.of("geyserUdpPort", "19300", "geyserWebrtcPort", "19401", "geyserSignalingPort", "19402"));

        assertEquals(19401, bedrock.icePort());
        assertEquals(19402, bedrock.signalingPort());
    }

    @Test
    void anInvalidPropertyKeepsTheConfiguredPort() throws Exception {
        GeyserConfig.BedrockConfig bedrock = apply("""
                port: 19200
                webrtc-port: 19201
                """, Map.of("geyserWebrtcPort", "70000", "geyserSignalingPort", "tcp"));

        assertEquals(19201, bedrock.icePort());
        assertEquals(19200, bedrock.signalingPort());
        verify(logger).error(contains("Invalid NetherNet UDP port from system property: 70000"));
        verify(logger).error(contains("Invalid HTTP signaling port from system property: tcp"));
    }

    @Test
    void aUdpPortPropertyThatIsNoPortStopsTheStart() throws Exception {
        // As before the NetherNet ports had properties of their own
        assertThrows(NumberFormatException.class, () -> apply("port: 19200", Map.of("geyserUdpPort", "udp")));
    }

    @Test
    void theIcePortIsKnownBeforeTheStartWithoutChangingTheConfig() throws Exception {
        // BungeeCord compares its query port with it before Geyser starts and applies the overrides
        GeyserConfig.BedrockConfig bedrock = config("""
                port: 19200
                webrtc-port: 19133
                """);
        Map<String, String> properties = Map.of("geyserWebrtcPort", "19134");

        assertEquals(19134, NetherNetPortProperties.icePort(bedrock, true, JAVA_PORT, properties::get));
        assertEquals(19133, bedrock.icePort(), "the config is only changed when Geyser starts");

        NetherNetPortProperties.apply(bedrock, true, JAVA_PORT, properties::get, logger);
        assertEquals(19134, bedrock.icePort());
    }
}
