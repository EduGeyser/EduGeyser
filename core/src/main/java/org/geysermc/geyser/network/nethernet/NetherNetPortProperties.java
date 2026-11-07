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

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The overrides of the Bedrock port and of NetherNet's two ports: clone-remote-port, the UDP port
 * property hosting panels set, and the properties that move NetherNet's ports away from the Bedrock
 * port, as upstream Geyser reads them, plus the TCP port key only EduGeyser has.
 * <p>
 * For the UDP port, -DgeyserWebrtcPort wins, then the UDP port property (-DgeyserUdpPort or
 * -DpluginUdpPort), then "webrtc-port", then the Bedrock port. For the TCP port,
 * -DgeyserSignalingPort wins, then "port" under "signaling", then the Bedrock port, which
 * clone-remote-port and the UDP port property may have set. Hosting panels set the UDP port property
 * with UDP in mind, so it does not override a configured TCP port.
 */
public final class NetherNetPortProperties {

    private NetherNetPortProperties() {
    }

    /**
     * The ports the overrides leave, and what to log about them.
     *
     * @param bedrockPort   the Bedrock port
     * @param webrtcPort    "webrtc-port", 0 standing for the Bedrock port
     * @param signalingPort "port" under "signaling", 0 standing for the Bedrock port
     * @param applied       one line for each override that applied
     * @param invalid       one line for each property that held no valid port
     */
    record Ports(int bedrockPort, int webrtcPort, int signalingPort, List<String> applied, List<String> invalid) {
        int icePort() {
            return webrtcPort == 0 ? bedrockPort : webrtcPort;
        }
    }

    /**
     * Applies every override to the config and logs what each one set.
     *
     * @param bedrock        the Bedrock settings, changed in place
     * @param pluginPlatform whether Geyser runs as a plugin or mod, where clone-remote-port and the
     *                       UDP port property apply
     * @param javaPort       the Java server's port, -1 when it is unknown
     * @param properties     looks up a system property, null when it is not set
     * @param logger         where applied and invalid properties are reported
     * @throws NumberFormatException if the UDP port property is neither a number nor "server"
     */
    public static void apply(GeyserConfig.BedrockConfig bedrock, boolean pluginPlatform, int javaPort,
                             UnaryOperator<String> properties, GeyserLogger logger) {
        Ports ports = resolve(bedrock, pluginPlatform, javaPort, properties);
        ports.applied().forEach(logger::info);
        ports.invalid().forEach(logger::error);
        bedrock.port(ports.bedrockPort());
        bedrock.webrtcPort(ports.webrtcPort());
        bedrock.signaling().port(ports.signalingPort());
    }

    /**
     * The UDP port NetherNet binds once {@link #apply} has run, without changing the config or
     * logging, for checks that run before Geyser starts, such as BungeeCord's query port check.
     *
     * @throws NumberFormatException if the UDP port property is neither a number nor "server"
     */
    public static int icePort(GeyserConfig.BedrockConfig bedrock, boolean pluginPlatform, int javaPort,
                              UnaryOperator<String> properties) {
        return resolve(bedrock, pluginPlatform, javaPort, properties).icePort();
    }

    static Ports resolve(GeyserConfig.BedrockConfig bedrock, boolean pluginPlatform, int javaPort,
                         UnaryOperator<String> properties) {
        List<String> applied = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        int bedrockPort = bedrock.port();
        boolean udpPortPropertyApplied = false;

        if (pluginPlatform) {
            String geyserUdpPort = value(properties, "geyserUdpPort");
            boolean generic = geyserUdpPort.isEmpty();
            String udpPort = generic ? value(properties, "pluginUdpPort") : geyserUdpPort;
            boolean matchServer = "server".equals(udpPort);
            if ((bedrock.cloneRemotePort() || matchServer) && javaPort != -1) {
                bedrockPort = javaPort;
                if (matchServer) {
                    applied.add(generic ? "Port set from system generic property to match Java server."
                            : "Port set from system property to match Java server.");
                    udpPortPropertyApplied = true;
                }
            }
            if (!udpPortPropertyApplied && !udpPort.isEmpty()) {
                bedrockPort = Integer.parseInt(udpPort);
                applied.add((generic ? "Port set from generic system property: " : "Port set from system property: ")
                        + bedrockPort);
                udpPortPropertyApplied = true;
            }
        }

        int webrtcPort = bedrock.webrtcPort();
        int webrtcProperty = port(properties, "geyserWebrtcPort", "NetherNet UDP", invalid);
        if (webrtcProperty != 0) {
            webrtcPort = webrtcProperty;
            applied.add("NetherNet UDP port set from system property: " + webrtcProperty);
        } else if (udpPortPropertyApplied && webrtcPort != 0) {
            webrtcPort = 0;
            applied.add("NetherNet UDP port set from the Bedrock port system property: " + bedrockPort);
        }

        int signalingPort = bedrock.signaling().port();
        int signalingProperty = port(properties, "geyserSignalingPort", "HTTP signaling", invalid);
        if (signalingProperty != 0) {
            signalingPort = signalingProperty;
            applied.add("HTTP signaling port set from system property: " + signalingProperty);
        }
        return new Ports(bedrockPort, webrtcPort, signalingPort, List.copyOf(applied), List.copyOf(invalid));
    }

    private static String value(UnaryOperator<String> properties, String property) {
        String value = properties.apply(property);
        return value == null ? "" : value;
    }

    /** @return the port in the property, or 0 when it is unset or holds no valid port */
    private static int port(UnaryOperator<String> properties, String property, String description, List<String> invalid) {
        String value = value(properties, property);
        if (value.isEmpty()) {
            return 0;
        }
        try {
            int port = Integer.parseInt(value.trim());
            if (port < 1 || port > 65535) {
                throw new NumberFormatException("it must be between 1 and 65535 inclusive");
            }
            return port;
        } catch (NumberFormatException e) {
            invalid.add("Invalid " + description + " port from system property: " + value
                    + " (" + e.getMessage() + ")! Defaulting to the configured port.");
            return 0;
        }
    }
}
