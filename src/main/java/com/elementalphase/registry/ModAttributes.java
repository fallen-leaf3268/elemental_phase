package com.elementalphase.registry;

import com.elementalphase.ElementalPhase;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraftforge.event.entity.EntityAttributeModificationEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModAttributes {
    public static final DeferredRegister<Attribute> ATTRIBUTES =
            DeferredRegister.create(ForgeRegistries.ATTRIBUTES, ElementalPhase.MOD_ID);
    public static final RegistryObject<Attribute> ELEMENT_STRENGTH = ATTRIBUTES.register(
            "element_strength",
            () -> new RangedAttribute("attribute.elemental_phase.element_strength", 1.0D, 0.0D, 1024.0D)
                    .setSyncable(true));

    private ModAttributes() {
    }

    public static void addToLivingEntities(EntityAttributeModificationEvent event) {
        for (var type : event.getTypes()) {
            event.add(type, ELEMENT_STRENGTH.get());
        }
    }
}
