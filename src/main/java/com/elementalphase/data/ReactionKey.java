package com.elementalphase.data;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record ReactionKey(ResourceLocation first, ResourceLocation second) {
    public ReactionKey {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
    }

    public static ReactionKey of(ResourceLocation a, ResourceLocation b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        return a.toString().compareTo(b.toString()) <= 0 ? new ReactionKey(a, b) : new ReactionKey(b, a);
    }
}
