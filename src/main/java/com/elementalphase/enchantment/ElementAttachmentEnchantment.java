package com.elementalphase.enchantment;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;

public final class ElementAttachmentEnchantment extends Enchantment {
    private static final EnchantmentCategory CATEGORY = EnchantmentCategory.create("element_attachment",
            ElementAttachmentEnchantment::supports);

    public ElementAttachmentEnchantment() {
        super(Rarity.RARE, CATEGORY, new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND});
    }

    @Override
    public int getMaxLevel() {
        return 1;
    }

    @Override
    public boolean canEnchant(ItemStack stack) {
        return supports(stack.getItem());
    }

    @Override
    public boolean canApplyAtEnchantingTable(ItemStack stack) {
        return supports(stack.getItem());
    }

    @Override
    protected boolean checkCompatibility(Enchantment other) {
        return !(other instanceof ElementAttachmentEnchantment) && super.checkCompatibility(other);
    }

    private static boolean supports(Item item) {
        return item instanceof SwordItem || item instanceof AxeItem || item instanceof BowItem
                || item instanceof CrossbowItem || item instanceof TridentItem;
    }
}
