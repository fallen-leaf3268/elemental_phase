package com.elementalphase.data.model;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ReactionSpec(ResourceLocation id, int priority, Set<ResourceLocation> elements,
                           List<ReactionDirection> directions) {
    public ReactionSpec {
        Objects.requireNonNull(id, "id");
        elements = Set.copyOf(elements);
        directions = List.copyOf(directions);
    }
}
