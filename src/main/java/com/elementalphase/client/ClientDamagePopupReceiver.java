package com.elementalphase.client;

import com.elementalphase.network.DamagePopupPacket;
import com.elementalphase.display.DamagePopupText;
import com.elementalphase.config.ElementalPhaseClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class ClientDamagePopupReceiver {
    private ClientDamagePopupReceiver() {
    }

    public static void receive(DamagePopupPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        DamagePopupManager.INSTANCE.updateWorldToken(minecraft.level);
        if (packet != null && minecraft.level != null && packet.shouldDisplay()
                && minecraft.level.getEntity(packet.entityId()) instanceof LivingEntity entity) {
            var entityTypeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            var visibility = DamagePopupVisibilityOptions.resolve(entityTypeId,
                    ElementalPhaseClientConfig.SHOW_DAMAGE.get(), ElementalPhaseClientConfig.SHOW_REACTIONS.get(),
                    ElementalPhaseClientConfig.DAMAGE_BLACKLIST.get(),
                    ElementalPhaseClientConfig.REACTION_BLACKLIST.get());
            DamagePopupText.component(packet.damage(), packet.reactionIds(), visibility.showDamage(),
                            visibility.showReactions())
                    .ifPresent(text -> DamagePopupManager.INSTANCE.add(packet, text, minecraft.font.width(text),
                            minecraft.level.getGameTime(), entity.getBoundingBox(),
                            ElementalPhaseClientConfig.HEIGHT_RATIO.get()));
        }
    }
}
