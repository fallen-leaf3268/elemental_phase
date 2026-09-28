package com.elementalphase.registry;

import com.elementalphase.ElementalPhase;
import com.elementalphase.enchantment.ElementAttachmentEnchantment;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.MissingMappingsEvent;
import net.minecraftforge.registries.RegisterEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipFile;

public final class ModEnchantments {
    private static final String PREFIX = "attachment/";
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    private ModEnchantments() {
    }

    public static ResourceLocation enchantmentId(ResourceLocation element) {
        return ResourceLocation.fromNamespaceAndPath(ElementalPhase.MOD_ID,
                PREFIX + element.getNamespace() + "/" + element.getPath());
    }

    public static Optional<ElementAttachmentEnchantment> forElement(ResourceLocation element) {
        Enchantment enchantment = ForgeRegistries.ENCHANTMENTS.getValue(enchantmentId(element));
        return enchantment instanceof ElementAttachmentEnchantment attachment && attachment.element().equals(element)
                ? Optional.of(attachment) : Optional.empty();
    }

    public static void register(IEventBus eventBus) {
        Path gameDirectory = FMLPaths.GAMEDIR.get();
        try {
            Set<ResourceLocation> elements = startupElements(ModList.get().getModFiles().stream()
                    .map(file -> file.getFile().findResource("data")).toList(), gameDirectory, launchArguments());
            eventBus.addListener((RegisterEvent event) -> register(event, elements));
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(ModEnchantments::restoreMissingEnchantments);
        } catch (IOException exception) {
            throw new IllegalStateException("无法扫描元素附魔数据包 " + gameDirectory, exception);
        }
    }

    public static void register(RegisterEvent event, Collection<ResourceLocation> elements) {
        event.register(ForgeRegistries.Keys.ENCHANTMENTS, helper -> elements.stream().distinct()
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .forEach(element -> helper.register(enchantmentId(element), new ElementAttachmentEnchantment(element))));
    }

    public static void restoreMissingEnchantments(MissingMappingsEvent event) {
        for (var mapping : event.getMappings(ForgeRegistries.Keys.ENCHANTMENTS, ElementalPhase.MOD_ID)) {
            ResourceLocation key = mapping.getKey();
            if (!key.getPath().startsWith(PREFIX)) continue;
            String identity = key.getPath().substring(PREFIX.length());
            int separator = identity.indexOf('/');
            if (separator <= 0 || separator == identity.length() - 1) continue;
            ResourceLocation element = ResourceLocation.tryParse(identity.substring(0, separator)
                    + ":" + identity.substring(separator + 1));
            if (element == null || !enchantmentId(element).equals(key)) continue;
            var registry = (ForgeRegistry<Enchantment>) ForgeRegistries.ENCHANTMENTS;
            Enchantment enchantment = registry.getValue(key);
            if (enchantment == null) {
                boolean locked = registry.isLocked();
                registry.unfreeze();
                try {
                    enchantment = new ElementAttachmentEnchantment(element);
                    registry.register(-1, key, enchantment);
                } finally {
                    if (locked) registry.freeze();
                }
            }
            if (enchantment instanceof ElementAttachmentEnchantment attachment
                    && attachment.element().equals(element)) mapping.remap(enchantment);
        }
    }

    public static Set<ResourceLocation> startupElements(Collection<Path> roots, Path gameDirectory,
                                                        List<String> launchArguments) throws IOException {
        Set<ResourceLocation> elements = new HashSet<>();
        for (Path root : roots) readDataDirectory(root, elements);
        Set<Path> worlds = new HashSet<>();
        Path saves = gameDirectory.resolve("saves");
        if (Files.isDirectory(saves)) {
            try (var directories = Files.list(saves)) {
                directories.filter(Files::isDirectory).forEach(worlds::add);
            }
        }
        Properties properties = new Properties();
        Path serverProperties = gameDirectory.resolve("server.properties");
        if (Files.isRegularFile(serverProperties)) {
            try (var reader = Files.newBufferedReader(serverProperties, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        Path universe = gameDirectory.resolve(argument(launchArguments, "--universe").orElse("."));
        worlds.add(universe.resolve(argument(launchArguments, "--world")
                .orElse(properties.getProperty("level-name", "world"))));
        for (Path world : worlds) {
            Path packs = world.resolve("datapacks");
            if (!Files.isDirectory(packs)) continue;
            try (var files = Files.list(packs)) {
                for (Path pack : files.toList()) {
                    try {
                        if (Files.isDirectory(pack)) readDataDirectory(pack.resolve("data"), elements);
                        else if (pack.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
                            try (var zip = new ZipFile(pack.toFile())) {
                                var entries = zip.entries();
                                while (entries.hasMoreElements()) {
                                    var entry = entries.nextElement();
                                    if (entry.isDirectory()) continue;
                                    String name = entry.getName();
                                    if (!name.startsWith("data/")) continue;
                                    int namespaceEnd = name.indexOf('/', 5);
                                    if (namespaceEnd < 0) continue;
                                    String prefix = "data/" + name.substring(5, namespaceEnd) + "/elemental_phase/elements/";
                                    if (!name.startsWith(prefix) || !name.endsWith(".json")) continue;
                                    addElement(elements, name.substring(5, namespaceEnd),
                                            name.substring(prefix.length(), name.length() - 5));
                                }
                            }
                        }
                    } catch (IOException exception) {
                        LOGGER.warn("Cannot scan element enchantments in data pack {}: {}", pack, exception.getMessage());
                    }
                }
            }
        }
        return Set.copyOf(elements);
    }

    public static Set<ResourceLocation> startupElements(Collection<Path> roots, Path gameDirectory) throws IOException {
        return startupElements(roots, gameDirectory, List.of());
    }

    private static void readDataDirectory(Path root, Set<ResourceLocation> elements) throws IOException {
        if (!Files.isDirectory(root)) return;
        try (var namespaces = Files.list(root)) {
            for (Path namespace : namespaces.filter(Files::isDirectory).toList()) {
                Path definitions = namespace.resolve("elemental_phase/elements");
                if (!Files.isDirectory(definitions)) continue;
                try (var files = Files.walk(definitions)) {
                    for (Path file : files.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                        String relative = definitions.relativize(file).toString().replace('\\', '/');
                        addElement(elements, namespace.getFileName().toString(),
                                relative.substring(0, relative.length() - 5));
                    }
                }
            }
        }
    }

    private static void addElement(Set<ResourceLocation> elements, String namespace, String path) {
        ResourceLocation element = ResourceLocation.tryParse(namespace + ":" + path);
        if (element != null && !namespace.isEmpty() && !path.isEmpty()) elements.add(element);
    }

    private static Optional<String> argument(List<String> arguments, String name) {
        for (int index = 0; index < arguments.size(); index++) {
            String value = arguments.get(index);
            if (value.startsWith(name + "=")) return Optional.of(value.substring(name.length() + 1));
            if (value.equals(name) && index + 1 < arguments.size()) return Optional.of(arguments.get(index + 1));
        }
        return Optional.empty();
    }

    private static List<String> launchArguments() {
        var arguments = ProcessHandle.current().info().arguments();
        if (arguments.isPresent()) return java.util.Arrays.asList(arguments.get());
        return commandArguments(System.getProperty("sun.java.command", ""));
    }

    private static List<String> commandArguments(String command) {
        var result = new java.util.ArrayList<String>();
        var matcher = java.util.regex.Pattern.compile("(?:^|\\s)(--(?:world|universe))(?:=|\\s+)(.*?)(?=\\s+--[^\\s=]+(?:=|\\s|$)|$)")
                .matcher(command);
        while (matcher.find()) {
            String value = matcher.group(2).trim();
            if (value.length() >= 2 && ((value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"')
                    || (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1, value.length() - 1);
            result.add(matcher.group(1));
            result.add(value);
        }
        return List.copyOf(result);
    }
}
