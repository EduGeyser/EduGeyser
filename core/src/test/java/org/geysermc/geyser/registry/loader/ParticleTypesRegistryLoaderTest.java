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

package org.geysermc.geyser.registry.loader;

import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.LevelEventPacket;
import org.geysermc.geyser.GeyserBootstrap;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.level.particle.ParticleMapping;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ColorParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ParticleType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ParticleTypesRegistryLoaderTest {
    private static Map<ParticleType, ParticleMapping> particles;

    @BeforeAll
    static void loadParticleMappings() {
        GeyserImpl geyser = mock(GeyserImpl.class);
        // The default resource methods read the particle mappings from the classpath
        when(geyser.getBootstrap()).thenReturn(mock(GeyserBootstrap.class, CALLS_REAL_METHODS));
        try (MockedStatic<GeyserImpl> instance = mockStatic(GeyserImpl.class)) {
            instance.when(GeyserImpl::getInstance).thenReturn(geyser);
            particles = new ParticleTypesRegistryLoader().load("mappings/particles.json");
        }
    }

    @Test
    void entityEffectIsSentAsMobSpell() {
        ParticleMapping mapping = particles.get(ParticleType.ENTITY_EFFECT);
        assertNotNull(mapping, "entity_effect has no particle mapping");

        Vector3f position = Vector3f.from(1, 2, 3);
        var packet = assertInstanceOf(LevelEventPacket.class,
            mapping.mapParticle(mock(GeyserSession.class), new ColorParticleData(0xFF0000)).apply(position));

        assertEquals(org.cloudburstmc.protocol.bedrock.data.ParticleType.MOB_SPELL, packet.getType());
        assertEquals(position, packet.getPosition());
        assertEquals(0, packet.getData());
    }

    @Test
    void onlyParticlesWithoutBedrockEquivalentAreUntranslated() {
        Set<ParticleType> untranslated = EnumSet.allOf(ParticleType.class);
        untranslated.removeAll(particles.keySet());

        // Neither had a translation before upstream Geyser #6878 either
        assertEquals(EnumSet.of(ParticleType.BLOCK_MARKER, ParticleType.NOXIOUS_GAS_CLOUD), untranslated);
    }
}
