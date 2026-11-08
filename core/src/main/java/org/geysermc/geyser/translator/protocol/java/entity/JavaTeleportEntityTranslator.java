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
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.entity.type.LivingEntity;
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
        Vector3f base = entity instanceof LivingEntity livingEntity ? livingEntity.serverPosition() : entity.position();
        Vector3d position = packet.getPosition();
        position = position.add(
            packet.getRelatives().contains(PositionElement.X) ? base.getX() : 0,
            packet.getRelatives().contains(PositionElement.Y) ? base.getY() : 0,
            packet.getRelatives().contains(PositionElement.Z) ? base.getZ() : 0
        );

        boolean hasRelative = packet.getRelatives().contains(PositionElement.X) || packet.getRelatives().contains(PositionElement.Y) || packet.getRelatives().contains(PositionElement.Z);
        boolean interpolate = (entity instanceof LivingEntity || hasRelative) && entity.position().distance(position.toFloat()) < 4096.0;

        float newPitch = MathUtils.clamp(packet.getXRot() + (packet.getRelatives().contains(PositionElement.X_ROT) ? entity.getPitch() : 0), -90, 90);
        float newYaw = packet.getYRot() + (packet.getRelatives().contains(PositionElement.Y_ROT) ? entity.getYaw() : 0);

        float lastPitch = entity.getPitch(), lastYaw = entity.getYaw();
        if (!interpolate) {
            entity.teleport(position.toFloat(), newYaw, newPitch, packet.isOnGround());
        } else if (entity instanceof LivingEntity livingEntity) {
            moveLivingEntity(livingEntity, position, newYaw, newPitch, packet.isOnGround());
        } else {
            final Vector3d currentPosition = entity.position().toDouble();
            entity.moveRelative(position.getX() - currentPosition.getX(), position.getY() - currentPosition.getY(), position.getZ() - currentPosition.getZ(), newYaw, newPitch, newYaw, packet.isOnGround());
        }

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

        if (!interpolate && !entity.getPassengers().isEmpty() && entity.getPassengers().getFirst() == session.getPlayerEntity() && !isPreviouslyRemovedVehicle) {
            ServerboundMoveVehiclePacket vehiclePacket = new ServerboundMoveVehiclePacket(position, newYaw, newPitch, entity.isOnGround());
            session.sendDownstreamGamePacket(vehiclePacket);
        }

        if (isPreviouslyRemovedVehicle) {
            ServerboundMovePlayerPosRotPacket positionPacket = new ServerboundMovePlayerPosRotPacket(false, false, position.getX(), position.getY(), position.getZ(), newYaw, newPitch);
            session.sendDownstreamGamePacket(positionPacket);
        }
    }

    /**
     * Moves a living entity to the teleport target, interpolated where
     * {@link LivingEntity#moveAbsolute} chooses to. The target goes in as is: as a delta from the
     * shown position it would be added to the target of a running interpolation and overshoot by
     * the part not interpolated yet.
     */
    private static void moveLivingEntity(LivingEntity entity, Vector3d position, float yaw, float pitch, boolean onGround) {
        if (entity instanceof ClientVehicle clientVehicle) {
            // Geyser moves this vehicle itself
            if (clientVehicle.shouldSimulateMovement()) {
                return;
            }
            clientVehicle.getVehicleComponent().moveAbsolute(position);
        }
        // As in Entity#teleport, the head yaw follows the yaw
        entity.moveAbsolute(position.toFloat(), yaw, pitch, yaw, onGround, false);
    }
}
