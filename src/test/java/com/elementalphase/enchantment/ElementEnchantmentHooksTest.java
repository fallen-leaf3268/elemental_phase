package com.elementalphase.enchantment;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ElementEnchantmentHooksTest {
    private static final ResourceLocation FIRE = ResourceLocation.tryParse("elemental_phase:fire");
    private static final ResourceLocation WATER = ResourceLocation.tryParse("elemental_phase:water");
    private static final ResourceLocation DELETED = ResourceLocation.tryParse("example:deleted");
    private static final Set<ResourceLocation> VALID = Set.of(FIRE, WATER);
    private static final String KEY = "elemental_phase:element";

    @BeforeAll
    static void bootstrap() throws ReflectiveOperationException {
        ElementTestBootstrap.initialize();
    }

    @Test
    void anvilRejectsDifferentElementsAndAcceptsSameElement() {
        ItemStack fire = sword(FIRE);
        assertTrue(ElementEnchantmentHooks.blocksAnvil(fire, ElementEnchantmentData.createBook(WATER), VALID::contains));
        assertFalse(ElementEnchantmentHooks.blocksAnvil(fire, ElementEnchantmentData.createBook(FIRE), VALID::contains));
    }

    @Test
    void synchronizedClientCatalogUpdatesAnvilConflictAndStaleBookChecks() {
        var previous = ElementBookCatalog.clientElements();
        try {
            ElementBookCatalog.replaceClient(Map.of(FIRE, "element.fire", WATER, "element.water"));
            ItemStack fire = sword(FIRE);
            assertFalse(ElementEnchantmentHooks.blocksAnvil(fire, ElementEnchantmentData.createBook(FIRE),
                    ElementBookCatalog.clientElements()::containsKey));
            assertTrue(ElementEnchantmentHooks.blocksAnvil(fire, ElementEnchantmentData.createBook(WATER),
                    ElementBookCatalog.clientElements()::containsKey));
            ElementBookCatalog.replaceClient(Map.of(WATER, "element.water"));
            assertTrue(ElementEnchantmentHooks.blocksAnvil(new ItemStack(Items.IRON_SWORD),
                    ElementEnchantmentData.createBook(FIRE), ElementBookCatalog.clientElements()::containsKey));
        } finally {
            ElementBookCatalog.replaceClient(previous);
        }
    }

    @Test
    void staleBooksCannotBeAppliedAndStaleWeaponsCanStillBeRepaired() {
        ItemStack plain = new ItemStack(Items.IRON_SWORD);
        ItemStack stale = sword(DELETED);
        assertTrue(ElementEnchantmentHooks.blocksAnvil(plain, ElementEnchantmentData.createBook(DELETED), VALID::contains));
        assertTrue(ElementEnchantmentHooks.blocksAnvil(stale, ElementEnchantmentData.createBook(FIRE), VALID::contains));
        assertFalse(ElementEnchantmentHooks.blocksAnvil(stale, new ItemStack(Items.IRON_INGOT), VALID::contains));
        ItemStack malformed = ElementEnchantmentData.createBook(FIRE);
        net.minecraft.world.item.EnchantedBookItem.getEnchantments(malformed).add(EnchantmentHelper.storeEnchantment(
                com.elementalphase.registry.ModEnchantments.enchantmentId(WATER), 1));
        assertTrue(ElementEnchantmentHooks.blocksAnvil(plain, malformed, VALID::contains));
    }

    @Test
    void nativeEnchantmentMergePreservesElementIdentityOrdinaryEnchantmentsAndName() {
        ItemStack output = new ItemStack(Items.IRON_SWORD);
        output.enchant(Enchantments.SHARPNESS, 3);
        output.setHoverName(Component.literal("retained"));
        var merged = new HashMap<>(EnchantmentHelper.getEnchantments(output));
        merged.putAll(EnchantmentHelper.getEnchantments(ElementEnchantmentData.createBook(WATER)));
        EnchantmentHelper.setEnchantments(merged, output);
        assertEquals(WATER, ElementEnchantmentData.readElement(output).orElseThrow());
        assertEquals(2, output.getEnchantmentTags().size());
        assertEquals(3, EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SHARPNESS, output));
        assertEquals("retained", output.getHoverName().getString());
        assertFalse(output.getTag().contains(KEY));
    }

    @Test
    void inactiveWeaponRetainsItsIdentityWithoutRandomizing() {
        ItemStack stale = sword(DELETED);
        var original = stale.getTag().copy();
        assertTrue(ElementEnchantmentData.activeElement(stale, VALID).isEmpty());
        assertEquals(DELETED, ElementEnchantmentData.readElement(stale).orElseThrow());
        assertEquals(original, stale.getTag());
    }

    @Test
    void removingNativeEnchantmentsRemovesElementIdentity() {
        ItemStack enchanted = sword(FIRE);
        assertEquals(FIRE, ElementEnchantmentData.readElement(enchanted).orElseThrow());
        EnchantmentHelper.setEnchantments(Map.of(), enchanted);
        enchanted.getOrCreateTag().putString(KEY, FIRE.toString());
        assertTrue(ElementEnchantmentData.readElement(enchanted).isEmpty());
        assertFalse(ElementEnchantmentData.hasElementEnchantment(enchanted));
    }

    @Test
    void ordinaryEnchantmentBookDoesNotTriggerElementAnvilRestriction() {
        ItemStack book = net.minecraft.world.item.EnchantedBookItem.createForEnchantment(
                new net.minecraft.world.item.enchantment.EnchantmentInstance(Enchantments.SHARPNESS, 3));
        assertFalse(ElementEnchantmentData.hasElementEnchantment(book));
        assertTrue(ElementEnchantmentData.readElement(book).isEmpty());
        assertFalse(ElementEnchantmentHooks.blocksAnvil(sword(FIRE), book, VALID::contains));
    }

    private static ItemStack sword(ResourceLocation element) {
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        ElementEnchantmentData.setElement(sword, element);
        return sword;
    }
}
