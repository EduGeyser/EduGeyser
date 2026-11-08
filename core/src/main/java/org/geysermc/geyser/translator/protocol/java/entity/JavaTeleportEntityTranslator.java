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

package org.geysermc.geyser.translator.protocol.java.entity;

import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket;
import org.geysermc.geyser.entity.EntityTypeDefinition;
import org.geysermc.geyser.entity.type.BoatEntity;
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.entity.type.living.MobEntity;
import org.geysermc.geyser.entity.vehicle.ClientVehicle;
import org.geysermc.geyser.registry.java.JavaRegistries;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.cache.tags.Tag;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.geyser.translator.protocol.Translator;
import org.geysermc.geyser.util.MathUtils;
import org.geysermc.geyser.util.MinecraftKey;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

@Translator(packet = ClientboundTeleportEntityPacket.class)
public class JavaTeleportEntityTranslator extends PacketTranslator<ClientboundTeleportEntityPacket> {
    private static final Tag<EntityTypeDefinition<?>> NON_CONTROLLING_RIDER = new Tag<>(JavaRegistries.ENTITY_TYPE, MinecraftKey.key("non_controlling_rider"));

    @Override
    public void translate(GeyserSession session, ClientboundTeleportEntityPacket packet) {
        Entity entity = session.getEntityCache().getEntityByJavaId(packet.getId());

        boolean isPreviouslyRemovedVehicle = false;
        if (entity == null && Objects.equals(session.getPlayerEntity().getRemovedPlayerVehicleId(), packet.getId())) {
            entity = session.getPlayerEntity();
            isPreviouslyRemovedVehicle = true;
        }
        if (entity == null) {
            return;
        }

        // Relative coordinates build on where the server has the entity, which an interpolation
        // may not have reached yet
        Vector3f base = entity.serverPosition();
        Vector3d position = packet.getPosition();
        position = position.add(
            packet.getRelatives().contains(PositionElement.X) ? base.getX() : 0,
            packet.getRelatives().contains(PositionElement.Y) ? base.getY() : 0,
            packet.getRelatives().contains(PositionElement.Z) ? base.getZ() : 0
        );

        // As the Java client does (ClientPacketListener#setValuesFromPositionPacket): interpolate a
        // teleport that ends within 64 blocks of where the client has the entity, the base above,
        // and snap a farther one. The player itself always snaps when it gets the teleport of its
        // removed vehicle.
        // Known limit: the client also snaps, and answers, a teleport of a vehicle it steers while
        // that vehicle is outside the chunks it ticks, as after a far teleport into chunks it has
        // not loaded yet. Geyser does not track which chunks the client would tick, so it
        // interpolates such a teleport within 64 blocks and does not answer it.
        boolean snap = isPreviouslyRemovedVehicle || base.toDouble().distanceSquared(position) > 4096;

        // Relative rotation builds on the Java yaw. Geyser keeps the yaw of a boat a quarter turn
        // ahead for Bedrock, and BoatEntity#moveAbsoluteRaw adds that turn again.
        float yaw = entity instanceof BoatEntity ? entity.getYaw() - 90 : entity.getYaw();
        float newPitch = MathUtils.clamp(packet.getXRot() + (packet.getRelatives().contains(PositionElement.X_ROT) ? entity.getPitch() : 0), -90, 90);
        float newYaw = packet.getYRot() + (packet.getRelatives().contains(PositionElement.Y_ROT) ? yaw : 0);

        float lastPitch = entity.getPitch(), lastYaw = yaw;
        if (entity instanceof ClientVehicle clientVehicle) {
            // Unlike its other position updates, the Java client applies a teleport to a vehicle it
            // steers too, and its interpolation adds the vehicle's own movement on top. Geyser's
            // simulation goes on from the target.
            clientVehicle.getVehicleComponent().moveAbsolute(position);
        }
        // The Bedrock client ignores a move without the teleport flag for the vehicle it predicts
        // (see the world border correction in BedrockPlayerAuthInputTranslator), so that vehicle
        // gets every teleport as one. Whether the Java client snaps stays the decision above.
        boolean predictedByBedrock = session.isInClientPredictedVehicle() && session.getPlayerEntity().getVehicle() == entity;
        entity.teleport(position.toFloat(), newYaw, newPitch, packet.isOnGround(), snap || predictedByBedrock);

        // As in PositionMoveRotation#calculateAbsolute, the rotation turns the current motion, and
        // only then are its relative parts added to the motion in the packet
        Vector3f motion = entity.getMotion();
        if (packet.getRelatives().contains(PositionElement.ROTATE_DELTA)) {
            motion = MathUtils.xYRot(motion, (float) Math.toRadians(lastPitch - newPitch), (float) Math.toRadians(lastYaw - newYaw));
        }
        Vector3f deltaMovement = packet.getDeltaMovement().toFloat().add(
            packet.getRelatives().contains(PositionElement.DELTA_X) ? motion.getX() : 0,
            packet.getRelatives().contains(PositionElement.DELTA_Y) ? motion.getY() : 0,
            packet.getRelatives().contains(PositionElement.DELTA_Z) ? motion.getZ() : 0
        );

        entity.setMotion(deltaMovement);
        if (deltaMovement.distanceSquared(Vector3f.ZERO) > 1.0E-8F) {
            SetEntityMotionPacket entityMotionPacket = new SetEntityMotionPacket();
            entityMotionPacket.setRuntimeEntityId(entity.geyserId());
            entityMotionPacket.setMotion(entity.getMotion());
            session.sendUpstreamPacket(entityMotionPacket);
        }

        // The Java client answers a snapped teleport of a vehicle it steers with the new position
        if (snap && isSteeredByPlayer(session, entity)) {
            ServerboundMoveVehiclePacket vehiclePacket = new ServerboundMoveVehiclePacket(position, newYaw, newPitch, entity.isOnGround());
            session.sendDownstreamGamePacket(vehiclePacket);
        }

        if (isPreviouslyRemovedVehicle) {
            ServerboundMovePlayerPosRotPacket positionPacket = new ServerboundMovePlayerPosRotPacket(false, false, position.getX(), position.getY(), position.getZ(), newYaw, newPitch);
            session.sendDownstreamGamePacket(positionPacket);
        }
    }

    /**
     * Whether the player steers the vehicle, itself or through the mob that steers it. The player
     * steers a vehicle that Geyser then simulates or the Bedrock client predicts. A mob with AI is
     * steered by a mob riding it first, unless that one is a non controlling rider. The server
     * checks only the passenger that steers its vehicle itself, so it warns about and resets a
     * vehicle that the player steers through a mob, for the Java client too.
     * <p>
     * MojMap Entity#isLocalInstanceAuthoritative, Mob#getControllingPassenger
     */
    private static boolean isSteeredByPlayer(GeyserSession session, Entity entity) {
        // Passenger packets can describe mobs riding each other in a loop, which the Java client
        // refuses to form (Entity#startRiding) and nobody steers
        Set<Entity> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Entity vehicle = entity;
        while (visited.add(vehicle) && !vehicle.getPassengers().isEmpty()) {
            Entity first = vehicle.getPassengers().getFirst();
            if (first == session.getPlayerEntity()) {
                // ClientVehicle#canSimulateMovement leaves out a vehicle the Bedrock client predicts
                return vehicle instanceof ClientVehicle clientVehicle && (clientVehicle.canSimulateMovement() || session.isInClientPredictedVehicle());
            }
            if (!(vehicle instanceof MobEntity) || vehicle.getFlag(EntityFlag.NO_AI) || !(first instanceof MobEntity)
                || session.javaRegistries().is(NON_CONTROLLING_RIDER, first.getJavaDefinition())) {
                return false;
            }
            vehicle = first;
        }
        return false;
    }
}
