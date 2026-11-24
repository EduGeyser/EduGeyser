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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.ClientboundDebugRendererType;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundDebugRendererPacket;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.java.level.JavaGameTestHighlightPosTranslator;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundGameTestHighlightPosPacket;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GameTestHighlightTest {

    static Stream<BedrockCodec> codecs() {
        return GameProtocol.SUPPORTED_BEDROCK_PROTOCOLS.intStream().boxed().flatMap(protocol ->
            Stream.of(GameProtocol.getBedrockCodec(protocol), GameProtocol.getEducationCodec(protocol))
        ).filter(Objects::nonNull);
    }

    @ParameterizedTest
    @MethodSource("codecs")
    void writesJavaHighlightInBedrockMarkerFormat(BedrockCodec codec) throws Exception {
        GeyserSession session = mock(GeyserSession.class);
        new JavaGameTestHighlightPosTranslator().translate(session, new ClientboundGameTestHighlightPosPacket(
            Vector3i.from(-20, 64, 100), Vector3i.from(3, -1, 7)));
        var marker = ArgumentCaptor.forClass(ClientboundDebugRendererPacket.class);
        verify(session).sendUpstreamPacket(marker.capture());

        BedrockCodecHelper helper = codec.createHelper();
        ByteBuf buffer = Unpooled.buffer();
        try {
            codec.tryEncode(helper, buffer, marker.getValue());
            assertEquals("adddebugmarkercube", helper.readString(buffer));
            assertTrue(buffer.readBoolean());
            assertEquals("3, -1, 7", helper.readString(buffer));
            assertEquals(Vector3f.from(-19.5f, 64.5f, 100.5f), helper.readVector3f(buffer));
            assertEquals(0x6000ff00, buffer.readIntLE());
            assertEquals(10_000L, buffer.readLongLE());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @ParameterizedTest
    @MethodSource("codecs")
    void clearMarkersHasNoCubePayload(BedrockCodec codec) throws Exception {
        ClientboundDebugRendererPacket clear = new ClientboundDebugRendererPacket();
        clear.setDebugMarkerType(ClientboundDebugRendererType.CLEAR_DEBUG_MARKERS);
        BedrockCodecHelper helper = codec.createHelper();
        ByteBuf buffer = Unpooled.buffer();
        try {
            codec.tryEncode(helper, buffer, clear);
            assertEquals("cleardebugmarkers", helper.readString(buffer));
            assertFalse(buffer.readBoolean());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }
}
