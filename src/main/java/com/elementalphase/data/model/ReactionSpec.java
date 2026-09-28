package com.elementalphase.data.model;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ReactionSpec(ResourceLocation id, int priority, Set<ResourceLocation> elements,
                           List<ReactionDirection> directions, String translationKey) {
    public ReactionSpec {
        Objects.requireNonNull(id, "id");
        elements = Set.copyOf(elements);
        directions = List.copyOf(directions);
        Objects.requireNonNull(translationKey, "translationKey");
        if (translationKey.isBlank()) throw new IllegalArgumentException("translationKey 不能为空");
    }

    public ReactionSpec(ResourceLocation id, int priority, Set<ResourceLocation> elements,
                        List<ReactionDirection> directions) {
        this(id, priority, elements, directions, defaultTranslationKey(id));
    }

    public static String defaultTranslationKey(ResourceLocation id) {
        return "reaction." + id.getNamespace() + "." + id.getPath().replace('/', '.');
    }
}
