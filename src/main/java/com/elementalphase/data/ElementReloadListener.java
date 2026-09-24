package com.elementalphase.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ElementReloadListener extends SimplePreparableReloadListener<ElementDataParser.ParseReport> {
    private static final String ROOT = "elemental_phase/";

    private final ReloadableServerResources serverResources;
    private final ElementDataParser parser = new ElementDataParser();

    public ElementReloadListener(ReloadableServerResources serverResources) {
        this.serverResources = serverResources;
    }

    @Override
    protected ElementDataParser.ParseReport prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, JsonElement> resources = new LinkedHashMap<>();
        for (String category : new String[]{"elements", "reactions", "entity_profiles", "attack_sources"}) {
            for (Map.Entry<ResourceLocation, Resource> entry : resourceManager
                    .listResources(ROOT + category, id -> id.getPath().endsWith(".json")).entrySet()) {
                resources.put(entry.getKey(), read(entry.getValue()));
            }
        }
        return parser.parseLenient(resources);
    }

    @Override
    protected void apply(ElementDataParser.ParseReport report, ResourceManager resourceManager, ProfilerFiller profiler) {
        ElementDataManager.stage(serverResources, report);
    }

    private static JsonElement read(Resource resource) {
        try (Reader reader = resource.openAsReader()) {
            return JsonParser.parseReader(reader);
        } catch (IOException | RuntimeException exception) {
            return com.google.gson.JsonNull.INSTANCE;
        }
    }
}
