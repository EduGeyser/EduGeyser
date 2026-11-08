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
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket;
import org.geysermc.geyser.entity.type.BoatEntity;
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.entity.vehicle.ClientVehicle;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.geyser.translator.protocol.Translator;
import org.geysermc.geyser.util.MathUtils;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;

import java.util.Objects;

@Translator(packet = ClientboundTeleportEntityPacket.class)
public class JavaTeleportEntityTranslator extends PacketTranslator<ClientboundTeleportEntityPacket> {

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
        entity.teleport(position.toFloat(), newYaw, newPitch, packet.isOnGround(), snap);

        Vector3f deltaMovement = packet.getDeltaMovement().toFloat().add(
            packet.getRelatives().contains(PositionElement.DELTA_X) ? entity.getMotion().getX() : 0,
            packet.getRelatives().contains(PositionElement.DELTA_Y) ? entity.getMotion().getY() : 0,
            packet.getRelatives().contains(PositionElement.DELTA_Z) ? entity.getMotion().getZ() : 0
        );
        if (packet.getRelatives().contains(PositionElement.ROTATE_DELTA)) {
            deltaMovement = MathUtils.xYRot(deltaMovement, (float) Math.toRadians(lastPitch - newPitch), (float) Math.toRadians(lastYaw - newYaw));
        }

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
     * Whether the player steers the vehicle, which Geyser then simulates or the Bedrock client
     * predicts. A server that gets a move vehicle packet for a vehicle the player does not steer
     * warns and resets the vehicle.
     * <p>
     * MojMap Entity#isLocalInstanceAuthoritative
     */
    private static boolean isSteeredByPlayer(GeyserSession session, Entity entity) {
        if (!(entity instanceof ClientVehicle clientVehicle)) {
            return false;
        }
        // ClientVehicle#canSimulateMovement leaves out a vehicle the Bedrock client predicts
        return clientVehicle.canSimulateMovement() || session.isInClientPredictedVehicle()
            && !entity.getPassengers().isEmpty() && entity.getPassengers().getFirst() == session.getPlayerEntity();
    }
}
