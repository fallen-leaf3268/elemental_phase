package com.elementalphase.enchantment;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;

import java.util.Map;

public final class ElementBookCatalog {
    private static Map<ResourceLocation, String> clientElements = Map.of();

    private ElementBookCatalog() {
    }

    public static Map<ResourceLocation, String> clientElements() {
        return clientElements;
    }

    public static Component name(ResourceLocation element) {
        String translation = clientElements.get(element);
        if (translation == null) {
            var definition = com.elementalphase.data.ElementDataManager.snapshot().elements().get(element);
            translation = definition == null
                    ? "element." + element.getNamespace() + "." + element.getPath().replace('/', '.')
                    : definition.display().translationKey();
        }
        return Component.translatable(translation);
    }

    public static boolean replaceClient(Map<ResourceLocation, String> elements) {
        Map<ResourceLocation, String> replacement = Map.copyOf(elements);
        if (replacement.equals(clientElements)) return false;
        clientElements = replacement;
        return true;
    }
}
