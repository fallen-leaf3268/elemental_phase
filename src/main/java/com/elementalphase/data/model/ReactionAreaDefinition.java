package com.elementalphase.data.model;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record ReactionAreaDefinition(double radius, ResourceLocation spreadElement,
                                     double attachmentRatio, boolean attachAttacker) {
    public ReactionAreaDefinition {
        if (!Double.isFinite(radius) || radius < 0.1D || radius > 64.0D) {
            throw new IllegalArgumentException("radius must be between 0.1 and 64");
        }
        Objects.requireNonNull(spreadElement, "spreadElement");
        if (!Double.isFinite(attachmentRatio) || attachmentRatio < 0.0D || attachmentRatio > 1.0D) {
            throw new IllegalArgumentException("attachmentRatio must be between 0 and 1");
        }
    }
}
