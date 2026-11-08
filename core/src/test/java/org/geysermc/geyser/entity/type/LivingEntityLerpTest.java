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
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.entity.EntityTypeDefinition;
import org.geysermc.geyser.entity.VanillaEntities;
import org.geysermc.geyser.entity.spawn.EntitySpawnContext;
import org.geysermc.geyser.entity.type.living.animal.StriderEntity;
import org.geysermc.geyser.entity.type.living.merchant.VillagerEntity;
import org.geysermc.geyser.entity.type.player.SessionPlayerEntity;
import org.geysermc.geyser.inventory.PlayerInventory;
import org.geysermc.geyser.level.WorldManager;
import org.geysermc.geyser.level.block.property.Properties;
import org.geysermc.geyser.level.block.type.BedBlock;
import org.geysermc.geyser.level.block.type.BlockState;
import org.geysermc.geyser.level.physics.Direction;
import org.geysermc.geyser.scoreboard.network.util.GeyserMockContext;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.cache.WorldCache;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ObjectEntityMetadata;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.geysermc.geyser.scoreboard.network.util.GeyserMockContext.mockContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Lerp steps toward a server position must stop once something else moves the entity or the
 * session moves the vehicle itself, or they pull it back toward that old target.
 */
class LivingEntityLerpTest {

    /**
     * A steered strider in a minecart is moved by the server. When it leaves the minecart, Geyser
     * simulates it from the minecart's position. The pending steps are dropped then, so they stay
     * dropped when the player puts the fungus on a stick away before the next step.
     */
    @Test
    void leavingAVehicleDropsTheStepsEvenIfControlEndsBeforeTheNextOne() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            fixture.holding().set(true);
            fixture.strider().setPassengers(new ArrayList<>(List.of(fixture.player())));
            Entity minecart = context.mock(Entity.class);
            when(minecart.position()).thenReturn(Vector3f.ZERO);
            fixture.strider().setVehicle(minecart);
            assertFalse(fixture.strider().shouldSimulateMovement());
            fixture.startLerp();

            fixture.strider().stopRiding(minecart);
            assertTrue(fixture.strider().shouldSimulateMovement());
            assertEquals(Vector3f.ZERO, fixture.strider().position());
            fixture.holding().set(false);
            fixture.runScheduledStep();

            assertEquals(Vector3f.ZERO, fixture.strider().position());
            assertEquals(1, fixture.moves());
            assertTrue(fixture.scheduled().isEmpty());
        });
    }

    /**
     * The player mounts a strider that the server is moving, holding the fungus on a stick, and
     * puts it away before the next step.
     */
    @Test
    void mountingDropsTheStepsEvenIfControlEndsBeforeTheNextOne() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            fixture.holding().set(true);
            fixture.startLerp();

            fixture.strider().setPassengers(new ArrayList<>(List.of(fixture.player())));
            assertTrue(fixture.strider().shouldSimulateMovement());
            fixture.holding().set(false);
            fixture.runScheduledStep();

            assertEquals(Vector3f.from(1, 0, 0), fixture.strider().position());
            assertEquals(1, fixture.moves());
            assertTrue(fixture.scheduled().isEmpty());
        });
    }

    /**
     * The player rides a strider the server is moving and takes out the fungus on a stick between
     * two steps, so no passenger change announces the new control.
     */
    @Test
    void stepsStopOnceThePlayerTakesControl() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            fixture.strider().setPassengers(new ArrayList<>(List.of(fixture.player())));
            assertFalse(fixture.strider().shouldSimulateMovement());
            fixture.startLerp();

            fixture.holding().set(true);
            fixture.runScheduledStep();

            assertEquals(Vector3f.from(1, 0, 0), fixture.strider().position());
            assertEquals(1, fixture.moves());
            assertTrue(fixture.scheduled().isEmpty());
        });
    }

    /**
     * The player riding a strider takes out the fungus on a stick, Geyser's simulation moves it,
     * and the player puts the fungus away before the next step, so no hook sees the handoff.
     */
    @Test
    void stepsStopAfterGeyserSimulatedTheVehicle() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            fixture.strider().setPassengers(new ArrayList<>(List.of(fixture.player())));
            fixture.startLerp();

            fixture.holding().set(true);
            // What VehicleComponent#moveVehicle does on a simulation tick
            fixture.strider().setPosition(Vector3f.from(1.5f, 0, 0));
            fixture.holding().set(false);
            fixture.runScheduledStep();

            assertEquals(Vector3f.from(1.5f, 0, 0), fixture.strider().position());
            assertEquals(1, fixture.moves());
            assertTrue(fixture.scheduled().isEmpty());
        });
    }

    /**
     * While the server moves a strider toward x = 3, a position sync puts it at x = 100, too far
     * to interpolate, so it moves there at once. The pending step must not pull it back.
     */
    @Test
    void stepsStopAfterADirectServerMove() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            fixture.startLerp();

            fixture.strider().moveAbsolute(Vector3f.from(100, 0, 0), 0, 0, 0, false, false);
            fixture.runScheduledStep();

            assertEquals(Vector3f.from(100, 0, 0), fixture.strider().position());
            assertEquals(1, fixture.moves());
            assertTrue(fixture.scheduled().isEmpty());
        });
    }

    /**
     * A relative update that arrives after such a direct move, before the pending step runs,
     * builds on the new position instead of the old target.
     */
    @Test
    void aRelativeUpdateAfterADirectServerMoveBuildsOnIt() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            // Near x = 100, so the relative update is interpolated
            when(fixture.player().position()).thenReturn(Vector3f.from(60, 0, 0));
            fixture.startLerp();

            fixture.strider().moveAbsolute(Vector3f.from(100, 0, 0), 0, 0, 0, false, false);
            fixture.strider().moveRelative(3, 0, 0, 0, 0, 0, false);

            assertEquals(Vector3f.from(101, 0, 0), fixture.strider().position());
        });
    }

    /**
     * Out of the interpolation radius, an absolute update puts the strider exactly where the lerp
     * has it now. That is the newer target, so the pending step toward the old one must not run.
     */
    @Test
    void aDirectServerMoveEndsTheLerpEvenWithoutMovingTheEntity() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            fixture.startLerp();

            when(fixture.player().position()).thenReturn(Vector3f.from(200, 0, 0));
            fixture.strider().moveAbsolute(Vector3f.from(1, 0, 0), 0, 0, 0, false, false);
            fixture.runScheduledStep();

            assertEquals(Vector3f.from(1, 0, 0), fixture.strider().position());
            assertEquals(1, fixture.moves());
        });
    }

    /**
     * Out of the interpolation radius, a relative update arrives mid-lerp. The server applied it
     * to the lerp target, so the strider must end at that target plus the delta, and the client
     * must get the whole position.
     */
    @Test
    void aRelativeUpdateOutsideTheRadiusFinishesTheLerp() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            fixture.startLerp();

            when(fixture.player().position()).thenReturn(Vector3f.from(200, 0, 0));
            fixture.strider().moveRelative(3, 0, 0, 0, 0, 0, false);

            assertEquals(Vector3f.from(6, 0, 0), fixture.strider().position());
            MoveEntityAbsolutePacket packet = assertInstanceOf(MoveEntityAbsolutePacket.class, fixture.sent().getLast());
            assertEquals(6, packet.getPosition().getX());
            fixture.runScheduledStep();
            assertEquals(Vector3f.from(6, 0, 0), fixture.strider().position());
        });
    }

    /**
     * A head rotation update mid-lerp adds a zero delta, which turns a -0.0 coordinate into 0.0
     * without moving the entity, so the lerp goes on.
     */
    @Test
    void aRotationUpdateThatFlipsTheSignOfZeroKeepsTheLerp() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            fixture.strider().setPosition(Vector3f.from(0, 0, -0.0f));
            fixture.strider().moveAbsolute(Vector3f.from(3, 0, -0.0f), 0, 0, 0, false, false);
            assertEquals(-0.0f, fixture.strider().position().getZ());

            fixture.strider().updateHeadLookRotation(10);
            assertEquals(0.0f, fixture.strider().position().getZ());
            fixture.runScheduledStep();

            assertEquals(2, fixture.strider().position().getX());
        });
    }

    /**
     * An absolute update for a sleeping villager, here out of the interpolation radius, keeps its
     * bed pose.
     */
    @Test
    void aSleepingVillagerKeepsItsBedPoseOnAnAbsoluteUpdate() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            VillagerEntity villager = sleepingVillager(context, fixture);

            when(fixture.player().position()).thenReturn(Vector3f.from(200, 0, 0));
            villager.moveAbsolute(Vector3f.from(3, 0.6875f, 0.5f), 20, 10, 30, false, false);

            assertBedPose(fixture, villager, 3);
        });
    }

    /**
     * A small move of a sleeping villager inside the interpolation radius is applied at once in
     * its bed pose instead of being interpolated.
     */
    @Test
    void aSleepingVillagerIsNotInterpolated() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            VillagerEntity villager = sleepingVillager(context, fixture);

            villager.moveRelative(0.5, 0, 0, 20, 10, 30, false);

            assertBedPose(fixture, villager, 3);
            assertTrue(fixture.scheduled().isEmpty());
        });
    }

    /**
     * A villager asleep in a bed facing east, which puts it at x = 2.5.
     */
    private static VillagerEntity sleepingVillager(GeyserMockContext context, Fixture fixture) {
        BlockState bed = mock(BlockState.class);
        when(bed.block()).thenReturn(mock(BedBlock.class));
        when(bed.getValue(Properties.HORIZONTAL_FACING)).thenReturn(Direction.EAST);
        WorldManager worldManager = mock(WorldManager.class);
        when(worldManager.blockAt(any(GeyserSession.class), any(Vector3i.class))).thenReturn(bed);
        when(context.mockOrSpy(GeyserImpl.class).getWorldManager()).thenReturn(worldManager);

        VillagerEntity villager = new VillagerEntity(Fixture.spawnContext(fixture.session(), VanillaEntities.VILLAGER, 3));
        villager.setValid(true);
        villager.setFlag(EntityFlag.SLEEPING, true);
        villager.setBedPosition(new ObjectEntityMetadata<>(0, MetadataTypes.OPTIONAL_BLOCK_POS, Optional.of(Vector3i.from(2, 0, 0))));
        return villager;
    }

    /**
     * The last packet sent the villager at {@code x} in the bed pose of an east facing bed: turned
     * along the bed and shifted half a block back onto it. The cached position stays unshifted.
     */
    private static void assertBedPose(Fixture fixture, VillagerEntity villager, float x) {
        assertEquals(x, villager.position().getX());
        MoveEntityAbsolutePacket packet = assertInstanceOf(MoveEntityAbsolutePacket.class, fixture.sent().getLast());
        assertEquals(Vector3f.from(0, 0, 90), packet.getRotation());
        assertEquals(x - 0.5f, packet.getPosition().getX());
    }

    /**
     * Out of the interpolation radius, with no lerp running, a relative update moves the vehicle
     * component once, so its box stays on the strider.
     */
    @Test
    void aRelativeUpdateOutsideTheRadiusMovesTheVehicleComponentOnce() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            when(fixture.player().position()).thenReturn(Vector3f.from(200, 0, 0));

            fixture.strider().moveRelative(3, 0, 0, 0, 0, 0, false);

            assertEquals(Vector3f.from(3, 0, 0), fixture.strider().position());
            assertEquals(3, fixture.strider().getVehicleComponent().getBoundingBox().getMiddleX());
        });
    }

    /**
     * A saddled strider at the origin on a mocked session that records the packets sent to the
     * client and the scheduled lerp steps, which the tests run by hand. The player holds the
     * fungus on a stick while {@code holding} is set.
     */
    private record Fixture(GeyserSession session, StriderEntity strider, SessionPlayerEntity player,
                           List<BedrockPacket> sent, List<Runnable> scheduled, AtomicBoolean holding) {

        static Fixture create(GeyserMockContext context) {
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
            AtomicBoolean holding = new AtomicBoolean();
            PlayerInventory inventory = context.mock(PlayerInventory.class);
            when(inventory.isHolding(any())).thenAnswer(invocation -> holding.get());
            when(session.getPlayerInventory()).thenReturn(inventory);

            StriderEntity strider = new StriderEntity(spawnContext(session, VanillaEntities.STRIDER, 2));
            strider.setValid(true);
            strider.setFlag(EntityFlag.SADDLED, true);
            return new Fixture(session, strider, player, sent, scheduled, holding);
        }

        static EntitySpawnContext spawnContext(GeyserSession session, EntityTypeDefinition<?> type, int id) {
            EntitySpawnContext spawnContext = EntitySpawnContext.DUMMY_CONTEXT.apply(session, UUID.randomUUID(), type);
            spawnContext.geyserId((long) id);
            spawnContext.javaId(id);
            return spawnContext;
        }

        /**
         * Starts a lerp toward x = 3 in three steps: the first goes out at once and moves the
         * strider to x = 1, the next one is scheduled.
         */
        void startLerp() {
            strider.moveAbsolute(Vector3f.from(3, 0, 0), 0, 0, 0, false, false);
            assertEquals(Vector3f.from(1, 0, 0), strider.position());
            assertEquals(1, moves());
            assertEquals(1, scheduled.size());
        }

        void runScheduledStep() {
            scheduled.removeFirst().run();
        }

        long moves() {
            return sent.stream().filter(MoveEntityDeltaPacket.class::isInstance).count();
        }
    }
}
