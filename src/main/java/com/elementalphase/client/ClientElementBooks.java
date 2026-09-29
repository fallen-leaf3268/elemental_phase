package com.elementalphase.client;

import com.elementalphase.ElementalPhase;
import com.elementalphase.display.DamagePopupText;
import com.elementalphase.enchantment.ElementBookCatalog;
import com.elementalphase.enchantment.ElementAttachmentEnchantment;
import com.elementalphase.enchantment.ElementEnchantmentData;
import com.elementalphase.mixin.CreativeModeTabsAccessor;
import com.elementalphase.network.ElementCatalogPacket;
import com.elementalphase.registry.ModEnchantments;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;

@Mod.EventBusSubscriber(modid = ElementalPhase.MOD_ID, value = Dist.CLIENT)
public final class ClientElementBooks {
    private ClientElementBooks() {
    }

    public static void receive(ElementCatalogPacket packet) {
        DamagePopupText.replaceReactionNames(packet.reactionNames());
        boolean changed = ElementBookCatalog.replaceClient(packet.elements(), packet.elementColors());
        changed |= ElementBookCatalog.replaceClientSettings(packet.enhancementEnabled(), packet.baseAttachmentAmount());
        if (changed) {
            CreativeModeTabsAccessor.setCachedParameters(null);
        }
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        receive(new ElementCatalogPacket(Map.of()));
        ElementBookCatalog.clearClientSettings();
        CreativeModeTabsAccessor.setCachedParameters(null);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tooltip(ItemTooltipEvent event) {
        var element = ElementEnchantmentData.readElement(event.getItemStack());
        if (element.isEmpty()) return;
        var enchantment = ModEnchantments.forElement(element.get()).orElse(null);
        if (enchantment == null) return;
        String translation = ElementBookCatalog.clientElements().get(element.get());
        var lines = event.getToolTip();
        for (int index = lines.size() - 1; index > 0; index--) {
            if (isDescription(lines.get(index), enchantment.getDescriptionId())) lines.remove(index);
        }
        if (translation == null) {
            lines.add(Component.translatable("tooltip.elemental_phase.missing_element")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
            return;
        }
        int level = EnchantmentHelper.getEnchantments(event.getItemStack()).getOrDefault(enchantment, 1);
        int nameIndex = lines.indexOf(enchantment.getFullname(level));
        if (nameIndex >= 0) lines.add(nameIndex + 1, enchantment.description());
    }

    private static boolean isDescription(Component component, String enchantmentKey) {
        if (component.getContents() instanceof TranslatableContents translation) {
            String key = translation.getKey();
            if (key.equals(ElementAttachmentEnchantment.DESCRIPTION_KEY)
                    || key.equals(ElementAttachmentEnchantment.ENHANCED_DESCRIPTION_KEY)
                    || key.equals(enchantmentKey + ".desc") || key.equals(enchantmentKey + ".description")) return true;
        }
        return component.getSiblings().stream().anyMatch(child -> isDescription(child, enchantmentKey));
    }
}
