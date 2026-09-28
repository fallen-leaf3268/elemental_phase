package com.elementalphase.enchantment;

import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.selector.options.EntitySelectorOptions;
import net.minecraft.core.cauldron.CauldronInteraction;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.Registry;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.RegisterEvent;

import java.util.List;

final class ElementTestBootstrap {
    private static boolean initialized;

    private ElementTestBootstrap() {
    }

    static synchronized void initialize() throws ReflectiveOperationException {
        if (initialized) {
            return;
        }
        SharedConstants.tryDetectVersion();
        var bootstrapped = Bootstrap.class.getDeclaredField("isBootstrapped");
        bootstrapped.setAccessible(true);
        if (!bootstrapped.getBoolean(null)) {
            bootstrapped.setBoolean(null, true);
            if (BuiltInRegistries.REGISTRY.keySet().isEmpty()) {
                throw new IllegalStateException("Unable to load registries");
            }
            FireBlock.bootStrap();
            ComposterBlock.bootStrap();
            if (EntityType.getKey(EntityType.PLAYER) == null) {
                throw new IllegalStateException("Failed loading EntityTypes");
            }
            PotionBrewing.bootStrap();
            EntitySelectorOptions.bootStrap();
            DispenseItemBehavior.bootStrap();
            CauldronInteraction.bootStrap();
            BuiltInRegistries.bootStrap();
        }
        var newRegistries = new net.minecraftforge.registries.NewRegistryEvent();
        var createRegistry = net.minecraftforge.registries.DeferredRegister.class.getDeclaredMethod(
                "createRegistry", net.minecraftforge.registries.NewRegistryEvent.class);
        createRegistry.setAccessible(true);
        for (var field : ForgeRegistries.class.getDeclaredFields()) {
            if (!field.getName().startsWith("DEFERRED_")) continue;
            field.setAccessible(true);
            createRegistry.invoke(field.get(null), newRegistries);
        }
        var fill = net.minecraftforge.registries.NewRegistryEvent.class.getDeclaredMethod("fill");
        fill.setAccessible(true);
        fill.invoke(newRegistries);
        var attributes = (ForgeRegistry<net.minecraft.world.entity.ai.attributes.Attribute>) ForgeRegistries.ATTRIBUTES;
        boolean attributesLocked = attributes.isLocked();
        var unfreezeAttributes = BuiltInRegistries.ATTRIBUTE.getClass().getMethod("unfreeze");
        unfreezeAttributes.setAccessible(true);
        unfreezeAttributes.invoke(BuiltInRegistries.ATTRIBUTE);
        attributes.unfreeze();
        try {
            var registration = net.minecraftforge.common.ForgeMod.class.getDeclaredField("ATTRIBUTES");
            registration.setAccessible(true);
            var constructor = RegisterEvent.class.getDeclaredConstructor(ResourceKey.class, ForgeRegistry.class, Registry.class);
            constructor.setAccessible(true);
            var event = constructor.newInstance(ForgeRegistries.Keys.ATTRIBUTES, attributes, BuiltInRegistries.ATTRIBUTE);
            var addEntries = net.minecraftforge.registries.DeferredRegister.class.getDeclaredMethod("addEntries", RegisterEvent.class);
            addEntries.setAccessible(true);
            addEntries.invoke(registration.get(null), event);
        } finally {
            BuiltInRegistries.ATTRIBUTE.freeze();
            if (attributesLocked) attributes.freeze();
        }
        ForgeRegistry<Enchantment> enchantments = (ForgeRegistry<Enchantment>) ForgeRegistries.ENCHANTMENTS;
        boolean locked = enchantments.isLocked();
        var unfreeze = BuiltInRegistries.ENCHANTMENT.getClass().getMethod("unfreeze");
        unfreeze.setAccessible(true);
        unfreeze.invoke(BuiltInRegistries.ENCHANTMENT);
        enchantments.unfreeze();
        try {
            var constructor = RegisterEvent.class.getDeclaredConstructor(ResourceKey.class, ForgeRegistry.class, Registry.class);
            constructor.setAccessible(true);
            RegisterEvent event = constructor.newInstance(ForgeRegistries.Keys.ENCHANTMENTS,
                    enchantments, BuiltInRegistries.ENCHANTMENT);
            com.elementalphase.registry.ModEnchantments.register(event,
                    List.of("elemental_phase:fire", "elemental_phase:water", "elemental_phase:ice",
                            "elemental_phase:lightning", "elemental_phase:wind", "example:steam", "example:deleted")
                            .stream().map(ResourceLocation::parse).toList());
        } finally {
            BuiltInRegistries.ENCHANTMENT.freeze();
            if (locked) enchantments.freeze();
        }
        initialized = true;
    }
}
