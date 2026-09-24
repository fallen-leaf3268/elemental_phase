package com.elementalphase.state;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record ApplicationCooldownKey(UUID source, UUID target, ResourceLocation group) {
    public ApplicationCooldownKey {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(group, "group");
    }
}
