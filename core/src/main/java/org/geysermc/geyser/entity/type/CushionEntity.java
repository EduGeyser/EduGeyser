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

import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.geysermc.geyser.entity.spawn.EntitySpawnContext;
import org.geysermc.geyser.level.block.Blocks;
import org.geysermc.geyser.level.block.type.Block;
import org.geysermc.geyser.network.bedrock.GameProtocol;
import org.geysermc.geyser.util.InteractionResult;
import org.geysermc.geyser.util.InteractiveTag;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.BooleanEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.IntEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;

import java.util.EnumMap;

/**
 * Clients before 26.50 have no cushion. They get an invisible minecart in its place, which they can sit on and
 * attack, plus falling block entities that stack carpets of the cushion's color to its height.
 */
public class CushionEntity extends Entity {
    /**
     * Indexed by Java dye color.
     */
    private static final Block[] CARPETS = {
        Blocks.WHITE_CARPET, Blocks.ORANGE_CARPET, Blocks.MAGENTA_CARPET, Blocks.LIGHT_BLUE_CARPET,
        Blocks.YELLOW_CARPET, Blocks.LIME_CARPET, Blocks.PINK_CARPET, Blocks.GRAY_CARPET,
        Blocks.LIGHT_GRAY_CARPET, Blocks.CYAN_CARPET, Blocks.PURPLE_CARPET, Blocks.BLUE_CARPET,
        Blocks.BROWN_CARPET, Blocks.GREEN_CARPET, Blocks.RED_CARPET, Blocks.BLACK_CARPET
    };

    private static final int CARPET_LAYERS = 4;
    private static final float CARPET_HEIGHT = 0.0625f;
    /**
     * The vanilla cushion model's top is 3.865 pixels up, so the four 1 pixel layers start 0.135 pixels into the ground.
     */
    private static final float STACK_OFFSET = -0.135f / 16;

    private int dyeColor;
    private long[] carpetIds = new long[0];

    public CushionEntity(EntitySpawnContext context) {
        super(context);
        // The default colour is white, or 15 on bedrock, which Java doesn't send
        metadata.put(EntityDataTypes.VARIANT, 15);
    }

    private boolean usesStandIn() {
        return !GameProtocol.is26_50orHigher(session.protocolVersion());
    }

    @Override
    protected void initializeMetadata() {
        super.initializeMetadata();
        // Riders get off on top of the cushion, so the client has to wait for the server's position instead of
        // stepping off to the side first. BDS sends this for the happy ghast, the other vanilla entity that does that.
        setFlag(EntityFlag.DOES_SERVER_AUTH_ONLY_DISMOUNT, true);
        if (usesStandIn()) {
            setFlag(EntityFlag.INVISIBLE, true);
            setFlag(EntityFlag.HAS_GRAVITY, false);
        }
    }

    @Override
    protected void setInvisible(boolean value) {
        super.setInvisible(value || usesStandIn());
    }

    @Override
    public void setGravity(BooleanEntityMetadata entityMetadata) {
        if (!usesStandIn()) {
            super.setGravity(entityMetadata);
        }
    }

    public void setDyeColor(IntEntityMetadata dyeColor) {
        int color = dyeColor.getPrimitiveValue();
        this.dyeColor = color;
        // Every color is offset 15 in bedrock edition
        metadata.put(EntityDataTypes.VARIANT, Math.abs(color - 15));

        for (long carpetId : carpetIds) {
            SetEntityDataPacket packet = new SetEntityDataPacket();
            packet.setRuntimeEntityId(carpetId);
            packet.getMetadata().put(EntityDataTypes.BLOCK, carpetBlock());
            session.sendUpstreamPacket(packet);
        }
    }

    @Override
    public void addAdditionalSpawnData(AddEntityPacket addEntityPacket) {
        if (usesStandIn()) {
            addEntityPacket.setIdentifier("minecraft:minecart");
        }
    }

    @Override
    public void spawnEntity() {
        super.spawnEntity();
        if (!usesStandIn()) {
            return;
        }

        Vector3f base = spawnPosition(position);
        carpetIds = new long[CARPET_LAYERS];
        for (int i = 0; i < CARPET_LAYERS; i++) {
            carpetIds[i] = session.getEntityCache().getNextEntityId().incrementAndGet();
            AddEntityPacket packet = new AddEntityPacket();
            packet.setIdentifier("minecraft:falling_block");
            packet.setRuntimeEntityId(carpetIds[i]);
            packet.setUniqueEntityId(carpetIds[i]);
            packet.setPosition(layerPosition(base, i));
            packet.setMotion(Vector3f.ZERO);
            packet.setRotation(Vector2f.ZERO);
            EnumMap<EntityFlag, Boolean> flags = new EnumMap<>(EntityFlag.class);
            flags.put(EntityFlag.NO_AI, true);
            flags.put(EntityFlag.SILENT, true);
            packet.getMetadata().putFlags(flags);
            packet.getMetadata().put(EntityDataTypes.BLOCK, carpetBlock());
            // No width, so the client targets the minecart instead. The height stays: with none, the carpet
            // was lit from the block below it and rendered dark.
            packet.getMetadata().put(EntityDataTypes.WIDTH, 0f);
            packet.getMetadata().put(EntityDataTypes.HEIGHT, 0.98f);
            session.sendUpstreamPacket(packet);
            // The client can still aim at a carpet instead of the minecart
            session.getEntityCache().addEntityPart(carpetIds[i], this);
        }
    }

    private static Vector3f layerPosition(Vector3f base, int layer) {
        return base.add(0, STACK_OFFSET + layer * CARPET_HEIGHT, 0);
    }

    @Override
    public void despawnEntity() {
        for (long carpetId : carpetIds) {
            RemoveEntityPacket packet = new RemoveEntityPacket();
            packet.setUniqueEntityId(carpetId);
            session.sendUpstreamPacket(packet);
            session.getEntityCache().removeEntityPart(carpetId);
        }
        carpetIds = new long[0];
        super.despawnEntity();
    }

    @Override
    public void moveRelativeRaw(double relX, double relY, double relZ, float yaw, float pitch, float headYaw, boolean isOnGround) {
        super.moveRelativeRaw(relX, relY, relZ, yaw, pitch, headYaw, isOnGround);
        if (relX != 0 || relY != 0 || relZ != 0) {
            moveCarpets();
        }
    }

    @Override
    public void moveAbsoluteRaw(Vector3f position, float yaw, float pitch, float headYaw, boolean isOnGround, boolean teleported) {
        super.moveAbsoluteRaw(position, yaw, pitch, headYaw, isOnGround, teleported);
        moveCarpets();
    }

    private void moveCarpets() {
        for (int i = 0; i < carpetIds.length; i++) {
            MoveEntityAbsolutePacket packet = new MoveEntityAbsolutePacket();
            packet.setRuntimeEntityId(carpetIds[i]);
            packet.setPosition(layerPosition(position, i));
            packet.setRotation(Vector3f.ZERO);
            packet.setTeleported(true);
            session.sendUpstreamPacket(packet);
        }
    }

    private BlockDefinition carpetBlock() {
        return session.getBlockMappings().getBedrockBlock(CARPETS[Math.floorMod(dyeColor, CARPETS.length)].defaultBlockState().javaId());
    }

    @Override
    protected InteractiveTag testInteraction(Hand hand) {
        if (session.isSneaking() || !passengers.isEmpty()) {
            return InteractiveTag.NONE;
        }
        return InteractiveTag.SIT;
    }

    @Override
    public InteractionResult interact(Hand hand) {
        if (session.isSneaking() || !passengers.isEmpty()) {
            return InteractionResult.PASS;
        }
        return InteractionResult.CONSUME;
    }
}
