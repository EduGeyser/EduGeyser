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

package org.geysermc.geyser.entity.type.living.merchant;

import lombok.Getter;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket;
import org.geysermc.geyser.entity.spawn.EntitySpawnContext;
import org.geysermc.geyser.level.block.property.Properties;
import org.geysermc.geyser.level.block.type.BedBlock;
import org.geysermc.geyser.level.block.type.BlockState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Pose;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.VillagerData;

import java.util.Objects;
import java.util.Optional;

public class VillagerEntity extends AbstractMerchantEntity {
    /**
     * A map of Java profession IDs to Bedrock IDs
     */
    private static final int[] VILLAGER_PROFESSIONS = new int[15];
    /**
     * A map of all Java region IDs (plains, savanna...) to Bedrock
     */
    private static final int[] VILLAGER_REGIONS = new int[7];

    static {
        // Java villager profession IDs -> Bedrock
        VILLAGER_PROFESSIONS[0] = 0;
        VILLAGER_PROFESSIONS[1] = 8;
        VILLAGER_PROFESSIONS[2] = 11;
        VILLAGER_PROFESSIONS[3] = 6;
        VILLAGER_PROFESSIONS[4] = 7;
        VILLAGER_PROFESSIONS[5] = 1;
        VILLAGER_PROFESSIONS[6] = 2;
        VILLAGER_PROFESSIONS[7] = 4;
        VILLAGER_PROFESSIONS[8] = 12;
        VILLAGER_PROFESSIONS[9] = 5;
        VILLAGER_PROFESSIONS[10] = 13;
        VILLAGER_PROFESSIONS[11] = 14;
        VILLAGER_PROFESSIONS[12] = 3;
        VILLAGER_PROFESSIONS[13] = 10;
        VILLAGER_PROFESSIONS[14] = 9;

        VILLAGER_REGIONS[0] = 1;
        VILLAGER_REGIONS[1] = 2;
        VILLAGER_REGIONS[2] = 0;
        VILLAGER_REGIONS[3] = 3;
        VILLAGER_REGIONS[4] = 4;
        VILLAGER_REGIONS[5] = 5;
        VILLAGER_REGIONS[6] = 6;
    }

    @Nullable
    private Vector3i bedPosition;
    /**
     * The bed pose the client was last sent, or null while it shows the cached Java position and
     * rotation.
     */
    private @Nullable BedPose sentBedPose;
    /**
     * Used in the interactive tag manager
     */
    @Getter
    private boolean canTradeWith;

    public VillagerEntity(EntitySpawnContext context) {
        super(context);
    }

    public void setVillagerData(EntityMetadata<VillagerData, ?> entityMetadata) {
        VillagerData villagerData = entityMetadata.getValue();
        // Profession
        int profession = getBedrockProfession(villagerData.getProfession());
        canTradeWith = profession != 14 && profession != 0; // Not a notwit and not professionless
        metadata.put(EntityDataTypes.VARIANT, profession);
        //metadata.put(EntityDataTypes.SKIN_ID, villagerData.getType()); Looks like this is modified but for any reason?
        // Region
        metadata.put(EntityDataTypes.MARK_VARIANT, getBedrockRegion(villagerData.getType()));
        // Trade tier - different indexing in Bedrock
        metadata.put(EntityDataTypes.TRADE_TIER, villagerData.getLevel() - 1);
    }

    @Override
    public @Nullable Vector3i setBedPosition(EntityMetadata<Optional<Vector3i>, ?> entityMetadata) {
        bedPosition = super.setBedPosition(entityMetadata);
        updateBedPose();
        return bedPosition;
    }

    @Override
    public void setPose(Pose pose) {
        super.setPose(pose);
        updateBedPose();
    }

    /**
     * Follows the villager into and out of its bed, which takes two metadata fields: the sleeping
     * pose and the bed position.
     */
    private void updateBedPose() {
        boolean inBed = isSleepingInBed();
        if (inBed) {
            // Getting into bed can leave the coordinates where a running lerp has them, so the
            // position check that ends a lerp does not see it
            cancelLerp();
        }
        // The client only learns a pose from a move, and the server need not send one with this
        // metadata. Getting into bed, a new bed, waking up and a vanished bed all change the pose.
        if (!Objects.equals(inBed ? bedPose(position) : null, sentBedPose)) {
            moveAbsoluteRaw(position, getYaw(), getPitch(), getHeadYaw(), isOnGround(), false);
        }
    }

    @Override
    public void moveRelativeRaw(double relX, double relY, double relZ, float yaw, float pitch, float headYaw, boolean isOnGround) {
        if (isSleepingInBed()) {
            // The bed pose is sent as an absolute move
            moveAbsoluteRaw(position.add(relX, relY, relZ), yaw, pitch, headYaw, isOnGround, false);
        } else {
            super.moveRelativeRaw(relX, relY, relZ, yaw, pitch, headYaw, isOnGround);
        }
    }

    /**
     * Sends a sleeping villager in its bed pose, whichever path moved it. The cached position stays
     * the Java one; only the packet is shifted onto the bed and turned to face along it.
     */
    @Override
    public void moveAbsoluteRaw(Vector3f position, float yaw, float pitch, float headYaw, boolean isOnGround, boolean teleported) {
        BedPose pose = isSleepingInBed() ? bedPose(position) : null;
        if (pose == null) {
            // Awake, or the bed is gone before the sleeping metadata cleared: an ordinary move,
            // which sends the whole Java position and rotation
            sentBedPose = null;
            super.moveAbsoluteRaw(position, yaw, pitch, headYaw, isOnGround, teleported);
            return;
        }

        setYaw(yaw);
        setPitch(pitch);
        setHeadYaw(headYaw);
        setOnGround(isOnGround);
        setPosition(position);

        if (isValid()) {
            MoveEntityAbsolutePacket moveEntityPacket = new MoveEntityAbsolutePacket();
            moveEntityPacket.setRuntimeEntityId(geyserId);
            moveEntityPacket.setRotation(pose.rotation());
            moveEntityPacket.setPosition(pose.position());
            moveEntityPacket.setOnGround(isOnGround);
            moveEntityPacket.setTeleported(teleported);
            session.sendUpstreamPacket(moveEntityPacket);
            sentBedPose = pose;
        }
    }

    /**
     * The packet position and rotation of this villager asleep at {@code position}: shifted onto
     * its bed and turned to face along it. Null if the bed block is gone.
     */
    private @Nullable BedPose bedPose(Vector3f position) {
        BlockState bed = session.getGeyser().getWorldManager().blockAt(session, bedPosition);
        if (!(bed.block() instanceof BedBlock)) {
            return null;
        }

        int bedRotation = 0;
        float xOffset = 0;
        float zOffset = 0;
        switch (bed.getValue(Properties.HORIZONTAL_FACING)) {
            case SOUTH -> {
                bedRotation = 180;
                zOffset = -.5f;
            }
            case EAST -> {
                bedRotation = 90;
                xOffset = -.5f;
            }
            case WEST -> {
                bedRotation = 270;
                xOffset = .5f;
            }
            case NORTH -> {
                // rotation does not change because north is 0
                zOffset = .5f;
            }
        }
        return new BedPose(Vector3f.from(position.getX() + xOffset, position.getY() + getOffset(), position.getZ() + zOffset),
            Vector3f.from(0, 0, bedRotation));
    }

    private record BedPose(Vector3f position, Vector3f rotation) {
    }

    /**
     * A sleeping villager lies still, so a server move is applied at once instead of interpolated,
     * which keeps it on the raw paths that send the bed pose. Getting into bed ends a running lerp
     * (see {@link #updateBedPose()}).
     */
    @Override
    public boolean shouldLerp() {
        return !isSleepingInBed() && super.shouldLerp();
    }

    private boolean isSleepingInBed() {
        return getFlag(EntityFlag.SLEEPING) && bedPosition != null;
    }

    public static int getBedrockProfession(int javaProfession) {
        return javaProfession >= 0 && javaProfession < VILLAGER_PROFESSIONS.length ? VILLAGER_PROFESSIONS[javaProfession] : 0;
    }

    public static int getBedrockRegion(int javaRegion) {
        return javaRegion >= 0 && javaRegion < VILLAGER_REGIONS.length ? VILLAGER_REGIONS[javaRegion] : 0;
    }
}
