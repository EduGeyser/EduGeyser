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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.data.ParticleType;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.geysermc.geyser.entity.VanillaEntities;
import org.geysermc.geyser.entity.spawn.EntitySpawnContext;
import org.geysermc.geyser.network.bedrock.GameProtocol;
import org.geysermc.geyser.scoreboard.network.util.GeyserMockContext;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ObjectEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.Particle;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import static org.geysermc.geyser.scoreboard.network.util.GeyserMockContext.mockContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

class AreaEffectCloudEntityTest {

    /**
     * Every Java particle that has a Bedrock particle type for clouds, on the newest Bedrock protocol. All but
     * EGG_CRACK and SONIC_BOOM are the eventType values of the particle mappings before upstream Geyser #6878
     * (mappings b4cec82).
     */
    @ParameterizedTest
    @CsvSource({
        "ANGRY_VILLAGER, VILLAGER_ANGRY",
        "BLOCK, TERRAIN",
        "BLOCK_CRUMBLE, CREAKING_CRUMBLE",
        "BUBBLE, BUBBLE",
        "BUBBLE_COLUMN_UP, BUBBLE_COLUMN_UP",
        "BUBBLE_POP, BUBBLE",
        "CAMPFIRE_COSY_SMOKE, CAMPFIRE_SMOKE",
        "CHERRY_LEAVES, CHERRY_LEAVES",
        "CLOUD, EVAPORATION",
        "COMPOSTER, VILLAGER_HAPPY",
        "COPPER_FIRE_FLAME, GREEN_FLAME",
        "CRIT, CRIT",
        "CURRENT_DOWN, BUBBLE_COLUMN_DOWN",
        "DRAGON_BREATH, DRAGON_BREATH",
        "DRIPPING_DRIPSTONE_LAVA, STALACTITE_DRIP_LAVA",
        "DRIPPING_DRIPSTONE_WATER, STALACTITE_DRIP_WATER",
        "DRIPPING_HONEY, DRIP_HONEY",
        "DRIPPING_LAVA, DRIP_LAVA",
        "DRIPPING_OBSIDIAN_TEAR, OBSIDIAN_TEAR",
        "DRIPPING_WATER, DRIP_WATER",
        "DUST, RED_DUST",
        "DUST_COLOR_TRANSITION, RED_DUST",
        "DUST_PLUME, DUST_PLUME",
        "EGG_CRACK, VILLAGER_HAPPY",
        "ELECTRIC_SPARK, ELECTRIC_SPARK",
        "ENCHANT, ENCHANTING_TABLE",
        "END_ROD, END_ROD",
        "ENTITY_EFFECT, MOB_SPELL",
        "EXPLOSION, HUGE_EXPLOSION",
        "FALLING_DRIPSTONE_LAVA, STALACTITE_DRIP_LAVA",
        "FALLING_DRIPSTONE_WATER, STALACTITE_DRIP_WATER",
        "FALLING_DUST, FALLING_DUST",
        "FALLING_LAVA, DRIP_LAVA",
        "FALLING_OBSIDIAN_TEAR, OBSIDIAN_TEAR",
        "FALLING_SPORE_BLOSSOM, SPORE_BLOSSOM_SHOWER",
        "FISHING, WATER_WAKE",
        "FLAME, FLAME",
        "GLOW_SQUID_INK, INK",
        "HAPPY_VILLAGER, VILLAGER_HAPPY",
        "HEART, HEART",
        "INSTANT_EFFECT, MOB_SPELL_INSTANTANEOUS",
        "ITEM, ICON_CRACK",
        "ITEM_SLIME, SLIME",
        "ITEM_SNOWBALL, SNOWBALL_POOF",
        "LARGE_SMOKE, LARGE_SMOKE",
        "LAVA, LAVA",
        "NOTE, NOTE",
        "ORANGE_POPLAR_LEAVES, ORANGE_POPLAR_LEAVES",
        "PALE_OAK_LEAVES, PALE_OAK_LEAVES",
        "PAUSE_MOB_GROWTH, PAUSE_MOB_GROWTH",
        "POOF, EXPLODE",
        "PORTAL, PORTAL",
        "RAIN, RAIN_SPLASH",
        "RED_POPLAR_LEAVES, RED_POPLAR_LEAVES",
        "RESET_MOB_GROWTH, RESET_MOB_GROWTH",
        "SCULK_SOUL, SCULK_SOUL",
        "SHRIEK, SHRIEK",
        "SMOKE, SMOKE",
        "SNEEZE, SNEEZE",
        "SNOWFLAKE, SNOWFLAKE",
        "SONIC_BOOM, SONIC_EXPLOSION",
        "SOUL, SOUL",
        "SOUL_FIRE_FLAME, BLUE_FLAME",
        "SPIT, SPIT",
        "SPLASH, WATER_SPLASH",
        "SQUID_INK, INK",
        "SULFUR_CUBE_GOO, SULFUR_CUBE",
        "TOTEM_OF_UNDYING, TOTEM",
        "VAULT_CONNECTION, VAULT_CONNECTION",
        "VIBRATION, VIBRATION_SIGNAL",
        "WAX_OFF, WAX",
        "WAX_ON, WAX",
        "WHITE_SMOKE, WHITE_SMOKE",
        "WITCH, WITCH_SPELL",
        "YELLOW_POPLAR_LEAVES, YELLOW_POPLAR_LEAVES"
    })
    void cloudShowsBedrockParticleType(org.geysermc.mcprotocollib.protocol.data.game.level.particle.ParticleType javaType,
                                       ParticleType bedrockType) {
        mockContext(context -> {
            GeyserSession session = session(context, GameProtocol.DEFAULT_BEDROCK_PROTOCOL);
            assertEquals(bedrockType, cloudParticleType(session, javaType));
        });
    }

    @ParameterizedTest
    @EnumSource(value = org.geysermc.mcprotocollib.protocol.data.game.level.particle.ParticleType.class,
        names = {"ORANGE_POPLAR_LEAVES", "RED_POPLAR_LEAVES", "YELLOW_POPLAR_LEAVES"})
    void poplarLeavesBecomeCherryLeavesBefore26_40(org.geysermc.mcprotocollib.protocol.data.game.level.particle.ParticleType javaType) {
        mockContext(context -> {
            GeyserSession session = session(context, Bedrock_v1001.CODEC.getProtocolVersion());
            assertEquals(ParticleType.CHERRY_LEAVES, cloudParticleType(session, javaType));
        });
    }

    /**
     * Writes and reads back the cloud particle type of every Java particle with the codec of every protocol Geyser
     * serves, so no session gets a particle type its codec has no ID for.
     */
    @ParameterizedTest
    @MethodSource("servedCodecs")
    void cloudParticleTypeEncodesForCodec(BedrockCodec codec) {
        BedrockCodecHelper helper = codec.createHelper();
        List<String> failures = new ArrayList<>();
        mockContext(context -> {
            GeyserSession session = session(context, codec.getProtocolVersion());
            for (var javaType : org.geysermc.mcprotocollib.protocol.data.game.level.particle.ParticleType.values()) {
                ParticleType type = cloudParticleType(session, javaType);
                if (type == null) {
                    continue;
                }

                EntityDataMap written = new EntityDataMap();
                written.put(EntityDataTypes.AREA_EFFECT_CLOUD_PARTICLE, type);
                ByteBuf buffer = Unpooled.buffer();
                try {
                    helper.writeEntityData(buffer, written);
                    EntityDataMap read = new EntityDataMap();
                    helper.readEntityData(buffer, read);
                    ParticleType readType = read.get(EntityDataTypes.AREA_EFFECT_CLOUD_PARTICLE);
                    if (readType != type) {
                        failures.add(javaType + " -> " + type + " read back as " + readType);
                    }
                } catch (RuntimeException exception) {
                    failures.add(javaType + " -> " + type + ": " + exception.getMessage());
                } finally {
                    buffer.release();
                }
            }
        });
        assertEquals(List.of(), failures);
    }

    static Stream<Arguments> servedCodecs() {
        return GameProtocol.SUPPORTED_BEDROCK_PROTOCOLS.intStream().boxed().flatMap(protocol -> Stream.of(
            codecArgument(GameProtocol.getBedrockCodec(protocol), "v" + protocol),
            codecArgument(GameProtocol.getEducationCodec(protocol), "v" + protocol + " Education")
        )).filter(Objects::nonNull);
    }

    private static @Nullable Arguments codecArgument(@Nullable BedrockCodec codec, String name) {
        return codec == null ? null : Arguments.of(Named.of(name, codec));
    }

    private static GeyserSession session(GeyserMockContext context, int protocolVersion) {
        GeyserSession session = context.mock(GeyserSession.class);
        when(session.protocolVersion()).thenReturn(protocolVersion);
        return session;
    }

    private static @Nullable ParticleType cloudParticleType(GeyserSession session,
                                                           org.geysermc.mcprotocollib.protocol.data.game.level.particle.ParticleType javaType) {
        var spawnContext = EntitySpawnContext.DUMMY_CONTEXT.apply(session, UUID.randomUUID(), VanillaEntities.AREA_EFFECT_CLOUD)
            .geyserId(1L);
        AreaEffectCloudEntity cloud = new AreaEffectCloudEntity(spawnContext);

        cloud.setParticle(new ObjectEntityMetadata<>(0, MetadataTypes.PARTICLE, new Particle(javaType, null)));

        return cloud.metadata.get(EntityDataTypes.AREA_EFFECT_CLOUD_PARTICLE);
    }
}
