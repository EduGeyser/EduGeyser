/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
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

package org.geysermc.geyser.network.netty;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

/**
 * Marks a channel whose transport already encrypts it, so the login sends
 * the encryption handshake without enabling Bedrock encryption.
 */
public final class BedrockEncryptionControl {
    private static final AttributeKey<Boolean> DISABLE_ENCRYPTION =
            AttributeKey.valueOf("geyser.disable_bedrock_encryption");

    private BedrockEncryptionControl() {
    }

    /**
     * Disables Bedrock encryption for a channel. The NetherNet initializer
     * calls this for every channel, as DTLS already encrypts it.
     */
    public static void disableEncryption(Channel channel) {
        channel.attr(DISABLE_ENCRYPTION).set(true);
    }

    public static boolean isEncryptionDisabled(Channel channel) {
        return Boolean.TRUE.equals(channel.attr(DISABLE_ENCRYPTION).get());
    }
}
