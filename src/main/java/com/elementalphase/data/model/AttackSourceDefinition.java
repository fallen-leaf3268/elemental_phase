package com.elementalphase.data.model;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record AttackSourceDefinition(ResourceLocation id, SourceKind kind, String selector,
                                     ResourceLocation element, double baseAmount, int priority,
                                     Optional<Application> application) {
    public AttackSourceDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(selector, "selector");
        Objects.requireNonNull(element, "element");
        application = application == null ? Optional.empty() : application;
    }

    public AttackSourceDefinition(ResourceLocation id, SourceKind kind, String selector,
                                  ResourceLocation element, double baseAmount, int priority) {
        this(id, kind, selector, element, baseAmount, priority, Optional.empty());
    }

    public record Application(ResourceLocation cooldownGroup, int cooldownTicks) {
        public Application {
            Objects.requireNonNull(cooldownGroup, "cooldownGroup");
            if (cooldownTicks < 0) {
                throw new IllegalArgumentException("Invalid application cooldown");
            }
        }
    }

    public enum SourceKind {
        ENCHANTMENT_ID,
        ENCHANTMENT_TAG,
        DAMAGE_TYPE_ID,
        DAMAGE_TYPE_TAG
    }
}
