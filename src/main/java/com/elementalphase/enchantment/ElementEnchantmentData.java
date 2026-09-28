package com.elementalphase.enchantment;

import com.elementalphase.registry.ModEnchantments;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Collection;
import java.util.Optional;

public final class ElementEnchantmentData {
    private static final TagKey<Item> ELEMENT_ENCHANTABLE = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(com.elementalphase.ElementalPhase.MOD_ID, "element_enchantable"));

    private ElementEnchantmentData() {
    }

    public static Optional<ResourceLocation> readElement(ItemStack stack) {
        ResourceLocation result = null;
        ListTag enchantments = enchantments(stack);
        for (int index = 0; index < enchantments.size(); index++) {
            var attachment = attachment(enchantments.getCompound(index));
            if (attachment.isEmpty()) continue;
            ResourceLocation element = attachment.get().element();
            if (result != null && !result.equals(element)) return Optional.empty();
            result = element;
        }
        return Optional.ofNullable(result);
    }

    public static boolean hasElementEnchantment(ItemStack stack) {
        ListTag enchantments = enchantments(stack);
        for (int index = 0; index < enchantments.size(); index++) {
            if (attachment(enchantments.getCompound(index)).isPresent()) return true;
        }
        return false;
    }

    public static ItemStack createBook(ResourceLocation element) {
        return EnchantedBookItem.createForEnchantment(new EnchantmentInstance(
                ModEnchantments.forElement(element).orElseThrow(() ->
                        new IllegalArgumentException("元素尚未登记独立附魔：" + element)), 1));
    }

    public static void setElement(ItemStack stack, ResourceLocation element) {
        Enchantment attachment = ModEnchantments.forElement(element).orElseThrow(() ->
                new IllegalArgumentException("元素尚未登记独立附魔：" + element));
        var enchantments = EnchantmentHelper.getEnchantments(stack);
        enchantments.keySet().removeIf(enchantment -> enchantment instanceof ElementAttachmentEnchantment);
        enchantments.put(attachment, 1);
        if (stack.is(Items.ENCHANTED_BOOK)) stack.removeTagKey("StoredEnchantments");
        EnchantmentHelper.setEnchantments(enchantments, stack);
    }

    public static boolean isAllowed(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        return item instanceof SwordItem || item instanceof AxeItem || item instanceof BowItem
                || item instanceof CrossbowItem || item instanceof TridentItem || stack.is(ELEMENT_ENCHANTABLE);
    }

    public static Optional<ResourceLocation> activeElement(ItemStack stack,
            Collection<ResourceLocation> validElements) {
        return readElement(stack).filter(validElements::contains);
    }

    private static ListTag enchantments(ItemStack stack) {
        if (stack.isEmpty()) return new ListTag();
        return stack.is(Items.ENCHANTED_BOOK)
                ? EnchantedBookItem.getEnchantments(stack) : stack.getEnchantmentTags();
    }

    private static Optional<ElementAttachmentEnchantment> attachment(CompoundTag record) {
        if (EnchantmentHelper.getEnchantmentLevel(record) <= 0) return Optional.empty();
        ResourceLocation id = EnchantmentHelper.getEnchantmentId(record);
        Enchantment enchantment = id == null ? null : ForgeRegistries.ENCHANTMENTS.getValue(id);
        return enchantment instanceof ElementAttachmentEnchantment attachment
                ? Optional.of(attachment) : Optional.empty();
    }
}
