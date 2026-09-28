package com.elementalphase.client;

import com.elementalphase.ElementalPhase;
import com.elementalphase.display.DamagePopupText;
import com.elementalphase.enchantment.ElementBookCatalog;
import com.elementalphase.enchantment.ElementEnchantmentData;
import com.elementalphase.mixin.CreativeModeTabsAccessor;
import com.elementalphase.network.ElementCatalogPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;

@Mod.EventBusSubscriber(modid = ElementalPhase.MOD_ID, value = Dist.CLIENT)
public final class ClientElementBooks {
    private ClientElementBooks() {
    }

    public static void receive(ElementCatalogPacket packet) {
        DamagePopupText.replaceReactionNames(packet.reactionNames());
        if (ElementBookCatalog.replaceClient(packet.elements())) {
            CreativeModeTabsAccessor.setCachedParameters(null);
        }
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        receive(new ElementCatalogPacket(Map.of()));
    }

    @SubscribeEvent
    public static void tooltip(ItemTooltipEvent event) {
        var element = ElementEnchantmentData.readElement(event.getItemStack());
        if (element.isEmpty()) return;
        String translation = ElementBookCatalog.clientElements().get(element.get());
        Component name = translation == null ? Component.literal(element.get().toString())
                : Component.translatable(translation);
        var lines = event.getToolTip();
        if (event.getItemStack().is(Items.ENCHANTED_BOOK) && !event.getItemStack().hasCustomHoverName()
                && !lines.isEmpty()) {
            lines.set(0, Component.translatable("item.elemental_phase.element_book", name)
                    .setStyle(lines.get(0).getStyle()));
        }
        if (translation == null) {
            lines.add(Component.translatable("tooltip.elemental_phase.missing_element")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
        }
    }
}
