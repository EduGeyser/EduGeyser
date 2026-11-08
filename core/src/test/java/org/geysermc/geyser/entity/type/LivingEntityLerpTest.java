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

package org.geysermc.geyser.entity.type;

import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.entity.VanillaEntities;
import org.geysermc.geyser.entity.spawn.EntitySpawnContext;
import org.geysermc.geyser.entity.type.living.animal.StriderEntity;
import org.geysermc.geyser.entity.type.player.SessionPlayerEntity;
import org.geysermc.geyser.inventory.PlayerInventory;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.cache.WorldCache;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.geysermc.geyser.scoreboard.network.util.GeyserMockContext.mockContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LivingEntityLerpTest {

    /**
     * A strider the player steers is a passenger of a minecart, so the server moves it and a
     * position sync starts an interpolation. When the strider leaves the minecart, Geyser simulates
     * it from the minecart's position; the remaining steps must not pull it back toward the sync.
     */
    @Test
    void pendingStepsStopOnceTheSessionSimulatesTheVehicle() {
        mockContext(context -> {
            GeyserSession session = context.mock(GeyserSession.class);
            when(session.getGeyser()).thenReturn(context.mockOrSpy(GeyserImpl.class));
            when(session.getMillisecondsPerTick()).thenReturn(50f);
            // Passenger changes refresh the name tag through the scoreboard
            WorldCache worldCache = new WorldCache(session);
            when(session.getWorldCache()).thenReturn(worldCache);

            List<BedrockPacket> sent = new ArrayList<>();
            doAnswer(invocation -> sent.add(invocation.getArgument(0))).when(session).sendUpstreamPacket(any());
            List<Runnable> scheduled = new ArrayList<>();
            doAnswer(invocation -> {
                scheduled.add(invocation.getArgument(0));
                return mock(ScheduledFuture.class);
            }).when(session).scheduleInEventLoop(any(Runnable.class), anyLong(), any(TimeUnit.class));

            SessionPlayerEntity player = context.mock(SessionPlayerEntity.class);
            when(player.position()).thenReturn(Vector3f.ZERO);
            when(session.getPlayerEntity()).thenReturn(player);
            PlayerInventory inventory = context.mock(PlayerInventory.class);
            when(inventory.isHolding(any())).thenReturn(true);
            when(session.getPlayerInventory()).thenReturn(inventory);

            EntitySpawnContext spawnContext = EntitySpawnContext.DUMMY_CONTEXT.apply(session, UUID.randomUUID(), VanillaEntities.STRIDER);
            spawnContext.geyserId(2L);
            spawnContext.javaId(2);
            StriderEntity strider = new StriderEntity(spawnContext);
            strider.setValid(true);
            strider.setFlag(EntityFlag.SADDLED, true);
            strider.setPassengers(new ArrayList<>(List.of(player)));
            Entity minecart = context.mock(Entity.class);
            when(minecart.position()).thenReturn(Vector3f.ZERO);
            strider.setVehicle(minecart);
            assertFalse(strider.shouldSimulateMovement());

            // The first of three steps goes out at once, the next one is scheduled
            strider.moveAbsolute(Vector3f.from(3, 0, 0), 0, 0, 0, false, false);
            assertEquals(Vector3f.from(1, 0, 0), strider.position());
            assertEquals(1, moves(sent));
            assertEquals(1, scheduled.size());

            strider.stopRiding(minecart);
            assertTrue(strider.shouldSimulateMovement());
            assertEquals(Vector3f.ZERO, strider.position());

            scheduled.removeFirst().run();
            assertEquals(Vector3f.ZERO, strider.position());
            assertEquals(1, moves(sent));
            assertTrue(scheduled.isEmpty());
        });
    }

    private static long moves(List<BedrockPacket> sent) {
        return sent.stream().filter(MoveEntityDeltaPacket.class::isInstance).count();
    }
}
