/*
 * Copyright (c) 2025 GeyserMC. http://geysermc.org
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

package org.geysermc.geyser.translator.protocol.java.level;

import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.ClientboundDebugRendererType;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundDebugRendererPacket;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.geyser.translator.protocol.Translator;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundGameTestHighlightPosPacket;

@Translator(packet = ClientboundGameTestHighlightPosPacket.class)
public class JavaGameTestHighlightPosTranslator extends PacketTranslator<ClientboundGameTestHighlightPosPacket> {

    @Override
    public void translate(GeyserSession session, ClientboundGameTestHighlightPosPacket packet) {
        Vector3i relative = packet.getRelativePos();
        ClientboundDebugRendererPacket marker = new ClientboundDebugRendererPacket();
        marker.setDebugMarkerType(ClientboundDebugRendererType.ADD_DEBUG_MARKER_CUBE);
        marker.setMarkerPosition(packet.getAbsolutePos().toFloat().add(0.5f, 0.5f, 0.5f));
        marker.setMarkerText(relative.getX() + ", " + relative.getY() + ", " + relative.getZ());
        // Java's GameTestBlockHighlightRenderer uses ARGB 0x6000ff00 for ten seconds.
        marker.setMarkerColorGreen(1f);
        marker.setMarkerColorAlpha(96 / 255f);
        marker.setMarkerDuration(10_000);
        session.sendUpstreamPacket(marker);
    }
}
