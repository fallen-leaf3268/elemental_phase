package com.elementalphase.integration.kubejs;

import com.elementalphase.data.ElementDataSnapshot;
import com.elementalphase.data.ReactionDataParser;
import com.elementalphase.data.model.ReactionSpec;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.latvian.mods.kubejs.event.EventJS;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RegisterReactionsEventJS extends EventJS {
    private final ElementDataSnapshot base;
    private final Map<ResourceLocation, ReactionSpec> replacements = new LinkedHashMap<>();
    private final Set<ResourceLocation> disabled = new LinkedHashSet<>();
    private final List<String> errors = new ArrayList<>();

    public RegisterReactionsEventJS(ElementDataSnapshot base) {
        this.base = base;
    }

    public void create(String idValue, Object value) {
        ResourceLocation id = ResourceLocation.tryParse(idValue);
        if (id == null) {
            errors.add("Invalid reaction id " + idValue);
            return;
        }
        try {
            JsonElement json = KubeJsValueConverter.toJson(value);
            if (!json.isJsonObject()) {
                errors.add(id + ": reaction must be an object");
                return;
            }
            ReactionDataParser.ParseResult parsed = new ReactionDataParser().parse(id, json.getAsJsonObject(), base.elements());
            if (!parsed.errors().isEmpty()) {
                parsed.errors().forEach(error -> errors.add(id + ": " + error));
            } else if (parsed.definition() != null) {
                replacements.put(id, parsed.definition());
                disabled.remove(id);
            }
        } catch (RuntimeException exception) {
            errors.add(id + ": " + exception.getMessage());
        }
    }

    public void disable(String idValue) {
        ResourceLocation id = ResourceLocation.tryParse(idValue);
        if (id == null) {
            errors.add("Invalid reaction id " + idValue);
        } else {
            disabled.add(id);
            replacements.remove(id);
        }
    }

    public KubeJsHooks.Overlay overlay() {
        return new KubeJsHooks.Overlay(replacements, disabled, Map.of());
    }

    public List<String> errors() {
        return List.copyOf(errors);
    }
}
