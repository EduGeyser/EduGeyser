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

        // As the Java client does (ClientPacketListener#setValuesFromPositionPacket): interpolate a
        // teleport that ends within 64 blocks of where the client has the entity, the base above,
        // and snap a farther one. The client also snaps a vehicle it steers outside the chunks it
        // ticks, which the vehicle the player rides never is, and the player itself when it gets
        // the teleport of its removed vehicle.
        boolean snap = isPreviouslyRemovedVehicle || base.toDouble().distanceSquared(position) > 4096;

        float newPitch = MathUtils.clamp(packet.getXRot() + (packet.getRelatives().contains(PositionElement.X_ROT) ? entity.getPitch() : 0), -90, 90);
        float newYaw = packet.getYRot() + (packet.getRelatives().contains(PositionElement.Y_ROT) ? entity.getYaw() : 0);

        float lastPitch = entity.getPitch(), lastYaw = entity.getYaw();
        // Geyser moves this vehicle itself
        boolean ignored = !snap && entity instanceof ClientVehicle clientVehicle && clientVehicle.shouldSimulateMovement();
        if (!ignored) {
            if (entity instanceof ClientVehicle clientVehicle) {
                clientVehicle.getVehicleComponent().moveAbsolute(position);
            }
            entity.teleport(position.toFloat(), newYaw, newPitch, packet.isOnGround(), snap);
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

        if (snap && !entity.getPassengers().isEmpty() && entity.getPassengers().getFirst() == session.getPlayerEntity() && !isPreviouslyRemovedVehicle) {
            ServerboundMoveVehiclePacket vehiclePacket = new ServerboundMoveVehiclePacket(position, newYaw, newPitch, entity.isOnGround());
            session.sendDownstreamGamePacket(vehiclePacket);
        }

        if (isPreviouslyRemovedVehicle) {
            ServerboundMovePlayerPosRotPacket positionPacket = new ServerboundMovePlayerPosRotPacket(false, false, position.getX(), position.getY(), position.getZ(), newYaw, newPitch);
            session.sendDownstreamGamePacket(positionPacket);
        }
    }
}
