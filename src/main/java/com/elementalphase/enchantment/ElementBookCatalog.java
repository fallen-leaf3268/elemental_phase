package com.elementalphase.enchantment;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;

import java.util.Map;

public final class ElementBookCatalog {
    private static Map<ResourceLocation, String> clientElements = Map.of();
    private static Map<ResourceLocation, Integer> clientColors = Map.of();
    private static Boolean clientEnhancementEnabled;
    private static Double clientBaseAttachmentAmount;

    private ElementBookCatalog() {
    }

    public static Map<ResourceLocation, String> clientElements() {
        return clientElements;
    }

    public static Map<ResourceLocation, Integer> clientColors() {
        return clientColors;
    }

    public static boolean enhancementEnabled() {
        return clientEnhancementEnabled != null ? clientEnhancementEnabled
                : com.elementalphase.config.ElementalPhaseServerConfig.enchantmentEnhancementEnabled();
    }

    public static double baseAttachmentAmount() {
        return clientBaseAttachmentAmount != null ? clientBaseAttachmentAmount
                : com.elementalphase.config.ElementalPhaseServerConfig.enchantmentBaseAmount();
    }

    public static boolean replaceClientSettings(boolean enabled, double amount) {
        boolean changed = clientEnhancementEnabled == null || clientEnhancementEnabled != enabled
                || clientBaseAttachmentAmount == null || Double.compare(clientBaseAttachmentAmount, amount) != 0;
        clientEnhancementEnabled = enabled;
        clientBaseAttachmentAmount = amount;
        return changed;
    }

    public static void clearClientSettings() {
        clientEnhancementEnabled = null;
        clientBaseAttachmentAmount = null;
    }

    public static int color(ResourceLocation element) {
        Integer color = clientColors.get(element);
        if (color != null) return color;
        var definition = com.elementalphase.data.ElementDataManager.snapshot().elements().get(element);
        return definition == null ? net.minecraft.ChatFormatting.GRAY.getColor() : definition.display().color();
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
        return replaceClient(elements, Map.of());
    }

    public static boolean replaceClient(Map<ResourceLocation, String> elements,
                                        Map<ResourceLocation, Integer> colors) {
        Map<ResourceLocation, String> replacement = Map.copyOf(elements);
        Map<ResourceLocation, Integer> colorReplacement = Map.copyOf(colors);
        if (replacement.equals(clientElements) && colorReplacement.equals(clientColors)) return false;
        clientElements = replacement;
        clientColors = colorReplacement;
        return true;
    }
}
