package com.elementalphase.enchantment;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.elementalphase.config.ElementalPhaseServerConfig;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackLinkedSet;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.common.util.MutableHashedLinkedMap;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ElementEnchantmentDataTest {
    private static final String ELEMENT_KEY = "elemental_phase:element";
    private static final ResourceLocation ENCHANTMENT = id("elemental_phase:attachment/elemental_phase/fire");
    private static final ResourceLocation FIRE = id("elemental_phase:fire");
    private static final ResourceLocation CUSTOM = id("example:steam");
    private static Item customBow;

    @BeforeAll
    static void bootstrap() throws ReflectiveOperationException {
        ElementTestBootstrap.initialize();
        ForgeRegistry<Item> registry = (ForgeRegistry<Item>) ForgeRegistries.ITEMS;
        boolean locked = registry.isLocked();
        var unfreeze = BuiltInRegistries.ITEM.getClass().getMethod("unfreeze");
        unfreeze.setAccessible(true);
        unfreeze.invoke(BuiltInRegistries.ITEM);
        registry.unfreeze();
        try {
            customBow = new BowItem(new Item.Properties()) { };
            registry.register(id("elemental_phase_test:custom_bow"), customBow);
        } finally {
            BuiltInRegistries.ITEM.freeze();
            if (locked) {
                registry.freeze();
            }
        }
    }

    @Test
    void createsBuiltInAndCustomBooksWithIndependentStoredEnchantments() {
        for (ResourceLocation element : List.of(FIRE, CUSTOM)) {
            ItemStack book = ElementEnchantmentData.createBook(element);
            assertTrue(book.is(Items.ENCHANTED_BOOK));
            assertEquals(Optional.of(element), ElementEnchantmentData.readElement(book));
            assertTrue(ElementEnchantmentData.hasElementEnchantment(book));
            ListTag enchantments = book.getTag().getList("StoredEnchantments", Tag.TAG_COMPOUND);
            assertEquals(1, enchantments.size());
            assertEquals(id("elemental_phase:attachment/" + element.getNamespace() + "/" + element.getPath()),
                    EnchantmentHelper.getEnchantmentId(enchantments.getCompound(0)));
            assertEquals(1, EnchantmentHelper.getEnchantmentLevel(enchantments.getCompound(0)));
            assertTrue(com.elementalphase.registry.ModEnchantments.forElement(element).isPresent());
            assertFalse(book.getTag().contains(ELEMENT_KEY));
        }
        assertFalse(ForgeRegistries.ENCHANTMENTS.containsKey(id("elemental_phase:element_attachment")));
    }

    @Test
    void identityComesFromRegisteredEnchantmentRatherThanElementNbt() {
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        sword.getOrCreateTag().putString(ELEMENT_KEY, FIRE.toString());
        assertFalse(ElementEnchantmentData.hasElementEnchantment(sword));
        assertTrue(ElementEnchantmentData.readElement(sword).isEmpty());
        mark(sword, 1);
        assertEquals(Optional.of(FIRE), ElementEnchantmentData.readElement(sword));
        for (String stale : List.of("", "example:steam", "Invalid:ID", "example:", ":fire")) {
            sword.getOrCreateTag().putString(ELEMENT_KEY, stale);
            assertEquals(Optional.of(FIRE), ElementEnchantmentData.readElement(sword));
        }
        sword.getOrCreateTag().putInt(ELEMENT_KEY, 4);
        assertEquals(Optional.of(FIRE), ElementEnchantmentData.readElement(sword));
    }

    @Test
    void ignoresWrongContainerAndZeroLevel() {
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        ListTag misplaced = new ListTag();
        misplaced.add(EnchantmentHelper.storeEnchantment(ENCHANTMENT, 1));
        sword.getOrCreateTag().put("StoredEnchantments", misplaced);
        assertFalse(ElementEnchantmentData.hasElementEnchantment(sword));
        mark(sword, 0);
        assertFalse(ElementEnchantmentData.hasElementEnchantment(sword));
        mark(sword, 1);
        sword.getOrCreateTag().remove("Enchantments");
        assertTrue(ElementEnchantmentData.readElement(sword).isEmpty());
    }

    @Test
    void activeElementNeverChangesEnchantmentsOrAssignsAnotherElement() {
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        var originalTag = sword.getTag() == null ? null : sword.getTag().copy();
        assertTrue(ElementEnchantmentData.activeElement(sword, List.of(FIRE)).isEmpty());
        assertEquals(originalTag, sword.getTag());
        mark(sword, 1);
        var enchantedTag = sword.getTag().copy();
        assertTrue(ElementEnchantmentData.activeElement(sword, List.of()).isEmpty());
        assertTrue(ElementEnchantmentData.activeElement(sword, List.of(CUSTOM)).isEmpty());
        assertEquals(Optional.of(FIRE), ElementEnchantmentData.activeElement(sword, List.of(FIRE, CUSTOM)));
        assertEquals(enchantedTag, sword.getTag());
        assertFalse(sword.getTag().contains(ELEMENT_KEY));
    }

    @Test
    void multipleElementEnchantmentsAreInvalidAndCannotBeReassigned() {
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        mark(sword, 1);
        sword.getEnchantmentTags().add(EnchantmentHelper.storeEnchantment(
                com.elementalphase.registry.ModEnchantments.enchantmentId(CUSTOM), 1));
        var original = sword.getTag().copy();
        assertTrue(ElementEnchantmentData.hasElementEnchantment(sword));
        assertTrue(ElementEnchantmentData.readElement(sword).isEmpty());
        assertTrue(ElementEnchantmentData.activeElement(sword, List.of(FIRE, CUSTOM)).isEmpty());
        assertEquals(original, sword.getTag());
    }

    @Test
    void permitsDefaultWeaponsAndSubclassButRejectsToolsAndArmor() {
        for (Item item : List.of(Items.IRON_SWORD, Items.IRON_AXE, Items.BOW, Items.CROSSBOW, Items.TRIDENT,
                customBow)) {
            assertTrue(ElementEnchantmentData.isAllowed(new ItemStack(item)), item.toString());
        }
        for (Item item : List.of(Items.IRON_PICKAXE, Items.IRON_SHOVEL, Items.IRON_HELMET, Items.STICK)) {
            assertFalse(ElementEnchantmentData.isAllowed(new ItemStack(item)), item.toString());
        }
        assertFalse(ElementEnchantmentData.isAllowed(ItemStack.EMPTY));
    }

    @Test
    void customItemTagPermitsEnchantingTableAndAnvilTogether() {
        TagKey<Item> tag = TagKey.create(Registries.ITEM, id("elemental_phase:element_enchantable"));
        var holder = Items.STICK.builtInRegistryHolder();
        var originalTags = holder.tags().toList();
        try {
            holder.bindTags(List.of(tag));
            ItemStack stick = new ItemStack(Items.STICK);
            ElementAttachmentEnchantment enchantment = new ElementAttachmentEnchantment(FIRE);
            assertTrue(ElementEnchantmentData.isAllowed(stick));
            assertTrue(enchantment.canEnchant(stick));
            assertTrue(enchantment.canApplyAtEnchantingTable(stick));
            assertTrue(enchantment.isCompatibleWith(Enchantments.SHARPNESS));
            assertFalse(enchantment.isCompatibleWith(new ElementAttachmentEnchantment(CUSTOM)));
        } finally {
            holder.bindTags(originalTags);
        }
    }

    @Test
    void writingElementPreservesOtherEnchantmentsAndDisplayData() {
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        mark(sword, 1);
        sword.getEnchantmentTags().add(EnchantmentHelper.storeEnchantment(id("minecraft:sharpness"), 3));
        sword.getOrCreateTag().putString("custom", "retained");
        ElementEnchantmentData.setElement(sword, CUSTOM);
        assertEquals(2, sword.getEnchantmentTags().size());
        assertEquals("retained", sword.getTag().getString("custom"));
        assertEquals(Optional.of(CUSTOM), ElementEnchantmentData.readElement(sword));
        assertFalse(sword.getTag().contains(ELEMENT_KEY));
    }

    @Test
    void changingBookElementReplacesPreviousStoredEnchantmentAndPreservesOrdinaryOne() {
        ItemStack book = ElementEnchantmentData.createBook(FIRE);
        net.minecraft.world.item.EnchantedBookItem.addEnchantment(book,
                new net.minecraft.world.item.enchantment.EnchantmentInstance(Enchantments.SHARPNESS, 3));
        book.setHoverName(Component.literal("retained"));
        ElementEnchantmentData.setElement(book, CUSTOM);
        assertEquals(Optional.of(CUSTOM), ElementEnchantmentData.readElement(book));
        assertEquals(2, net.minecraft.world.item.EnchantedBookItem.getEnchantments(book).size());
        assertEquals(3, EnchantmentHelper.getEnchantments(book).get(Enchantments.SHARPNESS));
        assertEquals("retained", book.getHoverName().getString());
    }

    @Test
    void acquisitionUsesActiveServerDataWhileNamesUseSynchronizedElementTranslations() {
        var data = com.elementalphase.data.ElementDataManager.baseSnapshot();
        var catalog = ElementBookCatalog.clientElements();
        var colors = ElementBookCatalog.clientColors();
        var damageRegistry = new net.minecraft.core.MappedRegistry<net.minecraft.world.damagesource.DamageType>(
                Registries.DAMAGE_TYPE, com.mojang.serialization.Lifecycle.stable());
        var registries = new RegistryAccess.ImmutableRegistryAccess(List.of(damageRegistry));
        try {
            var snapshot = new com.elementalphase.data.ElementDataParser().parseLenient(Map.of(
                    id("elemental_phase:elemental_phase/elements/fire.json"),
                    com.google.gson.JsonParser.parseString("{}"))).snapshot();
            com.elementalphase.data.ElementDataManager.replace(snapshot, registries);
            ElementBookCatalog.replaceClient(Map.of(FIRE, "custom.name.fire", CUSTOM, "custom.name.steam"),
                    Map.of(FIRE, 0xFF5500, CUSTOM, 0x7EE7C4));
            var fire = com.elementalphase.registry.ModEnchantments.forElement(FIRE).orElseThrow();
            var custom = com.elementalphase.registry.ModEnchantments.forElement(CUSTOM).orElseThrow();
            assertTrue(fire.isDiscoverable());
            assertTrue(fire.isTradeable());
            assertFalse(custom.isDiscoverable());
            assertFalse(custom.isTradeable());
            var title = (net.minecraft.network.chat.contents.TranslatableContents) custom.getFullname(1).getContents();
            assertEquals("enchantment.elemental_phase.element_name", title.getKey());
            var elementName = (Component) title.getArgs()[0];
            assertEquals("custom.name.steam",
                    ((net.minecraft.network.chat.contents.TranslatableContents) elementName.getContents()).getKey());
            assertEquals(0x7EE7C4, custom.getFullname(1).getStyle().getColor().getValue());
            ElementBookCatalog.replaceClient(catalog);
            assertEquals(net.minecraft.ChatFormatting.GRAY.getColor(),
                    custom.getFullname(1).getStyle().getColor().getValue());
            com.elementalphase.data.ElementDataManager.replace(com.elementalphase.data.ElementDataSnapshot.empty(), registries);
            assertFalse(fire.isDiscoverable());
            assertFalse(fire.isTradeable());
        } finally {
            ElementBookCatalog.replaceClient(catalog, colors);
            com.elementalphase.data.ElementDataManager.replace(data, registries);
        }
    }

    @Test
    void unregisteredElementCannotGenerateBookOrAppearInCreativeCatalog() {
        ResourceLocation missing = id("example:undeclared");
        assertThrows(IllegalArgumentException.class, () -> ElementEnchantmentData.createBook(missing));
        var original = ElementBookCatalog.clientElements();
        try {
            ElementBookCatalog.replaceClient(Map.of(FIRE, "fire", missing, "missing"));
            var event = creativeEvent(CreativeModeTabs.INGREDIENTS);
            event.accept(ElementEnchantmentData.createBook(CUSTOM));
            com.elementalphase.registry.ModCreativeTabs.addElementBooks(event);
            int count = 0;
            for (var entry : event.getEntries()) count++;
            assertEquals(1, count);
            assertTrue(event.getEntries().contains(ElementEnchantmentData.createBook(FIRE)));
            assertFalse(event.getEntries().contains(ElementEnchantmentData.createBook(CUSTOM)));
        } finally {
            ElementBookCatalog.replaceClient(original);
        }
    }

    @Test
    void elementNamesUseConfiguredRgbOnBooksAndWeapons() {
        var data = com.elementalphase.data.ElementDataManager.baseSnapshot();
        var catalog = ElementBookCatalog.clientElements();
        var damageRegistry = new net.minecraft.core.MappedRegistry<net.minecraft.world.damagesource.DamageType>(
                Registries.DAMAGE_TYPE, com.mojang.serialization.Lifecycle.stable());
        var registries = new RegistryAccess.ImmutableRegistryAccess(List.of(damageRegistry));
        try {
            var snapshot = new com.elementalphase.data.ElementDataParser().parseLenient(Map.of(
                    id("example:elemental_phase/elements/steam.json"),
                    com.google.gson.JsonParser.parseString("{\"display\":{\"color\":\"#7EE7C4\"}}"))).snapshot();
            com.elementalphase.data.ElementDataManager.replace(snapshot, registries);
            ElementBookCatalog.replaceClient(Map.of());
            ItemStack book = ElementEnchantmentData.createBook(CUSTOM);
            ItemStack weapon = new ItemStack(Items.DIAMOND_SWORD);
            ElementEnchantmentData.setElement(weapon, CUSTOM);
            for (ItemStack stack : List.of(book, weapon)) {
                var enchantment = EnchantmentHelper.getEnchantments(stack).keySet().iterator().next();
                assertEquals(0x7EE7C4, enchantment.getFullname(1).getStyle().getColor().getValue());
                assertEquals(0x7EE7C4, enchantment.getFullname(3).getStyle().getColor().getValue());
            }
            assertEquals(net.minecraft.ChatFormatting.GRAY.getColor(),
                    Enchantments.SHARPNESS.getFullname(1).getStyle().getColor().getValue());
        } finally {
            ElementBookCatalog.replaceClient(catalog);
            com.elementalphase.data.ElementDataManager.replace(data, registries);
        }
    }

    @Test
    void defaultElementColorIsWhiteWhileMissingElementsStayGray() {
        var data = com.elementalphase.data.ElementDataManager.baseSnapshot();
        var catalog = ElementBookCatalog.clientElements();
        var damageRegistry = new net.minecraft.core.MappedRegistry<net.minecraft.world.damagesource.DamageType>(
                Registries.DAMAGE_TYPE, com.mojang.serialization.Lifecycle.stable());
        var registries = new RegistryAccess.ImmutableRegistryAccess(List.of(damageRegistry));
        try {
            var snapshot = new com.elementalphase.data.ElementDataParser().parseLenient(Map.of(
                    id("elemental_phase:elemental_phase/elements/fire.json"),
                    com.google.gson.JsonParser.parseString("{}"))).snapshot();
            com.elementalphase.data.ElementDataManager.replace(snapshot, registries);
            ElementBookCatalog.replaceClient(Map.of());
            var fire = com.elementalphase.registry.ModEnchantments.forElement(FIRE).orElseThrow();
            var missing = com.elementalphase.registry.ModEnchantments.forElement(CUSTOM).orElseThrow();
            assertEquals(0xFFFFFF, fire.getFullname(1).getStyle().getColor().getValue());
            assertEquals(net.minecraft.ChatFormatting.GRAY.getColor(),
                    missing.getFullname(1).getStyle().getColor().getValue());
        } finally {
            ElementBookCatalog.replaceClient(catalog);
            com.elementalphase.data.ElementDataManager.replace(data, registries);
        }
    }

    @Test
    void vanillaEnchantedBookCategoryIncludesEveryLoadedElementAndSearchEntry() {
        var original = ElementBookCatalog.clientElements();
        try {
            ElementBookCatalog.replaceClient(Map.of(FIRE, "fire", CUSTOM, "steam",
                    id("elemental_phase:water"), "water"));
            var event = creativeEvent(CreativeModeTabs.INGREDIENTS);
            ItemStack ordinaryBook = new ItemStack(Items.ENCHANTED_BOOK);
            event.accept(ordinaryBook);
            com.elementalphase.registry.ModCreativeTabs.addElementBooks(event);
            var elements = new ArrayList<ResourceLocation>();
            for (var entry : event.getEntries()) {
                var element = ElementEnchantmentData.readElement(entry.getKey());
                if (element.isPresent()) {
                    elements.add(element.get());
                    assertEquals(CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS, entry.getValue());
                }
            }
            assertEquals(List.of(FIRE, id("elemental_phase:water"), CUSTOM), elements);
            assertTrue(event.getEntries().contains(ordinaryBook));
        } finally {
            ElementBookCatalog.replaceClient(original);
        }
    }

    @Test
    void tooltipKeepsVanillaBookTitleAndColoredElementName() {
        var catalog = ElementBookCatalog.clientElements();
        var colors = ElementBookCatalog.clientColors();
        try {
            ElementBookCatalog.replaceClient(Map.of(FIRE, "element.elemental_phase.fire"),
                    Map.of(FIRE, 0xFF7A00));
            ItemStack book = ElementEnchantmentData.createBook(FIRE);
            Component title = book.getHoverName().copy().withStyle(net.minecraft.ChatFormatting.YELLOW);
            Component enchantmentName = com.elementalphase.registry.ModEnchantments.forElement(FIRE)
                    .orElseThrow().getFullname(1);
            var lines = new ArrayList<>(List.of(title, enchantmentName));
            var event = new net.minecraftforge.event.entity.player.ItemTooltipEvent(book, null, lines,
                    net.minecraft.world.item.TooltipFlag.Default.NORMAL);
            com.elementalphase.client.ClientElementBooks.tooltip(event);
            assertSame(title, lines.get(0));
            assertEquals("item.minecraft.enchanted_book",
                    ((net.minecraft.network.chat.contents.TranslatableContents) lines.get(0).getContents()).getKey());
            assertSame(enchantmentName, lines.get(1));
            assertEquals(0xFF7A00, lines.get(1).getStyle().getColor().getValue());
            assertEquals(3, lines.size());
            assertEquals("1", ((net.minecraft.network.chat.contents.TranslatableContents)
                    lines.get(2).getContents()).getArgs()[0]);
            ElementBookCatalog.replaceClient(Map.of());
            com.elementalphase.client.ClientElementBooks.tooltip(event);
            assertSame(title, lines.get(0));
            assertEquals("tooltip.elemental_phase.missing_element",
                    ((net.minecraft.network.chat.contents.TranslatableContents) lines.get(2).getContents()).getKey());
        } finally {
            ElementBookCatalog.replaceClient(catalog, colors);
        }
    }

    @Test
    void creativeBookCategoryUsesCurrentCatalogAfterElementReload() {
        var original = ElementBookCatalog.clientElements();
        try {
            ElementBookCatalog.replaceClient(Map.of(FIRE, "fire"));
            var first = creativeEvent(CreativeModeTabs.INGREDIENTS);
            com.elementalphase.registry.ModCreativeTabs.addElementBooks(first);
            assertTrue(first.getEntries().contains(ElementEnchantmentData.createBook(FIRE)));
            ElementBookCatalog.replaceClient(Map.of(CUSTOM, "steam"));
            var reloaded = creativeEvent(CreativeModeTabs.INGREDIENTS);
            com.elementalphase.registry.ModCreativeTabs.addElementBooks(reloaded);
            assertTrue(reloaded.getEntries().contains(ElementEnchantmentData.createBook(CUSTOM)));
            assertFalse(reloaded.getEntries().contains(ElementEnchantmentData.createBook(FIRE)));
        } finally {
            ElementBookCatalog.replaceClient(original);
        }
    }

    @Test
    void descriptionUsesConfiguredAmountAndElementColorOnBooksAndWeapons() throws ReflectiveOperationException {
        var catalog = ElementBookCatalog.clientElements();
        var colors = ElementBookCatalog.clientColors();
        var originalConfig = configureEnchantmentAmount(2.5D);
        try {
            ElementBookCatalog.replaceClient(Map.of(CUSTOM, "custom.name.steam"), Map.of(CUSTOM, 0x7EE7C4));
            ItemStack book = ElementEnchantmentData.createBook(CUSTOM);
            ItemStack weapon = new ItemStack(Items.DIAMOND_SWORD);
            ElementEnchantmentData.setElement(weapon, CUSTOM);
            for (ItemStack stack : List.of(book, weapon)) {
                Component title = stack.getHoverName();
                Component name = com.elementalphase.registry.ModEnchantments.forElement(CUSTOM)
                        .orElseThrow().getFullname(1);
                var lines = new ArrayList<>(List.of(title, name));
                var event = new net.minecraftforge.event.entity.player.ItemTooltipEvent(stack, null, lines,
                        net.minecraft.world.item.TooltipFlag.Default.NORMAL);
                com.elementalphase.client.ClientElementBooks.tooltip(event);
                assertEquals(3, lines.size());
                assertSame(title, lines.get(0));
                var description = assertInstanceOf(net.minecraft.network.chat.contents.TranslatableContents.class,
                        lines.get(2).getContents());
                assertEquals("enchantment.elemental_phase.element_description", description.getKey());
                assertEquals("2.5", description.getArgs()[0]);
                assertEquals(net.minecraft.ChatFormatting.GRAY.getColor(),
                        lines.get(2).getStyle().getColor().getValue());
                Component elementLabel = (Component) description.getArgs()[1];
                assertEquals(0x7EE7C4, elementLabel.getStyle().getColor().getValue());
                assertEquals("custom.name.steam",
                        ((net.minecraft.network.chat.contents.TranslatableContents) elementLabel.getContents()).getKey());
                var amount = (net.minecraftforge.common.ForgeConfigSpec.DoubleValue)
                        ElementalPhaseServerConfig.SPEC.getValues().get(List.of("enchantments", "base_attachment_amount"));
                amount.set(4.0D);
                com.elementalphase.client.ClientElementBooks.tooltip(event);
                assertEquals(3, lines.size());
                assertEquals("4", ((net.minecraft.network.chat.contents.TranslatableContents)
                        lines.get(2).getContents()).getArgs()[0]);
                amount.set(2.5D);
            }
        } finally {
            ElementalPhaseServerConfig.SPEC.setConfig(originalConfig);
            ElementBookCatalog.replaceClient(catalog, colors);
        }
    }

    @Test
    void builtInElementTranslationsAreCompleteNamesWithoutRepeatedSuffixes() throws IOException {
        var expected = Map.of(
                "zh_cn", Map.of("fire", "火元素", "water", "水元素", "ice", "冰元素",
                        "lightning", "雷元素", "wind", "风元素"),
                "en_us", Map.of("fire", "Fire Element", "water", "Water Element", "ice", "Ice Element",
                        "lightning", "Lightning Element", "wind", "Wind Element"));
        for (var locale : expected.entrySet()) {
            var resource = "/assets/elemental_phase/lang/" + locale.getKey() + ".json";
            try (var stream = ElementEnchantmentDataTest.class.getResourceAsStream(resource)) {
                assertNotNull(stream, resource);
                var translations = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(
                        stream, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                for (var element : locale.getValue().entrySet()) {
                    assertEquals(element.getValue(), translations.get("element.elemental_phase." + element.getKey())
                            .getAsString(), resource + ": " + element.getKey());
                }
                String name = translations.get("element.elemental_phase.fire").getAsString();
                String enchantment = translations.get("enchantment.elemental_phase.element_name").getAsString();
                assertEquals(locale.getKey().equals("zh_cn") ? "火元素附着" : "Fire Element Attachment",
                        String.format(enchantment, name));
                String enhanced = translations.get("enchantment.elemental_phase.element_enhanced_name").getAsString();
                assertEquals(locale.getKey().equals("zh_cn") ? "火元素强化" : "Fire Element Empowerment",
                        String.format(enhanced, name));
                String enhancedDescription = translations.get(
                        "enchantment.elemental_phase.element_enhanced_description").getAsString();
                assertEquals(locale.getKey().equals("zh_cn")
                                ? "造成的伤害变为火元素伤害，并附着1点火元素。"
                                : "Attacks deal Fire Element damage and attach Fire Element (base amount: 1).",
                        String.format(enhancedDescription, "1", name, name));
            }
        }
    }

    @Test
    void enabledEnhancementChangesEnchantmentNameAndDescriptionWithoutRenamingBook() throws ReflectiveOperationException {
        var catalog = ElementBookCatalog.clientElements();
        var colors = ElementBookCatalog.clientColors();
        var previous = configureEnchantmentAmount(2.5D);
        try {
            ElementalPhaseServerConfig.ENCHANTMENT_ENHANCEMENT_ENABLED.set(true);
            ElementBookCatalog.replaceClient(Map.of(CUSTOM, "custom.name.steam"), Map.of(CUSTOM, 0x7EE7C4));
            var enchantment = com.elementalphase.registry.ModEnchantments.forElement(CUSTOM).orElseThrow();
            var book = ElementEnchantmentData.createBook(CUSTOM);
            assertEquals("item.minecraft.enchanted_book",
                    ((net.minecraft.network.chat.contents.TranslatableContents) book.getHoverName().getContents()).getKey());
            var name = (net.minecraft.network.chat.contents.TranslatableContents) enchantment.getFullname(1).getContents();
            assertEquals("enchantment.elemental_phase.element_enhanced_name", name.getKey());
            var description = (net.minecraft.network.chat.contents.TranslatableContents)
                    enchantment.description().getContents();
            assertEquals("enchantment.elemental_phase.element_enhanced_description", description.getKey());
            assertEquals("2.5", description.getArgs()[0]);
            for (int index = 1; index <= 2; index++) {
                Component element = (Component) description.getArgs()[index];
                assertEquals(0x7EE7C4, element.getStyle().getColor().getValue());
                assertEquals("custom.name.steam",
                        ((net.minecraft.network.chat.contents.TranslatableContents) element.getContents()).getKey());
            }
        } finally {
            ElementalPhaseServerConfig.SPEC.setConfig(previous);
            ElementBookCatalog.replaceClient(catalog, colors);
        }
    }

    @Test
    void replacesIndentedElementDescriptionWithoutChangingOrdinaryDescription() {
        var catalog = ElementBookCatalog.clientElements();
        var colors = ElementBookCatalog.clientColors();
        try {
            ElementBookCatalog.replaceClient(Map.of(FIRE, "element.elemental_phase.fire"), Map.of(FIRE, 0xFF7A00));
            ItemStack book = ElementEnchantmentData.createBook(FIRE);
            var enchantment = com.elementalphase.registry.ModEnchantments.forElement(FIRE).orElseThrow();
            Component title = book.getHoverName();
            Component name = enchantment.getFullname(1);
            Component untranslated = Component.literal("  ")
                    .append(Component.translatable(enchantment.getDescriptionId() + ".desc"));
            Component ordinaryDescription = Component.translatable("enchantment.minecraft.sharpness.desc");
            var lines = new ArrayList<>(List.of(title, name, untranslated, ordinaryDescription));
            var event = new net.minecraftforge.event.entity.player.ItemTooltipEvent(book, null, lines,
                    net.minecraft.world.item.TooltipFlag.Default.NORMAL);
            com.elementalphase.client.ClientElementBooks.tooltip(event);
            var description = assertInstanceOf(net.minecraft.network.chat.contents.TranslatableContents.class,
                    lines.get(2).getContents());
            assertEquals("enchantment.elemental_phase.element_description", description.getKey());
            assertSame(title, lines.get(0));
            assertSame(ordinaryDescription, lines.get(3));
            assertEquals(4, lines.size());
            com.elementalphase.client.ClientElementBooks.tooltip(event);
            assertEquals(4, lines.size());
        } finally {
            ElementBookCatalog.replaceClient(catalog, colors);
        }
    }

    @Test
    void configuredAttachmentAmountIsUsedAndCapturedByWeaponSource() throws ReflectiveOperationException {
        var originalConfig = configureEnchantmentAmount(2.5D);
        try {
            var snapshot = new com.elementalphase.data.ElementDataParser().parseLenient(Map.of(
                    id("example:elemental_phase/elements/steam.json"),
                    com.google.gson.JsonParser.parseString("{}"))).snapshot();
            ItemStack weapon = new ItemStack(Items.DIAMOND_SWORD);
            ElementEnchantmentData.setElement(weapon, CUSTOM);
            var select = com.elementalphase.combat.AttackElementResolver.class.getDeclaredMethod(
                    "enchantmentCandidate", ItemStack.class, com.elementalphase.data.ElementDataSnapshot.class);
            select.setAccessible(true);
            var candidate = (com.elementalphase.combat.ProjectileElementSnapshot.Candidate)
                    ((Optional<?>) select.invoke(null, weapon, snapshot)).orElseThrow();
            assertEquals(2.5D, candidate.baseAmount());
            var captured = new com.elementalphase.combat.ProjectileElementSnapshot(true, 3.0D,
                    Optional.of(candidate), Optional.empty());
            var context = com.elementalphase.combat.AttackElementResolver.class.getDeclaredMethod("context",
                    Optional.class, double.class, com.elementalphase.data.ElementDataSnapshot.class,
                    com.elementalphase.combat.ElementAttackContext.SourceKind.class);
            context.setAccessible(true);
            var attack = (com.elementalphase.combat.ElementAttackContext) ((Optional<?>) context.invoke(null,
                    captured.enchantment(), captured.strength(), snapshot,
                    com.elementalphase.combat.ElementAttackContext.SourceKind.ENCHANTMENT)).orElseThrow();
            assertEquals(7.5D, attack.mountAmount());
            var amount = (net.minecraftforge.common.ForgeConfigSpec.DoubleValue)
                    ElementalPhaseServerConfig.SPEC.getValues().get(List.of("enchantments", "base_attachment_amount"));
            amount.set(5.0D);
            var next = (com.elementalphase.combat.ProjectileElementSnapshot.Candidate)
                    ((Optional<?>) select.invoke(null, weapon, snapshot)).orElseThrow();
            assertEquals(5.0D, next.baseAmount());
            var tag = new net.minecraft.nbt.CompoundTag();
            captured.writeTo(tag);
            assertEquals(2.5D, com.elementalphase.combat.ProjectileElementSnapshot.readFrom(tag)
                    .orElseThrow().enchantment().orElseThrow().baseAmount());
        } finally {
            ElementalPhaseServerConfig.SPEC.setConfig(originalConfig);
        }
    }

    @Test
    void hiddenEnchantmentNamesAlsoHideDescriptions() {
        var catalog = ElementBookCatalog.clientElements();
        var colors = ElementBookCatalog.clientColors();
        try {
            ElementBookCatalog.replaceClient(Map.of(FIRE, "element.elemental_phase.fire"), Map.of(FIRE, 0xFF7A00));
            ItemStack book = ElementEnchantmentData.createBook(FIRE);
            ItemStack weapon = new ItemStack(Items.DIAMOND_SWORD);
            ElementEnchantmentData.setElement(weapon, FIRE);
            for (ItemStack stack : List.of(book, weapon)) {
                stack.getOrCreateTag().putInt("HideFlags", stack.is(Items.ENCHANTED_BOOK) ? 32 : 1);
                Component title = stack.getHoverName();
                var lines = new ArrayList<>(List.of(title));
                var event = new net.minecraftforge.event.entity.player.ItemTooltipEvent(stack, null, lines,
                        net.minecraft.world.item.TooltipFlag.Default.NORMAL);
                com.elementalphase.client.ClientElementBooks.tooltip(event);
                assertEquals(List.of(title), lines);
            }
        } finally {
            ElementBookCatalog.replaceClient(catalog, colors);
        }
    }

    private static CommentedConfig configureEnchantmentAmount(double amount) throws ReflectiveOperationException {
        var field = net.minecraftforge.common.ForgeConfigSpec.class.getDeclaredField("childConfig");
        field.setAccessible(true);
        var previous = (CommentedConfig) field.get(ElementalPhaseServerConfig.SPEC);
        var replacement = CommentedConfig.inMemory();
        ElementalPhaseServerConfig.SPEC.correct(replacement);
        ElementalPhaseServerConfig.SPEC.setConfig(replacement);
        replacement.set("enchantments.base_attachment_amount", amount);
        ElementalPhaseServerConfig.SPEC.afterReload();
        return previous;
    }

    @Test
    void emptyCatalogAndOtherCreativeCategoriesDoNotAddElementBooks() {
        var original = ElementBookCatalog.clientElements();
        try {
            ElementBookCatalog.replaceClient(Map.of(FIRE, "fire"));
            var other = creativeEvent(CreativeModeTabs.COMBAT);
            com.elementalphase.registry.ModCreativeTabs.addElementBooks(other);
            assertTrue(other.getEntries().isEmpty());
            ElementBookCatalog.replaceClient(Map.of());
            var empty = creativeEvent(CreativeModeTabs.INGREDIENTS);
            com.elementalphase.registry.ModCreativeTabs.addElementBooks(empty);
            assertTrue(empty.getEntries().isEmpty());
        } finally {
            ElementBookCatalog.replaceClient(original);
        }
    }

    private static BuildCreativeModeTabContentsEvent creativeEvent(ResourceKey<CreativeModeTab> key) {
        var tab = CreativeModeTab.builder().title(Component.literal("test"))
                .icon(() -> new ItemStack(Items.ENCHANTED_BOOK)).build();
        var parameters = new CreativeModeTab.ItemDisplayParameters(FeatureFlags.VANILLA_SET, false,
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        return new BuildCreativeModeTabContentsEvent(tab, key, parameters,
                new MutableHashedLinkedMap<>(ItemStackLinkedSet.TYPE_AND_TAG));
    }

    @Test
    void startupDiscoversModDataAndWorldDatapacks(@TempDir Path directory) throws IOException {
        Path data = directory.resolve("data");
        Path definitions = data.resolve("custom/elemental_phase/elements/weather");
        Files.createDirectories(definitions);
        Files.writeString(definitions.resolve("steam.json"), "{}");
        Files.writeString(definitions.resolve("ignored.txt"), "{}");
        Path worldData = directory.resolve("saves/first/datapacks/elements/data/example/elemental_phase/elements");
        Files.createDirectories(worldData);
        Files.writeString(worldData.resolve("acid.json"), "{}");
        assertEquals(java.util.Set.of(id("custom:weather/steam"), id("example:acid")),
                com.elementalphase.registry.ModEnchantments.startupElements(List.of(data), directory, List.of()));
    }

    @Test
    void startupDoesNotCreateOrReadAdditionalElementConfig(@TempDir Path directory) throws IOException {
        Path data = directory.resolve("data");
        Path definitions = data.resolve("custom/elemental_phase/elements");
        Files.createDirectories(definitions);
        Files.writeString(definitions.resolve("light.json"), "{}");
        Path config = directory.resolve("config/elemental_phase-enchantments.json");
        assertEquals(java.util.Set.of(id("custom:light")),
                com.elementalphase.registry.ModEnchantments.startupElements(List.of(data), directory, List.of()));
        assertFalse(Files.exists(config));
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{\"additional_elements\":[\"obsolete:unwanted\"]}");
        assertEquals(java.util.Set.of(id("custom:light")),
                com.elementalphase.registry.ModEnchantments.startupElements(List.of(data), directory, List.of()));
        assertEquals("{\"additional_elements\":[\"obsolete:unwanted\"]}", Files.readString(config));
    }

    @Test
    void startupDiscoversNestedElementPathsInsideModArchive(@TempDir Path directory) throws IOException {
        var archive = directory.resolve("mod.jar");
        try (var filesystem = java.nio.file.FileSystems.newFileSystem(
                java.net.URI.create("jar:" + archive.toUri()), Map.of("create", "true"))) {
            Path data = filesystem.getPath("/data");
            Path definitions = data.resolve("example/elemental_phase/elements/weather");
            Files.createDirectories(definitions);
            Files.writeString(definitions.resolve("steam.json"), "{}");
            assertEquals(java.util.Set.of(id("example:weather/steam")),
                    com.elementalphase.registry.ModEnchantments.startupElements(List.of(data),
                            directory, List.of()));
        }
    }

    @Test
    void startupReadsZipDatapacksDeduplicatesWorldsAndSkipsBrokenArchives(@TempDir Path directory) throws IOException {
        Path packs = directory.resolve("saves/first/datapacks");
        Files.createDirectories(packs);
        Files.writeString(packs.resolve("broken.zip"), "bad zip");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(packs.resolve("elements.zip")))) {
            for (String name : List.of("data/example/elemental_phase/elements/weather/steam.json",
                    "data/example/elemental_phase/elements/weather/ignored.txt", "data/example/recipes/ignored.json")) {
                zip.putNextEntry(new java.util.zip.ZipEntry(name));
                zip.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        Path second = directory.resolve("saves/second/datapacks/elements/data/example/elemental_phase/elements/weather");
        Files.createDirectories(second);
        Files.writeString(second.resolve("steam.json"), "{}");
        assertEquals(java.util.Set.of(id("example:weather/steam")),
                com.elementalphase.registry.ModEnchantments.startupElements(List.of(), directory, List.of()));
    }

    @Test
    void startupUsesDedicatedServerConfiguredWorld(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("server.properties"), "level-name=storage/custom-world\n");
        Path definitions = directory.resolve("storage/custom-world/datapacks/elements/data/server/elemental_phase/elements");
        Files.createDirectories(definitions);
        Files.writeString(definitions.resolve("acid.json"), "{}");
        assertEquals(java.util.Set.of(id("server:acid")),
                com.elementalphase.registry.ModEnchantments.startupElements(List.of(), directory, List.of()));
    }

    @Test
    void startupUsesServerUniverseAndWorldArguments(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("server.properties"), "level-name=ignored\n");
        Path definitions = directory.resolve("universe/selected/datapacks/elements/data/server/elemental_phase/elements");
        Files.createDirectories(definitions);
        Files.writeString(definitions.resolve("light.json"), "{}");
        for (var arguments : List.of(List.of("--universe", "universe", "--world", "selected"),
                List.of("--universe=universe", "--world=selected"))) {
            assertEquals(java.util.Set.of(id("server:light")),
                    com.elementalphase.registry.ModEnchantments.startupElements(List.of(), directory, arguments));
        }
    }

    @Test
    void startupCommandArgumentsPreservePathsWithSpaces() throws ReflectiveOperationException {
        var parse = com.elementalphase.registry.ModEnchantments.class.getDeclaredMethod("commandArguments", String.class);
        parse.setAccessible(true);
        for (String command : List.of("Launcher --universe C:\\game worlds --world selected world --nogui",
                "Launcher --universe=\"C:\\game worlds\" --world='selected world' --nogui")) {
            assertEquals(List.of("--universe", "C:\\game worlds", "--world", "selected world"), parse.invoke(null, command));
        }
        assertEquals(List.of(), parse.invoke(null, "Launcher --nogui"));
    }

    @Test
    void forgeSnapshotRestoresServerOnlyElementEnchantmentWithoutClientData() throws ReflectiveOperationException {
        var registry = (ForgeRegistry<net.minecraft.world.item.enchantment.Enchantment>) ForgeRegistries.ENCHANTMENTS;
        var original = net.minecraftforge.registries.RegistryManager.ACTIVE.takeSnapshot(false);
        var received = new java.util.HashMap<>(original);
        var enchantments = new ForgeRegistry.Snapshot();
        var old = original.get(ForgeRegistries.Keys.ENCHANTMENTS.location());
        enchantments.ids.putAll(old.ids);
        enchantments.aliases.putAll(old.aliases);
        enchantments.blocked.addAll(old.blocked);
        enchantments.overrides.putAll(old.overrides);
        ResourceLocation element = id("server:weather/steam");
        ResourceLocation enchantment = com.elementalphase.registry.ModEnchantments.enchantmentId(element);
        int nextId = 0;
        for (int value : enchantments.ids.values()) nextId = Math.max(nextId, value + 1);
        nextId += 17;
        enchantments.ids.put(enchantment, nextId);
        received.put(ForgeRegistries.Keys.ENCHANTMENTS.location(), enchantments);
        java.util.function.Consumer<net.minecraftforge.registries.MissingMappingsEvent> listener =
                com.elementalphase.registry.ModEnchantments::restoreMissingEnchantments;
        var initializeListenerList = net.minecraftforge.eventbus.api.EventListenerHelper.class.getDeclaredMethod(
                "getListenerListInternal", Class.class, boolean.class);
        initializeListenerList.setAccessible(true);
        initializeListenerList.invoke(null, net.minecraftforge.registries.MissingMappingsEvent.class, true);
        var bus = net.minecraftforge.common.MinecraftForge.EVENT_BUS;
        var shutdown = bus.getClass().getDeclaredField("shutdown");
        shutdown.setAccessible(true);
        boolean wasShutdown = shutdown.getBoolean(bus);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(listener);
        bus.start();
        try {
            assertTrue(com.elementalphase.registry.ModEnchantments.forElement(element).isEmpty());
            var missing = net.minecraftforge.registries.GameData.injectSnapshot(received, false, false);
            assertTrue(missing.isEmpty(), missing::toString);
            var restored = com.elementalphase.registry.ModEnchantments.forElement(element).orElseThrow();
            assertEquals(nextId, registry.getID(restored));
            assertTrue(registry.isLocked());
            assertEquals(Optional.of(element), ElementEnchantmentData.readElement(ElementEnchantmentData.createBook(element)));
            assertSame(Enchantments.SHARPNESS, ForgeRegistries.ENCHANTMENTS.getValue(id("minecraft:sharpness")));
        } finally {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(listener);
            try {
                assertTrue(net.minecraftforge.registries.GameData.injectSnapshot(original, false, false).isEmpty());
            } finally {
                shutdown.setBoolean(bus, wasShutdown);
            }
        }
        assertTrue(com.elementalphase.registry.ModEnchantments.forElement(element).isEmpty());
    }

    @Test
    void missingMappingRestorationLeavesUnrelatedAndMalformedIdentitiesUnchanged() throws ReflectiveOperationException {
        var registry = ForgeRegistries.ENCHANTMENTS;
        var keys = registry.getKeys();
        var original = java.util.Set.copyOf(keys);
        var action = net.minecraftforge.registries.MissingMappingsEvent.Mapping.class.getDeclaredField("action");
        action.setAccessible(true);
        for (String identity : List.of("elemental_phase:ordinary", "elemental_phase:attachment/fire",
                "elemental_phase:attachment/example/", "elemental_phase:attachment//steam",
                "other:attachment/example/steam")) {
            var mapping = new net.minecraftforge.registries.MissingMappingsEvent.Mapping<>(registry, registry, id(identity), 1000);
            var event = new net.minecraftforge.registries.MissingMappingsEvent(
                    ForgeRegistries.Keys.ENCHANTMENTS, registry, List.of(mapping));
            com.elementalphase.registry.ModEnchantments.restoreMissingEnchantments(event);
            assertEquals(net.minecraftforge.registries.MissingMappingsEvent.Action.DEFAULT, action.get(mapping), identity);
        }
        var itemOnly = new net.minecraftforge.registries.MissingMappingsEvent.Mapping<>(registry, registry,
                id("elemental_phase:attachment/example/item_only"), 1000);
        var otherRegistry = new net.minecraftforge.registries.MissingMappingsEvent(
                ForgeRegistries.Keys.ITEMS, ForgeRegistries.ITEMS, List.of(itemOnly));
        com.elementalphase.registry.ModEnchantments.restoreMissingEnchantments(otherRegistry);
        assertEquals(net.minecraftforge.registries.MissingMappingsEvent.Action.DEFAULT, action.get(itemOnly));
        assertEquals(original, registry.getKeys());
    }

    @Test
    void missingMappingRestorationReusesRegisteredElementWithoutChangingLockState() throws ReflectiveOperationException {
        var registry = (ForgeRegistry<net.minecraft.world.item.enchantment.Enchantment>) ForgeRegistries.ENCHANTMENTS;
        var original = com.elementalphase.registry.ModEnchantments.forElement(FIRE).orElseThrow();
        int size = registry.getKeys().size();
        boolean locked = registry.isLocked();
        var mapping = new net.minecraftforge.registries.MissingMappingsEvent.Mapping<>(registry, registry, ENCHANTMENT, 1000);
        com.elementalphase.registry.ModEnchantments.restoreMissingEnchantments(new net.minecraftforge.registries.MissingMappingsEvent(
                ForgeRegistries.Keys.ENCHANTMENTS, registry, List.of(mapping)));
        var action = net.minecraftforge.registries.MissingMappingsEvent.Mapping.class.getDeclaredField("action");
        action.setAccessible(true);
        var target = net.minecraftforge.registries.MissingMappingsEvent.Mapping.class.getDeclaredField("target");
        target.setAccessible(true);
        assertEquals(net.minecraftforge.registries.MissingMappingsEvent.Action.REMAP, action.get(mapping));
        assertSame(original, target.get(mapping));
        assertEquals(size, registry.getKeys().size());
        assertEquals(locked, registry.isLocked());
    }

    private static void mark(ItemStack stack, int level) {
        ListTag enchantments = new ListTag();
        enchantments.add(EnchantmentHelper.storeEnchantment(ENCHANTMENT, level));
        stack.getOrCreateTag().put(stack.is(Items.ENCHANTED_BOOK) ? "StoredEnchantments" : "Enchantments", enchantments);
    }

    private static ResourceLocation id(String value) {
        return ResourceLocation.tryParse(value);
    }
}
