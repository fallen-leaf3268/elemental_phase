package com.elementalphase.enchantment;

import com.elementalphase.data.ElementDataManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;

public final class ElementAttachmentEnchantment extends Enchantment {
    public static final String DESCRIPTION_KEY = "enchantment.elemental_phase.element_description";
    public static final String ENHANCED_DESCRIPTION_KEY = "enchantment.elemental_phase.element_enhanced_description";
    private final ResourceLocation element;

    public ElementAttachmentEnchantment(ResourceLocation element) {
        super(Rarity.RARE, EnchantmentCategory.WEAPON,
                new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND});
        this.element = java.util.Objects.requireNonNull(element, "element");
    }

    public ResourceLocation element() {
        return element;
    }

    @Override
    public Component getFullname(int level) {
        var name = Component.translatable(ElementBookCatalog.enhancementEnabled()
                        ? "enchantment.elemental_phase.element_enhanced_name"
                        : "enchantment.elemental_phase.element_name",
                ElementBookCatalog.name(element)).withStyle(style -> style.withColor(ElementBookCatalog.color(element)));
        if (level != 1) name.append(" ").append(Component.translatable("enchantment.level." + level));
        return name;
    }

    public Component description() {
        String amount = java.math.BigDecimal.valueOf(ElementBookCatalog.baseAttachmentAmount())
                .stripTrailingZeros().toPlainString();
        Component elementName = ElementBookCatalog.name(element).copy()
                .withStyle(style -> style.withColor(ElementBookCatalog.color(element)));
        if (ElementBookCatalog.enhancementEnabled()) {
            return Component.translatable(ENHANCED_DESCRIPTION_KEY, amount, elementName,
                    elementName.copy()).withStyle(ChatFormatting.GRAY);
        }
        return Component.translatable(DESCRIPTION_KEY, amount, elementName).withStyle(ChatFormatting.GRAY);
    }

    @Override
    public boolean isDiscoverable() {
        return ElementDataManager.snapshot().elements().containsKey(element);
    }

    @Override
    public boolean isTradeable() {
        return isDiscoverable();
    }

    @Override
    public int getMaxLevel() {
        return 1;
    }

    @Override
    public boolean canEnchant(ItemStack stack) {
        return ElementEnchantmentData.isAllowed(stack);
    }

    @Override
    public boolean canApplyAtEnchantingTable(ItemStack stack) {
        return ElementEnchantmentData.isAllowed(stack);
    }

    @Override
    protected boolean checkCompatibility(Enchantment other) {
        return !(other instanceof ElementAttachmentEnchantment) && super.checkCompatibility(other);
    }
}
