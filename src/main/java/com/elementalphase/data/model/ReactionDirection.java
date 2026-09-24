package com.elementalphase.data.model;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;

public record ReactionDirection(ResourceLocation trigger, ResourceLocation aura, int priority,
                                double minimumScale, ReactionConsumption consumption,
                                List<ReactionCondition> conditions, ReactionDisplay display,
                                List<ReactionAction> actions) {
    public ReactionDirection {
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(aura, "aura");
        Objects.requireNonNull(consumption, "consumption");
        conditions = List.copyOf(conditions);
        Objects.requireNonNull(display, "display");
        actions = List.copyOf(actions);
    }
}
