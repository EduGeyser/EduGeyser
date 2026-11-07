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

import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientToServerHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.CommandRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundLoadingScreenPacket;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.registry.BlockRegistries;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.registry.type.BlockMappings;
import org.geysermc.geyser.registry.type.ItemMappings;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.SessionManager;
import org.geysermc.geyser.util.LoginEncryptionUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginPacketHandlerTest {
    private static final int VERSION = GameProtocol.DEFAULT_BEDROCK_PROTOCOL;

    private GeyserImpl geyser;
    private MockedStatic<GeyserImpl> geyserInstance;
    private MockedStatic<LoginEncryptionUtils> loginValidation;
    private MockedConstruction<UpstreamPacketHandler> upstreamHandlers;
    private boolean blocksAdded;
    private boolean itemsAdded;

    @BeforeEach
    void mockGeyser() {
        geyser = mock(GeyserImpl.class, RETURNS_DEEP_STUBS);
        when(geyser.getSessionManager()).thenReturn(new SessionManager());
        // Registries reads the instance when first loaded
        geyserInstance = mockStatic(GeyserImpl.class);
        geyserInstance.when(GeyserImpl::getInstance).thenReturn(geyser);
        // Accepts every login, leaving the session open
        loginValidation = mockStatic(LoginEncryptionUtils.class);
        upstreamHandlers = mockConstruction(UpstreamPacketHandler.class);
        // Looked up by the login's protocol version
        blocksAdded = BlockRegistries.BLOCKS.get().putIfAbsent(VERSION, mock(BlockMappings.class)) == null;
        itemsAdded = Registries.ITEMS.get().putIfAbsent(VERSION, mock(ItemMappings.class)) == null;
    }

    @AfterEach
    void closeMocks() {
        if (blocksAdded) {
            BlockRegistries.BLOCKS.get().remove(VERSION);
        }
        if (itemsAdded) {
            Registries.ITEMS.get().remove(VERSION);
        }
        upstreamHandlers.close();
        loginValidation.close();
        geyserInstance.close();
    }

    @Test
    void anAcceptedLoginHandsOverRightAway() {
        // Nothing waits for an answer to the handshake an Education client gets
        Connection connection = new Connection();
        connection.handle(requestNetworkSettings());
        connection.handle(login());
        connection.assertHandedOver();
    }

    @Test
    void onlyRequestNetworkSettingsComesFirst() {
        // LoggingPacketHandler does not pass the last one to its default handler
        for (BedrockPacket packet : List.of(login(), new ClientToServerHandshakePacket(), new CommandRequestPacket(), new ServerboundLoadingScreenPacket())) {
            new Connection().assertDisconnectsOn(packet);
        }
    }

    @Test
    void onlyLoginFollowsNetworkSettings() {
        for (BedrockPacket packet : List.of(requestNetworkSettings(), new ClientToServerHandshakePacket(), new CommandRequestPacket())) {
            Connection connection = new Connection();
            connection.handle(requestNetworkSettings());
            connection.assertDisconnectsOn(packet);
        }
    }

    private static RequestNetworkSettingsPacket requestNetworkSettings() {
        RequestNetworkSettingsPacket packet = new RequestNetworkSettingsPacket();
        packet.setProtocolVersion(VERSION);
        return packet;
    }

    private static LoginPacket login() {
        LoginPacket packet = new LoginPacket();
        packet.setProtocolVersion(VERSION);
        return packet;
    }

    private class Connection {
        private final BedrockServerSession upstream;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final GeyserSession session = mock(GeyserSession.class, RETURNS_DEEP_STUBS);
        private final LoginPacketHandler handler;

        Connection() {
            when(session.xuid()).thenReturn("1");
            when(session.isClosed()).thenAnswer(call -> closed.get());
            doAnswer(call -> {
                closed.set(true);
                return null;
            }).when(session).disconnect(anyString());
            upstream = session.getUpstream().getSession();
            handler = new LoginPacketHandler(geyser, session);
        }

        void handle(BedrockPacket packet) {
            handler.handlePacket(packet);
        }

        void assertDisconnectsOn(BedrockPacket packet) {
            handle(packet);
            // Nothing after it is handled, not even a proper login
            handle(packet);
            handle(requestNetworkSettings());
            handle(login());

            // Sends the reason, which a forcible close would drop
            verify(session).disconnect("Invalid packet received!");
            verify(session, never()).forciblyCloseUpstream();
            verify(upstream, never()).setPacketHandler(any());
        }

        void assertHandedOver() {
            verify(session, never()).disconnect(anyString());
            verify(session, never()).forciblyCloseUpstream();
            verify(upstream).setPacketHandler(any(UpstreamPacketHandler.class));
        }
    }
}
