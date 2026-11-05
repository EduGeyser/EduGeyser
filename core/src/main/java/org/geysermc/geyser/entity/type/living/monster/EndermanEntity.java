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

package org.geysermc.geyser.entity.type.living.monster;

import org.cloudburstmc.protocol.bedrock.data.SoundEvent;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.LevelSoundEventPacket;
import org.geysermc.geyser.entity.spawn.EntitySpawnContext;
import org.geysermc.geyser.inventory.GeyserItemStack;
import org.geysermc.geyser.item.Items;
import org.geysermc.geyser.item.type.Item;
import org.geysermc.geyser.level.block.type.BlockState;
import org.geysermc.geyser.translator.item.ItemTranslator;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.BooleanEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.IntEntityMetadata;

public class EndermanEntity extends MonsterEntity {
    /**
     * Since Bedrock 1.21.100 the client draws the block of an enderman's main hand item and no
     * longer reads CARRY_BLOCK_STATE. Java Edition does not render an enderman's own main hand
     * item, so the carried block takes its place. A block without an item is not shown.
     */
    private ItemData carriedBlock = ItemData.AIR;

    public EndermanEntity(EntitySpawnContext context) {
        super(context);
    }

    public void setCarriedBlock(IntEntityMetadata entityMetadata) {
        int javaState = entityMetadata.getPrimitiveValue();
        Item item = BlockState.of(javaState).block().asItem();
        if (item == Items.AIR) {
            carriedBlock = ItemData.AIR;
        } else {
            carriedBlock = ItemTranslator.translateToBedrock(session, GeyserItemStack.of(session, item.javaId(), 1))
                .toBuilder()
                .blockDefinition(session.getBlockMappings().getBedrockBlock(javaState))
                .build();
        }
        updateMainHand();
    }

    @Override
    protected ItemData translateEquipment(GeyserItemStack stack, EquipmentSlot slot) {
        return slot == EquipmentSlot.MAIN_HAND ? carriedBlock : super.translateEquipment(stack, slot);
    }

    /**
     * Controls the screaming sound
     */
    public void setScreaming(BooleanEntityMetadata entityMetadata) {
        //TODO see if Bedrock controls this differently
        // Java Edition this controls which ambient sound is used
        if (entityMetadata.getPrimitiveValue()) {
            LevelSoundEventPacket packet = new LevelSoundEventPacket();
            packet.setSound(SoundEvent.STARE);
            packet.setPosition(bedrockPosition());
            packet.setExtraData(-1);
            packet.setIdentifier("minecraft:enderman");
            session.sendUpstreamPacket(packet);
        }
    }

    public void setAngry(BooleanEntityMetadata entityMetadata) {
        // "Is staring/provoked" - controls visuals
        setFlag(EntityFlag.ANGRY, entityMetadata.getPrimitiveValue());
    }
}
