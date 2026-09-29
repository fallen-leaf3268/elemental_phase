package com.elementalphase.integration.jade;

import com.elementalphase.ElementalPhase;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

@WailaPlugin(ElementalPhase.MOD_ID)
public final class ElementalPhaseJadePlugin implements IWailaPlugin {
    public static final ResourceLocation ELEMENT_INFO =
            ResourceLocation.fromNamespaceAndPath(ElementalPhase.MOD_ID, "element_info");

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerEntityDataProvider(ElementEntityProvider.INSTANCE, LivingEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerEntityComponent(ElementEntityProvider.INSTANCE, LivingEntity.class);
    }
}
