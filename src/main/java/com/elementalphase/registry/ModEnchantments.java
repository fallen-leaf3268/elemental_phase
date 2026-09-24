package com.elementalphase.registry;

import com.elementalphase.ElementalPhase;
import com.elementalphase.enchantment.ElementAttachmentEnchantment;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.Optional;

public final class ModEnchantments {
    public static final DeferredRegister<Enchantment> ENCHANTMENTS =
            DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, ElementalPhase.MOD_ID);
    public static final RegistryObject<Enchantment> FIRE_ATTACHMENT = register("fire_attachment");
    public static final RegistryObject<Enchantment> WATER_ATTACHMENT = register("water_attachment");
    public static final RegistryObject<Enchantment> ICE_ATTACHMENT = register("ice_attachment");
    public static final RegistryObject<Enchantment> LIGHTNING_ATTACHMENT = register("lightning_attachment");
    public static final RegistryObject<Enchantment> WIND_ATTACHMENT = register("wind_attachment");

    private ModEnchantments() {
    }

    public static Optional<ResourceLocation> elementFor(Enchantment enchantment) {
        if (enchantment == FIRE_ATTACHMENT.get()) {
            return Optional.of(ResourceLocation.fromNamespaceAndPath(ElementalPhase.MOD_ID, "fire"));
        }
        if (enchantment == WATER_ATTACHMENT.get()) {
            return Optional.of(ResourceLocation.fromNamespaceAndPath(ElementalPhase.MOD_ID, "water"));
        }
        if (enchantment == ICE_ATTACHMENT.get()) {
            return Optional.of(ResourceLocation.fromNamespaceAndPath(ElementalPhase.MOD_ID, "ice"));
        }
        if (enchantment == LIGHTNING_ATTACHMENT.get()) {
            return Optional.of(ResourceLocation.fromNamespaceAndPath(ElementalPhase.MOD_ID, "lightning"));
        }
        if (enchantment == WIND_ATTACHMENT.get()) {
            return Optional.of(ResourceLocation.fromNamespaceAndPath(ElementalPhase.MOD_ID, "wind"));
        }
        return Optional.empty();
    }

    private static RegistryObject<Enchantment> register(String id) {
        return ENCHANTMENTS.register(id, ElementAttachmentEnchantment::new);
    }
}
