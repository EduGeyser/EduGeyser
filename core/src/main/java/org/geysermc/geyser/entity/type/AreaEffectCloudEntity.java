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

package org.geysermc.geyser.entity.type;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.data.ParticleType;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.geysermc.geyser.entity.spawn.EntitySpawnContext;
import org.geysermc.geyser.network.bedrock.GameProtocol;
import org.geysermc.geyser.util.MathUtils;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.FloatEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ColorParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.Particle;

public class AreaEffectCloudEntity extends Entity {

    public AreaEffectCloudEntity(EntitySpawnContext context) {
        super(context);
    }

    @Override
    protected void initializeMetadata() {
        super.initializeMetadata();
        // Without this the cloud doesn't appear,
        metadata.put(EntityDataTypes.AREA_EFFECT_CLOUD_DURATION, Integer.MAX_VALUE);

        // This disabled client side shrink of the cloud
        metadata.put(EntityDataTypes.AREA_EFFECT_CLOUD_RADIUS, 3.0f);
        metadata.put(EntityDataTypes.AREA_EFFECT_CLOUD_CHANGE_ON_PICKUP, Float.MIN_VALUE);

        //noinspection deprecation - still needed for these to show up
        metadata.put(EntityDataTypes.AREA_EFFECT_CLOUD_CHANGE_RATE, Float.MIN_VALUE);
        setFlag(EntityFlag.FIRE_IMMUNE, true);
    }

    public void setRadius(FloatEntityMetadata entityMetadata) {
        // Anything less than 0.5 will cause the cloud to despawn
        float value = MathUtils.clamp(entityMetadata.getPrimitiveValue(), 0.5f, 32.0f);
        metadata.put(EntityDataTypes.AREA_EFFECT_CLOUD_RADIUS, value);
        metadata.put(EntityDataTypes.WIDTH, 2.0f * value);
    }

    public void setParticle(EntityMetadata<Particle, ?> entityMetadata) {
        Particle particle = entityMetadata.getValue();
        ParticleType type = bedrockParticleType(particle.getType(), session.protocolVersion());
        if (type != null) {
            metadata.put(EntityDataTypes.AREA_EFFECT_CLOUD_PARTICLE, type);
        }

        if (particle.getData() instanceof ColorParticleData effectParticleData) {
            metadata.put(EntityDataTypes.EFFECT_COLOR, effectParticleData.getColor());
        }
    }

    /**
     * The Bedrock particle type a cloud shows for a Java particle, or null if Bedrock has none. These are listed
     * here because the particle mappings send most particles by an identifier that rarely matches the enum name.
     * All but EGG_CRACK and SONIC_BOOM are the types the particle mappings held before upstream Geyser #6878.
     * Each type must have an ID in the codec of every protocol Geyser serves, or the metadata fails to encode.
     */
    private static @Nullable ParticleType bedrockParticleType(org.geysermc.mcprotocollib.protocol.data.game.level.particle.ParticleType javaType,
                                                             int protocolVersion) {
        return switch (javaType) {
            case ANGRY_VILLAGER -> ParticleType.VILLAGER_ANGRY;
            case BLOCK -> ParticleType.TERRAIN;
            case BLOCK_CRUMBLE -> ParticleType.CREAKING_CRUMBLE;
            case BUBBLE, BUBBLE_POP -> ParticleType.BUBBLE;
            case BUBBLE_COLUMN_UP -> ParticleType.BUBBLE_COLUMN_UP;
            case CAMPFIRE_COSY_SMOKE -> ParticleType.CAMPFIRE_SMOKE;
            case CHERRY_LEAVES -> ParticleType.CHERRY_LEAVES;
            case CLOUD -> ParticleType.EVAPORATION;
            case COMPOSTER, EGG_CRACK, HAPPY_VILLAGER -> ParticleType.VILLAGER_HAPPY;
            case COPPER_FIRE_FLAME -> ParticleType.GREEN_FLAME;
            case CRIT -> ParticleType.CRIT;
            case CURRENT_DOWN -> ParticleType.BUBBLE_COLUMN_DOWN;
            case DRAGON_BREATH -> ParticleType.DRAGON_BREATH;
            case DRIPPING_DRIPSTONE_LAVA, FALLING_DRIPSTONE_LAVA -> ParticleType.STALACTITE_DRIP_LAVA;
            case DRIPPING_DRIPSTONE_WATER, FALLING_DRIPSTONE_WATER -> ParticleType.STALACTITE_DRIP_WATER;
            case DRIPPING_HONEY -> ParticleType.DRIP_HONEY;
            case DRIPPING_LAVA, FALLING_LAVA -> ParticleType.DRIP_LAVA;
            case DRIPPING_OBSIDIAN_TEAR, FALLING_OBSIDIAN_TEAR -> ParticleType.OBSIDIAN_TEAR;
            case DRIPPING_WATER -> ParticleType.DRIP_WATER;
            case DUST, DUST_COLOR_TRANSITION -> ParticleType.RED_DUST;
            case DUST_PLUME -> ParticleType.DUST_PLUME;
            case ELECTRIC_SPARK -> ParticleType.ELECTRIC_SPARK;
            case ENCHANT -> ParticleType.ENCHANTING_TABLE;
            case END_ROD -> ParticleType.END_ROD;
            case ENTITY_EFFECT -> ParticleType.MOB_SPELL;
            case EXPLOSION -> ParticleType.HUGE_EXPLOSION;
            case FALLING_DUST -> ParticleType.FALLING_DUST;
            case FALLING_SPORE_BLOSSOM -> ParticleType.SPORE_BLOSSOM_SHOWER;
            case FISHING -> ParticleType.WATER_WAKE;
            case FLAME -> ParticleType.FLAME;
            case GLOW_SQUID_INK, SQUID_INK -> ParticleType.INK;
            case HEART -> ParticleType.HEART;
            case INSTANT_EFFECT -> ParticleType.MOB_SPELL_INSTANTANEOUS;
            case ITEM -> ParticleType.ICON_CRACK;
            case ITEM_SLIME -> ParticleType.SLIME;
            case ITEM_SNOWBALL -> ParticleType.SNOWBALL_POOF;
            case LARGE_SMOKE -> ParticleType.LARGE_SMOKE;
            case LAVA -> ParticleType.LAVA;
            case NOTE -> ParticleType.NOTE;
            case ORANGE_POPLAR_LEAVES -> poplarLeaves(ParticleType.ORANGE_POPLAR_LEAVES, protocolVersion);
            case PALE_OAK_LEAVES -> ParticleType.PALE_OAK_LEAVES;
            case PAUSE_MOB_GROWTH -> ParticleType.PAUSE_MOB_GROWTH;
            case POOF -> ParticleType.EXPLODE;
            case PORTAL -> ParticleType.PORTAL;
            case RAIN -> ParticleType.RAIN_SPLASH;
            case RED_POPLAR_LEAVES -> poplarLeaves(ParticleType.RED_POPLAR_LEAVES, protocolVersion);
            case RESET_MOB_GROWTH -> ParticleType.RESET_MOB_GROWTH;
            case SCULK_SOUL -> ParticleType.SCULK_SOUL;
            case SHRIEK -> ParticleType.SHRIEK;
            case SMOKE -> ParticleType.SMOKE;
            case SNEEZE -> ParticleType.SNEEZE;
            case SNOWFLAKE -> ParticleType.SNOWFLAKE;
            case SONIC_BOOM -> ParticleType.SONIC_EXPLOSION;
            case SOUL -> ParticleType.SOUL;
            case SOUL_FIRE_FLAME -> ParticleType.BLUE_FLAME;
            case SPIT -> ParticleType.SPIT;
            case SPLASH -> ParticleType.WATER_SPLASH;
            case SULFUR_CUBE_GOO -> ParticleType.SULFUR_CUBE;
            case TOTEM_OF_UNDYING -> ParticleType.TOTEM;
            case VAULT_CONNECTION -> ParticleType.VAULT_CONNECTION;
            case VIBRATION -> ParticleType.VIBRATION_SIGNAL;
            case WAX_OFF, WAX_ON -> ParticleType.WAX;
            case WHITE_SMOKE -> ParticleType.WHITE_SMOKE;
            case WITCH -> ParticleType.WITCH_SPELL;
            case YELLOW_POPLAR_LEAVES -> poplarLeaves(ParticleType.YELLOW_POPLAR_LEAVES, protocolVersion);
            default -> null;
        };
    }

    /**
     * The poplar leaves particle types exist since Bedrock 26.40. Older clients, such as Education 26.3x, get cherry
     * leaves instead: of the two falling leaves types they have, its pink is closer to the autumn colors than the
     * grey of pale oak leaves.
     */
    private static ParticleType poplarLeaves(ParticleType type, int protocolVersion) {
        return GameProtocol.is26_40orHigher(protocolVersion) ? type : ParticleType.CHERRY_LEAVES;
    }
}
