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
        var name = Component.translatable("enchantment.elemental_phase.element_name",
                ElementBookCatalog.name(element)).withStyle(ChatFormatting.GRAY);
        if (level != 1) name.append(" ").append(Component.translatable("enchantment.level." + level));
        return name;
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
