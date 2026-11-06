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

import org.cloudburstmc.protocol.bedrock.BedrockDisconnectReasons;
import org.geysermc.geyser.text.GeyserLocale;
import org.geysermc.geyser.text.MinecraftLocale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;

class LoggingPacketHandlerTest {

    @AfterEach
    void tearDown() {
        MinecraftLocale.LOCALE_MAPPINGS.remove("en_us");
    }

    @Test
    void aLostConnectionIsLoggedWithTheJavaEditionsTextAndOtherReasonsAsTheyAre() {
        // As in the Java edition's en_us.json, which Geyser always loads
        MinecraftLocale.LOCALE_MAPPINGS.put("en_us", Map.of("disconnect.lost", "Connection Lost"));
        try (var locale = mockStatic(GeyserLocale.class)) {
            locale.when(GeyserLocale::getDefaultLocale).thenReturn("en_US");

            assertEquals("Connection Lost", LoggingPacketHandler.transportReasonText(BedrockDisconnectReasons.UNKNOWN));
            assertEquals("disconnect.removed", LoggingPacketHandler.transportReasonText(BedrockDisconnectReasons.REMOVED),
                    "the Java edition has no text for it");
            assertEquals("Kicked for flying", LoggingPacketHandler.transportReasonText("Kicked for flying"));
        }
    }
}
