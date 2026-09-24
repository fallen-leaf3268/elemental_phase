package com.elementalphase;

import com.elementalphase.registry.ModAttributes;
import com.elementalphase.registry.ModEnchantments;
import com.elementalphase.capability.ElementalCapabilities;
import com.elementalphase.config.ElementalPhaseClientConfig;
import com.elementalphase.network.ModNetwork;
import com.elementalphase.integration.damagenumber.DamageNumberCompat;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;

@Mod(ElementalPhase.MOD_ID)
public final class ElementalPhase {
    public static final String MOD_ID = "elemental_phase";

    @SuppressWarnings("removal")
    public ElementalPhase() {
        ModNetwork.register();
        DamageNumberCompat.initialize();
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ElementalPhaseClientConfig.SPEC);
        var modEventBus = net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();
        ModAttributes.ATTRIBUTES.register(modEventBus);
        ModEnchantments.ENCHANTMENTS.register(modEventBus);
        modEventBus.addListener(ElementalCapabilities::register);
        modEventBus.addListener(ModAttributes::addToLivingEntities);
    }
}
