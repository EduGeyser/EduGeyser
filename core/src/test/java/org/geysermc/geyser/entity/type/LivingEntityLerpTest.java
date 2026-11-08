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

import net.kyori.adventure.text.Component;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.entity.EntityTypeDefinition;
import org.geysermc.geyser.entity.VanillaEntities;
import org.geysermc.geyser.entity.spawn.EntitySpawnContext;
import org.geysermc.geyser.entity.type.living.ArmorStandEntity;
import org.geysermc.geyser.entity.type.living.SquidEntity;
import org.geysermc.geyser.entity.type.living.animal.StriderEntity;
import org.geysermc.geyser.entity.type.living.merchant.VillagerEntity;
import org.geysermc.geyser.entity.type.living.monster.cubemob.MagmaCubeEntity;
import org.geysermc.geyser.entity.type.player.SessionPlayerEntity;
import org.geysermc.geyser.inventory.GeyserItemStack;
import org.geysermc.geyser.inventory.PlayerInventory;
import org.geysermc.geyser.item.Items;
import org.geysermc.geyser.level.WorldManager;
import org.geysermc.geyser.level.block.Blocks;
import org.geysermc.geyser.level.block.property.Properties;
import org.geysermc.geyser.level.block.type.BedBlock;
import org.geysermc.geyser.level.block.type.Block;
import org.geysermc.geyser.level.block.type.BlockState;
import org.geysermc.geyser.level.physics.Direction;
import org.geysermc.geyser.scoreboard.network.util.GeyserMockContext;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.cache.EntityCache;
import org.geysermc.geyser.session.cache.WorldCache;
import org.geysermc.geyser.translator.protocol.java.entity.JavaTeleportEntityTranslator;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Pose;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ByteEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ObjectEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
     * An awake villager is interpolating from x = 1.5 toward 4.5 when it gets into the bed at
     * x = 2.5, exactly where the first step put it. Getting into bed ends the lerp all the same.
     */
    @Test
    void gettingIntoBedEndsTheLerpEvenWithoutMovingTheVillager() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            VillagerEntity villager = villager(context, fixture);
            villager.setPosition(Vector3f.from(1.5f, 0.6875f, 0.5f));
            villager.moveAbsolute(Vector3f.from(4.5f, 0.6875f, 0.5f), 0, 0, 0, false, false);
            assertEquals(Vector3f.from(2.5f, 0.6875f, 0.5f), villager.position());

            villager.setPose(Pose.SLEEPING);
            villager.setBedPosition(BED);
            fixture.runScheduledStep();

            assertEquals(Vector3f.from(2.5f, 0.6875f, 0.5f), villager.position());
            assertEquals(1, fixture.moves());
            assertTrue(fixture.scheduled().isEmpty());
        });
    }

    /**
     * The client last got a sleeping villager in its bed pose. Waking up sends the Java position
     * and rotation in full, since later relative moves only send what changed.
     */
    @Test
    void wakingUpSendsTheJavaPoseAgain() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            VillagerEntity villager = sleepingVillager(context, fixture);
            villager.moveAbsolute(Vector3f.from(2.5f, 0.6875f, 0.5f), 20, 10, 30, false, false);
            assertBedPose(fixture, villager, 2.5f);

            villager.setPose(Pose.STANDING);
            villager.setBedPosition(new ObjectEntityMetadata<>(0, MetadataTypes.OPTIONAL_BLOCK_POS, Optional.empty()));

            assertJavaPose(fixture, villager, 2.5f);
        });
    }

    /**
     * The bed is gone while the villager is still marked as sleeping in it, so a move sends the
     * plain Java pose rather than a bed pose without a bed.
     */
    @Test
    void aSleepingVillagerWithoutABedGetsTheJavaPose() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            VillagerEntity villager = sleepingVillager(context, fixture);
            BlockState air = mock(BlockState.class);
            when(air.block()).thenReturn(mock(Block.class));
            WorldManager worldManager = context.mockOrSpy(GeyserImpl.class).getWorldManager();
            when(worldManager.blockAt(any(GeyserSession.class), any(Vector3i.class))).thenReturn(air);

            villager.moveAbsolute(Vector3f.from(2.5f, 0.6875f, 0.5f), 20, 10, 30, false, false);

            assertJavaPose(fixture, villager, 2.5f);
        });
    }

    /**
     * The sleeping pose arrives before the bed position and no move follows. Once both are there,
     * the client gets the bed pose.
     */
    @Test
    void gettingIntoBedSendsTheBedPoseWhenThePoseComesFirst() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            VillagerEntity villager = villager(context, fixture);

            villager.setPose(Pose.SLEEPING);
            villager.setBedPosition(BED);

            assertEquals(1, fixture.absoluteMoves());
            assertBedPose(fixture, villager, 2.5f);
        });
    }

    /**
     * The bed position arrives before the sleeping pose and no move follows. The same pose sent
     * again changes nothing, so it sends nothing.
     */
    @Test
    void gettingIntoBedSendsTheBedPoseWhenTheBedComesFirst() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            VillagerEntity villager = villager(context, fixture);

            villager.setBedPosition(BED);
            villager.setPose(Pose.SLEEPING);
            villager.setPose(Pose.SLEEPING);

            assertEquals(1, fixture.absoluteMoves());
            assertBedPose(fixture, villager, 2.5f);
        });
    }

    /**
     * A new bed while asleep moves the villager there, and no move follows.
     */
    @Test
    void aNewBedWhileAsleepSendsTheBedPose() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            VillagerEntity villager = sleepingVillager(context, fixture);

            villager.setBedPosition(new ObjectEntityMetadata<>(0, MetadataTypes.OPTIONAL_BLOCK_POS, Optional.of(Vector3i.from(12, 0, 0))));

            assertEquals(2, fixture.absoluteMoves());
            assertBedPose(fixture, villager, 12.5f);
        });
    }

    private static final ObjectEntityMetadata<Optional<Vector3i>> BED =
        new ObjectEntityMetadata<>(0, MetadataTypes.OPTIONAL_BLOCK_POS, Optional.of(Vector3i.from(2, 0, 0)));

    /**
     * An awake villager at the origin, with a bed facing east at block (2, 0, 0) in the world.
     */
    private static VillagerEntity villager(GeyserMockContext context, Fixture fixture) {
        BlockState bed = mock(BlockState.class);
        when(bed.block()).thenReturn(mock(BedBlock.class));
        when(bed.getValue(Properties.HORIZONTAL_FACING)).thenReturn(Direction.EAST);
        WorldManager worldManager = mock(WorldManager.class);
        when(worldManager.blockAt(any(GeyserSession.class), any(Vector3i.class))).thenReturn(bed);
        when(context.mockOrSpy(GeyserImpl.class).getWorldManager()).thenReturn(worldManager);

        VillagerEntity villager = new VillagerEntity(Fixture.spawnContext(fixture.session(), VanillaEntities.VILLAGER, 3));
        villager.setValid(true);
        return villager;
    }

    /**
     * A villager asleep in that bed, which puts it at x = 2.5.
     */
    private static VillagerEntity sleepingVillager(GeyserMockContext context, Fixture fixture) {
        VillagerEntity villager = villager(context, fixture);
        villager.setPose(Pose.SLEEPING);
        villager.setBedPosition(BED);
        return villager;
    }

    /**
     * The last packet sent the villager at {@code x} in its plain Java pose.
     */
    private static void assertJavaPose(Fixture fixture, VillagerEntity villager, float x) {
        MoveEntityAbsolutePacket packet = assertInstanceOf(MoveEntityAbsolutePacket.class, fixture.sent().getLast());
        assertEquals(villager.bedrockRotation(), packet.getRotation());
        assertEquals(x, packet.getPosition().getX());
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
     * An invisible armor stand with a name and equipment shows the name through a second entity.
     * Every step takes that entity along, or the name stays where the lerp started.
     */
    @Test
    void anArmorStandStepMovesItsNameTagEntity() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            EntityCache entityCache = mock(EntityCache.class);
            when(entityCache.nextEntityId()).thenReturn(NAME_TAG_ENTITY_ID);
            when(fixture.session().getEntityCache()).thenReturn(entityCache);
            when(fixture.session().locale()).thenReturn("en_us");
            ArmorStandEntity armorStand = armorStand(fixture);
            armorStand.setFlags(new ByteEntityMetadata(0, MetadataTypes.BYTE, (byte) 0x20));
            armorStand.setCustomName(new ObjectEntityMetadata<>(2, MetadataTypes.OPTIONAL_COMPONENT, Optional.of(Component.text("name"))));
            armorStand.setHelmet(GeyserItemStack.of(fixture.session(), Items.DIAMOND_HELMET.javaId(), 1));

            armorStand.moveAbsolute(Vector3f.from(3, 0, 0), 0, 0, 0, false, false);
            fixture.runScheduledStep();
            fixture.runScheduledStep();

            assertEquals(Vector3f.from(3, 0, 0), armorStand.position());
            MoveEntityAbsolutePacket nameTagMove = fixture.sent().stream()
                .filter(MoveEntityAbsolutePacket.class::isInstance)
                .map(MoveEntityAbsolutePacket.class::cast)
                .filter(packet -> packet.getRuntimeEntityId() == NAME_TAG_ENTITY_ID)
                .reduce((first, second) -> second)
                .orElseThrow();
            assertEquals(3, nameTagMove.getPosition().getX());
            assertTrue(nameTagMove.isTeleported());
        });
    }

    /**
     * An armor stand shows its yaw as its pitch and head yaw too. A step after a turn sends all
     * three, not only the part that changed on Java.
     */
    @Test
    void anArmorStandStepSendsItsYawAsTheWholeRotation() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            ArmorStandEntity armorStand = armorStand(fixture);

            armorStand.moveRelative(1, 0, 0, 90, 0, 0, false);

            MoveEntityDeltaPacket packet = assertInstanceOf(MoveEntityDeltaPacket.class, fixture.sent().getLast());
            assertTrue(packet.getFlags().containsAll(List.of(MoveEntityDeltaPacket.Flag.HAS_PITCH,
                MoveEntityDeltaPacket.Flag.HAS_YAW, MoveEntityDeltaPacket.Flag.HAS_HEAD_YAW)));
            assertEquals(90, packet.getPitch());
            assertEquals(90, packet.getYaw());
            assertEquals(90, packet.getHeadYaw());
        });
    }

    /**
     * A magma cube squashes when it lands and stretches when it jumps. Each server update with a
     * ground change sends that event once, whether it is interpolated (the first two here) or
     * applied at once out of the interpolation radius (the third).
     */
    @Test
    void aMagmaCubeJumpsAndLandsOncePerServerUpdate() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            MagmaCubeEntity magmaCube = new MagmaCubeEntity(Fixture.spawnContext(fixture.session(), VanillaEntities.MAGMA_CUBE, 5));
            magmaCube.setValid(true);

            magmaCube.moveRelative(0.5, 0, 0, 0, 0, 0, true);
            magmaCube.moveRelative(0.5, 0.5, 0, 0, 0, 0, false);
            fixture.runAllSteps();
            when(fixture.player().position()).thenReturn(Vector3f.from(200, 0, 0));
            magmaCube.moveRelative(0.5, -0.5, 0, 0, 0, 0, true);

            List<Byte> events = fixture.sent().stream()
                .filter(SetEntityDataPacket.class::isInstance)
                .map(packet -> ((SetEntityDataPacket) packet).getMetadata().get(EntityDataTypes.CLIENT_EVENT))
                .filter(Objects::nonNull)
                .toList();
            // 1 lands, 2 jumps
            assertEquals(List.of((byte) 1, (byte) 2, (byte) 1), events);
        });
    }

    /**
     * A squid on land at the origin moves into water at x = 3 through an interpolation. The steps
     * that change its block run the water check, so it swims there instead of tipping over as on
     * land.
     */
    @Test
    void anInterpolatedSquidChecksForWaterOnEachNewBlock() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            int water = Blocks.WATER.defaultBlockState().javaId();
            WorldManager worldManager = mock(WorldManager.class);
            when(worldManager.getBlockAtAsync(any(GeyserSession.class), any(Vector3i.class))).thenAnswer(invocation ->
                CompletableFuture.completedFuture(invocation.getArgument(1, Vector3i.class).getX() == 3 ? water : Block.JAVA_AIR_ID));
            when(context.mockOrSpy(GeyserImpl.class).getWorldManager()).thenReturn(worldManager);
            SquidEntity squid = new SquidEntity(Fixture.spawnContext(fixture.session(), VanillaEntities.SQUID, 6));
            squid.setValid(true);

            squid.moveAbsolute(Vector3f.from(3, 0, 0), 0, 0, 0, false, false);
            fixture.runScheduledStep();
            fixture.runScheduledStep();
            squid.tick();

            assertEquals(Vector3f.from(3, 0, 0), squid.position());
            // On land the pitch tips toward -90; in water it heads for the swimming pitch, 0 without motion
            assertEquals(0, squid.getPitch());
        });
    }

    /**
     * While the strider moves toward x = 3, the server teleports it to x = 10. The strider and
     * its vehicle component end there, not at the old target plus the distance from where the
     * strider was shown.
     */
    @Test
    void aTeleportDuringALerpEndsAtTheTeleportTarget() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            fixture.startLerp();

            fixture.teleport(Vector3d.from(10, 0, 0), List.of());
            fixture.runAllSteps();

            assertEquals(Vector3f.from(10, 0, 0), fixture.strider().position());
            assertEquals(10, fixture.strider().getVehicleComponent().getBoundingBox().getMiddleX());
        });
    }

    /**
     * A relative teleport during a lerp builds on where the server has the strider, the lerp
     * target x = 3, rather than on where it is shown.
     */
    @Test
    void aRelativeTeleportDuringALerpBuildsOnTheLerpTarget() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            fixture.startLerp();

            fixture.teleport(Vector3d.from(5, 0, 0), List.of(PositionElement.X));
            fixture.runAllSteps();

            assertEquals(Vector3f.from(8, 0, 0), fixture.strider().position());
        });
    }

    /**
     * The same past the interpolation range: built on the shown position x = 1, the strider would
     * end at x = 5001. The vehicle component goes along to x = 5003.
     */
    @Test
    void aFarRelativeTeleportDuringALerpBuildsOnTheLerpTarget() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            fixture.startLerp();

            fixture.teleport(Vector3d.from(5000, 0, 0), List.of(PositionElement.X));
            fixture.runAllSteps();

            assertEquals(Vector3f.from(5003, 0, 0), fixture.strider().position());
            assertEquals(5003, fixture.strider().getVehicleComponent().getBoundingBox().getMiddleX());
        });
    }

    /**
     * A teleport past the interpolation range of a strider the player steers moves its vehicle
     * component too, so Geyser's simulation goes on from the new position.
     */
    @Test
    void aFarTeleportMovesTheComponentOfASteeredVehicle() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            fixture.holding().set(true);
            fixture.strider().setPassengers(new ArrayList<>(List.of(fixture.player())));
            assertTrue(fixture.strider().shouldSimulateMovement());

            fixture.teleport(Vector3d.from(5000, 0, 0), List.of());

            assertEquals(Vector3f.from(5000, 0, 0), fixture.strider().position());
            assertEquals(5000, fixture.strider().getVehicleComponent().getBoundingBox().getMiddleX());
        });
    }

    /**
     * The Java client interpolates a teleport that ends 64 blocks away.
     */
    @Test
    void aTeleportOf64BlocksIsInterpolated() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            // Near the target, so Geyser interpolates for this player
            when(fixture.player().position()).thenReturn(Vector3f.from(64, 0, 0));

            fixture.teleport(Vector3d.from(64, 0, 0), List.of());

            assertEquals(1, fixture.moves());
            assertEquals(0, fixture.absoluteMoves());
            fixture.runAllSteps();
            assertEquals(Vector3f.from(64, 0, 0), fixture.strider().position());
        });
    }

    /**
     * The Java client snaps a teleport past 64 blocks, so the Bedrock client gets it as a
     * teleport.
     */
    @Test
    void aTeleportPast64BlocksSnaps() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            when(fixture.player().position()).thenReturn(Vector3f.from(64.5f, 0, 0));

            fixture.teleport(Vector3d.from(64.5, 0, 0), List.of());

            assertEquals(Vector3f.from(64.5f, 0, 0), fixture.strider().position());
            assertTrue(fixture.lastAbsoluteMove().isTeleported());
            assertTrue(fixture.scheduled().isEmpty());
        });
    }

    /**
     * The 64 blocks count from where the Java client has the strider, the target x = 3 of the
     * running lerp. A teleport to x = 66 is interpolated, though the strider is shown 65 blocks
     * from there.
     */
    @Test
    void theInterpolationRangeCountsFromTheLerpTarget() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            when(fixture.player().position()).thenReturn(Vector3f.from(66, 0, 0));
            fixture.startLerp();

            fixture.teleport(Vector3d.from(66, 0, 0), List.of());

            assertEquals(2, fixture.moves());
            assertEquals(0, fixture.absoluteMoves());
            fixture.runAllSteps();
            assertEquals(Vector3f.from(66, 0, 0), fixture.strider().position());
        });
    }

    /**
     * Likewise a teleport to x = -62, 65 blocks from the lerp target, snaps, though the strider is
     * shown 63 blocks from there.
     */
    @Test
    void aTeleportPast64BlocksFromTheLerpTargetSnaps() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            // Near both the lerp target and the teleport target
            when(fixture.player().position()).thenReturn(Vector3f.from(-30, 0, 0));
            fixture.startLerp();

            fixture.teleport(Vector3d.from(-62, 0, 0), List.of());

            assertTrue(fixture.lastAbsoluteMove().isTeleported());
            fixture.runAllSteps();
            assertEquals(Vector3f.from(-62, 0, 0), fixture.strider().position());
        });
    }

    /**
     * The Java client applies a teleport past 64 blocks to a vehicle it steers and answers with
     * the vehicle's new position.
     */
    @Test
    void aFarTeleportOfASteeredVehicleIsAppliedAndAcknowledged() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            fixture.ride(true);

            fixture.teleport(Vector3d.from(100, 0, 0), List.of());

            assertEquals(Vector3f.from(100, 0, 0), fixture.strider().position());
            assertEquals(100, fixture.strider().getVehicleComponent().getBoundingBox().getMiddleX());
            assertTrue(fixture.lastAbsoluteMove().isTeleported());
            assertEquals(List.of(Vector3d.from(100, 0, 0)),
                fixture.vehicleMoves().stream().map(ServerboundMoveVehiclePacket::getPosition).toList());
        });
    }

    /**
     * The Java client answers a snapped teleport only for a vehicle it steers, not for one the
     * player just rides.
     */
    @Test
    void aFarTeleportOfAVehicleThePlayerDoesNotSteerIsNotAcknowledged() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            fixture.ride(false);

            fixture.teleport(Vector3d.from(100, 0, 0), List.of());

            assertEquals(Vector3f.from(100, 0, 0), fixture.strider().position());
            assertTrue(fixture.vehicleMoves().isEmpty());
        });
    }

    /**
     * The Java client also applies a teleport within 64 blocks to a vehicle it steers, interpolated
     * and without an answer. Geyser simulates the strider, so the strider and its component go
     * there at once, and the Bedrock client interpolates the move.
     */
    @Test
    void aNearTeleportOfASteeredVehicleIsApplied() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            fixture.ride(true);

            fixture.teleport(Vector3d.from(10, 0, 0), List.of());

            assertEquals(Vector3f.from(10, 0, 0), fixture.strider().position());
            assertEquals(10, fixture.strider().getVehicleComponent().getBoundingBox().getMiddleX());
            assertFalse(fixture.lastAbsoluteMove().isTeleported());
            assertTrue(fixture.scheduled().isEmpty());
            assertTrue(fixture.vehicleMoves().isEmpty());
        });
    }

    /**
     * A boat, a vehicle that is not a living entity, snaps a teleport past 64 blocks as a
     * teleport, with its vehicle component.
     */
    @Test
    void aFarTeleportOfABoatSnaps() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            BoatEntity boat = boat(fixture);

            fixture.teleport(boat, Vector3d.from(100, 0, 0), List.of());

            assertEquals(Vector3f.from(100, 0, 0), boat.position());
            assertEquals(100, boat.getVehicleComponent().getBoundingBox().getMiddleX());
            assertTrue(fixture.lastAbsoluteMove().isTeleported());
        });
    }

    /**
     * A relative teleport of a boat keeps the quarter turn that Geyser adds to the yaw of a boat
     * for Bedrock.
     */
    @Test
    void aRelativeTeleportOfABoatKeepsItsBedrockYaw() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            BoatEntity boat = boat(fixture);

            fixture.teleport(boat, Vector3d.from(5, 0, 0), List.of(PositionElement.X));

            assertEquals(Vector3f.from(5, 0, 0), boat.position());
            assertEquals(90, boat.getYaw());
        });
    }

    /**
     * A relative yaw builds on the Java yaw of a boat, 0 here: turning it by 30 degrees gives a
     * Java yaw of 30, shown on Bedrock as 120.
     */
    @Test
    void aRelativeYawOfABoatBuildsOnItsJavaYaw() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            BoatEntity boat = boat(fixture);

            fixture.teleport(boat, Vector3d.ZERO, Vector3d.ZERO, 30, List.of(PositionElement.X, PositionElement.Y_ROT));

            assertEquals(120, boat.getYaw());
        });
    }

    /**
     * A minecart interpolating toward x = 3 has ticked to x = 1. A relative teleport by 5 builds
     * on that target, as the Java client builds on its interpolation target, and ends at x = 8.
     */
    @Test
    void aRelativeTeleportOfAMinecartBuildsOnItsLerpTarget() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            MinecartEntity minecart = minecartAtOneOnTheWayToThree(fixture);

            fixture.teleport(minecart, Vector3d.from(5, 0, 0), List.of(PositionElement.X));
            tickThrice(minecart);

            assertEquals(Vector3f.from(8, 0, 0), minecart.position());
        });
    }

    /**
     * A teleport past 64 blocks from the interpolation target snaps the minecart there, and the
     * rest of the old interpolation does not pull it back.
     */
    @Test
    void aSnappedTeleportEndsTheLerpOfAMinecart() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            MinecartEntity minecart = minecartAtOneOnTheWayToThree(fixture);

            fixture.teleport(minecart, Vector3d.from(100, 0, 0), List.of());
            assertTrue(fixture.lastAbsoluteMove().isTeleported());
            tickThrice(minecart);

            assertEquals(Vector3f.from(100, 0, 0), minecart.position());
        });
    }

    /**
     * The interpolation range of a minecart also counts from its interpolation target, x = 60
     * here: a teleport to x = 110 is interpolated, though the minecart is shown 90 blocks away.
     */
    @Test
    void aMinecartInterpolatesATeleportWithin64BlocksOfItsLerpTarget() {
        mockContext(context -> {
            Fixture fixture = Fixture.create(context);
            when(fixture.player().position()).thenReturn(Vector3f.from(60, 0, 0));
            MinecartEntity minecart = minecart(fixture);
            minecart.moveAbsolute(Vector3f.from(60, 0, 0), 0, 0, 0, false, false);
            minecart.tick();
            Vector3f shown = minecart.position();

            fixture.teleport(minecart, Vector3d.from(110, 0, 0), List.of());

            assertEquals(shown, minecart.position());
            tickThrice(minecart);
            assertEquals(Vector3f.from(110, 0, 0), minecart.position());
        });
    }

    /**
     * A minecart at the origin, moved with the minecart logic that is not experimental.
     */
    private static MinecartEntity minecart(Fixture fixture) {
        when(fixture.session().getQueuedImmediatelyPackets()).thenReturn(new ArrayList<>());
        MinecartEntity minecart = new MinecartEntity(Fixture.spawnContext(fixture.session(), VanillaEntities.MINECART, 9));
        minecart.setValid(true);
        return minecart;
    }

    /**
     * A minecart that interpolates toward x = 3 and has ticked to x = 1.
     */
    private static MinecartEntity minecartAtOneOnTheWayToThree(Fixture fixture) {
        MinecartEntity minecart = minecart(fixture);
        minecart.moveAbsolute(Vector3f.from(3, 0, 0), 0, 0, 0, false, false);
        minecart.tick();
        assertEquals(Vector3f.from(1, 0, 0), minecart.position());
        return minecart;
    }

    /**
     * Runs the three ticks of a minecart interpolation.
     */
    private static void tickThrice(MinecartEntity minecart) {
        for (int i = 0; i < 3; i++) {
            minecart.tick();
        }
    }

    /**
     * An oak boat at the origin.
     */
    private static BoatEntity boat(Fixture fixture) {
        BoatEntity boat = new BoatEntity(Fixture.spawnContext(fixture.session(), VanillaEntities.OAK_BOAT, 7), BoatEntity.BoatVariant.OAK);
        boat.setValid(true);
        return boat;
    }

    private static final long NAME_TAG_ENTITY_ID = 10;

    /**
     * A visible armor stand at the origin.
     */
    private static ArmorStandEntity armorStand(Fixture fixture) {
        ArmorStandEntity armorStand = new ArmorStandEntity(Fixture.spawnContext(fixture.session(), VanillaEntities.ARMOR_STAND, 4));
        armorStand.setValid(true);
        return armorStand;
    }

    /**
     * A saddled strider at the origin on a mocked session that records the packets sent to the
     * client and to the server, and the scheduled lerp steps, which the tests run by hand. The
     * player holds the fungus on a stick while {@code holding} is set.
     */
    private record Fixture(GeyserSession session, StriderEntity strider, SessionPlayerEntity player,
                           List<BedrockPacket> sent, List<Packet> sentToServer, List<Runnable> scheduled,
                           AtomicBoolean holding) {

        static Fixture create(GeyserMockContext context) {
            GeyserSession session = context.mock(GeyserSession.class);
            when(session.getGeyser()).thenReturn(context.mockOrSpy(GeyserImpl.class));
            when(session.getMillisecondsPerTick()).thenReturn(50f);
            // Passenger changes refresh the name tag through the scoreboard
            WorldCache worldCache = new WorldCache(session);
            when(session.getWorldCache()).thenReturn(worldCache);

            List<BedrockPacket> sent = new ArrayList<>();
            doAnswer(invocation -> sent.add(invocation.getArgument(0))).when(session).sendUpstreamPacket(any());
            List<Packet> sentToServer = new ArrayList<>();
            doAnswer(invocation -> sentToServer.add(invocation.getArgument(0))).when(session).sendDownstreamGamePacket(any());
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
            return new Fixture(session, strider, player, sent, sentToServer, scheduled, holding);
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

        /**
         * Runs the scheduled steps until none is left, including stale ones of a replaced lerp,
         * which do nothing.
         */
        void runAllSteps() {
            while (!scheduled.isEmpty()) {
                runScheduledStep();
            }
        }

        /**
         * The player rides the strider, steering it if {@code steering} is set.
         */
        void ride(boolean steering) {
            holding.set(steering);
            strider.setPassengers(new ArrayList<>(List.of(player)));
            assertEquals(steering, strider.shouldSimulateMovement());
        }

        /**
         * Translates a server teleport of the strider.
         */
        void teleport(Vector3d position, List<PositionElement> relatives) {
            teleport(strider, position, relatives);
        }

        /**
         * Translates a server teleport of the entity.
         */
        void teleport(Entity entity, Vector3d position, List<PositionElement> relatives) {
            teleport(entity, position, Vector3d.ZERO, 0, relatives);
        }

        /**
         * Translates a server teleport of the entity with a motion and a yaw.
         */
        void teleport(Entity entity, Vector3d position, Vector3d motion, float yaw, List<PositionElement> relatives) {
            EntityCache entityCache = mock(EntityCache.class);
            when(entityCache.getEntityByJavaId(entity.getEntityId())).thenReturn(entity);
            when(session.getEntityCache()).thenReturn(entityCache);
            new JavaTeleportEntityTranslator().translate(session,
                new ClientboundTeleportEntityPacket(entity.getEntityId(), position, motion, yaw, 0, relatives, false));
        }

        /**
         * The last move sent to the client, which must be absolute.
         */
        MoveEntityAbsolutePacket lastAbsoluteMove() {
            return assertInstanceOf(MoveEntityAbsolutePacket.class, sent.getLast());
        }

        List<ServerboundMoveVehiclePacket> vehicleMoves() {
            return sentToServer.stream().filter(ServerboundMoveVehiclePacket.class::isInstance)
                .map(ServerboundMoveVehiclePacket.class::cast).toList();
        }

        long moves() {
            return sent.stream().filter(MoveEntityDeltaPacket.class::isInstance).count();
        }

        long absoluteMoves() {
            return sent.stream().filter(MoveEntityAbsolutePacket.class::isInstance).count();
        }
    }
}
