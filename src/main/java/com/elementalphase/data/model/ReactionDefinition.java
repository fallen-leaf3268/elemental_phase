package com.elementalphase.data.model;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;

public record ReactionDefinition(
        ResourceLocation id,
        ResourceLocation elementA,
        ResourceLocation elementB,
        double ratioA,
        double ratioB,
        double minimumScale,
        ReactionDamageDefinition damage,
        int displayColor,
        List<ReactionEffectDefinition> effects) {
    public ReactionDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(elementA, "elementA");
        Objects.requireNonNull(elementB, "elementB");
        Objects.requireNonNull(damage, "damage");
        if (displayColor < 0 || displayColor > 0xFFFFFF) {
            throw new IllegalArgumentException("displayColor must be a 24-bit RGB value");
        }
        effects = List.copyOf(effects);
    }

}
