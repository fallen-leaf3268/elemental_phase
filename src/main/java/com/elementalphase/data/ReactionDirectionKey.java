package com.elementalphase.data;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record ReactionDirectionKey(ResourceLocation trigger, ResourceLocation aura) {
    public ReactionDirectionKey {
        Objects.requireNonNull(trigger);
        Objects.requireNonNull(aura);
    }
}
