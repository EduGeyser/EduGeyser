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

package org.geysermc.geyser.network.bedrock;

import io.github.sendablemetatype.netty.channel.TransportDisconnectReason;
import org.cloudburstmc.protocol.bedrock.BedrockDisconnectReasons;
import org.geysermc.geyser.text.MinecraftLocale;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LoggingPacketHandlerTest {

    @Test
    void theTransportsReasonsReadWithoutAJavaLocale() {
        // As before the Java locale download finished, or after it failed
        assertNull(MinecraftLocale.getLocaleStringIfPresent(BedrockDisconnectReasons.UNKNOWN, "en_us"));

        assertEquals("Bedrock client connection lost",
                LoggingPacketHandler.transportReasonText(BedrockDisconnectReasons.getReason(TransportDisconnectReason.TRANSPORT_FAILED)));
        assertEquals("Bedrock client sent an invalid packet",
                LoggingPacketHandler.transportReasonText(BedrockDisconnectReasons.getReason(TransportDisconnectReason.BAD_PACKET)));
        assertEquals("Too much data queued on the Bedrock client's connection",
                LoggingPacketHandler.transportReasonText(BedrockDisconnectReasons.getReason(TransportDisconnectReason.QUEUE_TOO_LONG)));
        assertEquals("Bedrock client sent more split packets than could be reassembled",
                LoggingPacketHandler.transportReasonText(BedrockDisconnectReasons.getReason(TransportDisconnectReason.SPLIT_QUEUE_TOO_LONG)));
        assertEquals("Kicked for flying", LoggingPacketHandler.transportReasonText("Kicked for flying"));
    }
}
