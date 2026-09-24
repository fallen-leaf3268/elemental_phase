package com.elementalphase.data.model;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record ElementDisplayDefinition(String translationKey, int color, boolean visibleInJade, int order,
                                       Optional<ResourceLocation> icon) {
    public ElementDisplayDefinition {
        Objects.requireNonNull(translationKey, "translationKey");
        icon = Objects.requireNonNull(icon, "icon");
    }
}
