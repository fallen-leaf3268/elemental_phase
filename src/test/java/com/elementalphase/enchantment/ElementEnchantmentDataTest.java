package com.elementalphase.enchantment;

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
        var damageRegistry = new net.minecraft.core.MappedRegistry<net.minecraft.world.damagesource.DamageType>(
                Registries.DAMAGE_TYPE, com.mojang.serialization.Lifecycle.stable());
        var registries = new RegistryAccess.ImmutableRegistryAccess(List.of(damageRegistry));
        try {
            var snapshot = new com.elementalphase.data.ElementDataParser().parseLenient(Map.of(
                    id("elemental_phase:elemental_phase/elements/fire.json"),
                    com.google.gson.JsonParser.parseString("{}"))).snapshot();
            com.elementalphase.data.ElementDataManager.replace(snapshot, registries);
            ElementBookCatalog.replaceClient(Map.of(FIRE, "custom.name.fire", CUSTOM, "custom.name.steam"));
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
            com.elementalphase.data.ElementDataManager.replace(com.elementalphase.data.ElementDataSnapshot.empty(), registries);
            assertFalse(fire.isDiscoverable());
            assertFalse(fire.isTradeable());
        } finally {
            ElementBookCatalog.replaceClient(catalog);
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
