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

import java.util.function.UnaryOperator;

/**
 * The system properties that move NetherNet's two ports away from the Bedrock port, as upstream Geyser
 * reads them, plus the TCP port key only EduGeyser has.
 */
public final class NetherNetPortProperties {

    private NetherNetPortProperties() {
    }

    /**
     * Applies -DgeyserWebrtcPort and -DgeyserSignalingPort, once the Bedrock port is final. For the UDP
     * port, -DgeyserWebrtcPort wins, then the UDP port property (-DgeyserUdpPort or -DpluginUdpPort),
     * then "webrtc-port", then the Bedrock port. For the TCP port, -DgeyserSignalingPort wins, then
     * "port" under "signaling", then the Bedrock port, which clone-remote-port and the UDP port property
     * may have set. Hosting panels set the UDP port property with UDP in mind, so it does not override a
     * configured TCP port.
     *
     * @param bedrock                the Bedrock settings, changed in place
     * @param udpPortPropertyApplied whether the UDP port property set the Bedrock port
     * @param properties             looks up a system property, null when it is not set
     * @param logger                 where applied and invalid properties are reported
     */
    public static void apply(GeyserConfig.BedrockConfig bedrock, boolean udpPortPropertyApplied,
                             UnaryOperator<String> properties, GeyserLogger logger) {
        int webrtcPort = port(properties, "geyserWebrtcPort", "NetherNet UDP", logger);
        if (webrtcPort != 0) {
            bedrock.webrtcPort(webrtcPort);
            logger.info("NetherNet UDP port set from system property: " + webrtcPort);
        } else if (udpPortPropertyApplied && bedrock.webrtcPort() != 0) {
            bedrock.webrtcPort(0);
            logger.info("NetherNet UDP port set from the Bedrock port system property: " + bedrock.port());
        }
        int signalingPort = port(properties, "geyserSignalingPort", "HTTP signaling", logger);
        if (signalingPort != 0) {
            bedrock.signaling().port(signalingPort);
            logger.info("HTTP signaling port set from system property: " + signalingPort);
        }
    }

    /** @return the port in the property, or 0 when it is unset or holds no valid port */
    private static int port(UnaryOperator<String> properties, String property, String description, GeyserLogger logger) {
        String value = properties.apply(property);
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            int port = Integer.parseInt(value.trim());
            if (port < 1 || port > 65535) {
                throw new NumberFormatException("it must be between 1 and 65535 inclusive");
            }
            return port;
        } catch (NumberFormatException e) {
            logger.error("Invalid " + description + " port from system property: " + value
                    + " (" + e.getMessage() + ")! Defaulting to the configured port.");
            return 0;
        }
    }
}
