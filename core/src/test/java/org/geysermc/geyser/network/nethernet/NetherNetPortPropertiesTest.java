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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NetherNetPortPropertiesTest {

    private final GeyserLogger logger = mock(GeyserLogger.class);

    private static GeyserConfig.BedrockConfig config(String yaml) throws Exception {
        return YamlConfigurationLoader.builder()
                .source(() -> new BufferedReader(new StringReader(yaml)))
                .defaultOptions(InterfaceDefaultOptions::addTo)
                .build().load().get(GeyserConfig.BedrockConfig.class);
    }

    private GeyserConfig.BedrockConfig apply(String yaml, boolean udpPortPropertyApplied, Map<String, String> properties)
            throws Exception {
        GeyserConfig.BedrockConfig bedrock = config(yaml);
        NetherNetPortProperties.apply(bedrock, udpPortPropertyApplied, properties::get, logger);
        return bedrock;
    }

    @Test
    void bothSidesUseTheBedrockPortByDefault() throws Exception {
        GeyserConfig.BedrockConfig bedrock = apply("port: 19200", false, Map.of());

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
                """, false, Map.of());

        assertEquals(19201, bedrock.icePort());
        assertEquals(19202, bedrock.signalingPort());
    }

    @Test
    void theUdpPortPropertyWinsOverWebrtcPortButNotOverTheSignalingPort() throws Exception {
        // As GeyserImpl leaves it after -DgeyserUdpPort=19300 set the Bedrock port
        GeyserConfig.BedrockConfig bedrock = apply("""
                port: 19300
                webrtc-port: 19201
                signaling:
                  port: 19202
                """, true, Map.of());

        assertEquals(19300, bedrock.icePort());
        assertEquals(19202, bedrock.signalingPort());
    }

    @Test
    void theUdpPortPropertyMovesTheSignalingPortWithTheBedrockPort() throws Exception {
        GeyserConfig.BedrockConfig bedrock = apply("port: 19300", true, Map.of());

        assertEquals(19300, bedrock.icePort());
        assertEquals(19300, bedrock.signalingPort());
    }

    @Test
    void theirOwnPropertiesWinOverEverything() throws Exception {
        GeyserConfig.BedrockConfig bedrock = apply("""
                port: 19300
                webrtc-port: 19201
                signaling:
                  port: 19202
                """, true, Map.of("geyserWebrtcPort", "19401", "geyserSignalingPort", "19402"));

        assertEquals(19401, bedrock.icePort());
        assertEquals(19402, bedrock.signalingPort());
    }

    @Test
    void anInvalidPropertyKeepsTheConfiguredPort() throws Exception {
        GeyserConfig.BedrockConfig bedrock = apply("""
                port: 19200
                webrtc-port: 19201
                """, false, Map.of("geyserWebrtcPort", "70000", "geyserSignalingPort", "tcp"));

        assertEquals(19201, bedrock.icePort());
        assertEquals(19200, bedrock.signalingPort());
        verify(logger).error(contains("Invalid NetherNet UDP port from system property: 70000"));
        verify(logger).error(contains("Invalid HTTP signaling port from system property: tcp"));
    }
}
