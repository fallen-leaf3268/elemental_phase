package com.elementalphase.registry;

import com.elementalphase.enchantment.ElementBookCatalog;
import com.elementalphase.enchantment.ElementEnchantmentData;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;

import java.util.Comparator;

public final class ModCreativeTabs {
    private ModCreativeTabs() {
    }

    public static void addElementBooks(BuildCreativeModeTabContentsEvent event) {
        if (!event.getTabKey().equals(CreativeModeTabs.INGREDIENTS)) return;
        var entries = event.getEntries().iterator();
        while (entries.hasNext()) {
            if (ElementEnchantmentData.hasElementEnchantment(entries.next().getKey())) entries.remove();
        }
        ElementBookCatalog.clientElements().keySet().stream()
                .filter(element -> ModEnchantments.forElement(element).isPresent())
                .sorted(Comparator.comparing(net.minecraft.resources.ResourceLocation::toString))
                .forEach(element -> event.accept(ElementEnchantmentData.createBook(element),
                        CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS));
    }
}
