package com.elementalphase.integration.jade;

import com.elementalphase.api.ElementalPhaseApi;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IEntityComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

public enum ElementEntityProvider implements IServerDataProvider<EntityAccessor>, IEntityComponentProvider {
    INSTANCE;

    @Override
    public void appendServerData(CompoundTag data, EntityAccessor accessor) {
        if (!(accessor.getEntity() instanceof LivingEntity entity)) {
            return;
        }
        JadeElementData.write(data, JadeElementData.collect(
                ElementalPhaseApi.getActiveElements(entity),
                ElementalPhaseApi.dataSnapshot().elements()));
    }

    @Override
    public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
        if (!config.get(ElementalPhaseJadePlugin.ELEMENT_INFO)) {
            return;
        }
        for (JadeElementData.Entry entry : JadeElementData.read(accessor.getServerData())) {
            Component amount = Component.literal(JadeElementData.formatAmount(entry.amount()))
                    .withStyle(style -> style.withColor(entry.color()));
            if (entry.icon().filter(ElementEntityProvider::resourceExists).isPresent()) {
                tooltip.add(java.util.List.of(new ElementTextureElement(entry.icon().orElseThrow()),
                        tooltip.getElementHelper().text(amount)));
            } else {
                tooltip.add(Component.translatable("jade.elemental_phase.element",
                        Component.translatable(entry.translationKey()), amount));
            }
        }
    }

    @Override
    public ResourceLocation getUid() {
        return ElementalPhaseJadePlugin.ELEMENT_INFO;
    }

    private static boolean resourceExists(ResourceLocation resource) {
        return Minecraft.getInstance().getResourceManager().getResource(resource).isPresent();
    }
}
