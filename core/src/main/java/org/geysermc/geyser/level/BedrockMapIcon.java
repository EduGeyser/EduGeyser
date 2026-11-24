/*
 * Copyright (c) 2019-2026 GeyserMC. http://geysermc.org
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

package org.geysermc.geyser.level;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.geysermc.geyser.network.bedrock.GameProtocol;

import java.util.Optional;

public record BedrockMapIcon(int id, int argb) {

    public int idForProtocol(int protocolVersion) {
        if (GameProtocol.is26_50orHigher(protocolVersion)) {
            return id;
        }

        // Approximate decorations that older clients cannot display.
        return switch (id) {
            case 25, 26, 27 -> 4; // Abandoned camp, ancient city and mineshaft use the treasure X.
            case 28 -> 22; // Desert pyramid uses the jungle temple.
            case 29 -> 15; // Warm ocean ruin uses the monument.
            default -> id;
        };
    }

    public static BedrockMapIcon parse(JsonElement element) {
        if (!(element instanceof JsonObject object)) {
            throw new AssertionError("Expected map decoration mapping to be a JSON object");
        }

        int id = object.get("bedrock_id").getAsInt();
        int argb = Optional.ofNullable(object.get("color"))
            .map(JsonElement::getAsInt)
            .orElse(0xFFFFFFFF);
        return new BedrockMapIcon(id, argb);
    }
}
