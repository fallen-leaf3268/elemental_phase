package com.elementalphase.enchantment;

import com.elementalphase.data.ElementDataManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.Optional;
import java.util.function.Predicate;

public final class ElementEnchantmentHooks {
    private ElementEnchantmentHooks() {
    }

    public static boolean blocksAnvil(ItemStack left, ItemStack right, Level level) {
        return blocksAnvil(left, right, level.isClientSide
                ? ElementBookCatalog.clientElements()::containsKey
                : ElementDataManager.snapshot().elements()::containsKey);
    }

    static boolean blocksAnvil(ItemStack left, ItemStack right, Predicate<ResourceLocation> validElement) {
        if (!ElementEnchantmentData.hasElementEnchantment(right)) {
            return false;
        }
        Optional<ResourceLocation> rightElement = ElementEnchantmentData.readElement(right).filter(validElement);
        if (rightElement.isEmpty()) {
            return true;
        }
        return ElementEnchantmentData.hasElementEnchantment(left)
                && !ElementEnchantmentData.readElement(left).equals(rightElement);
    }
}
