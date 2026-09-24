package com.elementalphase.data.model;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

public record EntityProfileDefinition(
        ResourceLocation id,
        Selector selector,
        int priority,
        Map<ResourceLocation, PermanentElement> permanentElements,
        Set<ResourceLocation> removedPermanentElements,
        Map<ResourceLocation, Double> resistances,
        Optional<IntrinsicAttack> intrinsicAttack,
        boolean clearIntrinsicAttack,
        OptionalDouble elementStrength) {
    public EntityProfileDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(selector, "selector");
        permanentElements = Map.copyOf(permanentElements);
        removedPermanentElements = Set.copyOf(removedPermanentElements);
        resistances = Map.copyOf(resistances);
        intrinsicAttack = intrinsicAttack == null ? Optional.empty() : intrinsicAttack;
        elementStrength = elementStrength == null ? OptionalDouble.empty() : elementStrength;
    }

    public record Selector(SelectorKind kind, ResourceLocation id) {
        public Selector {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
        }
    }

    public enum SelectorKind {
        ENTITY_TAG,
        ENTITY_ID
    }

    public record PermanentElement(double amount, int restoreDelayTicks) {
    }

    public record IntrinsicAttack(ResourceLocation element, double baseAmount) {
        public IntrinsicAttack {
            Objects.requireNonNull(element, "element");
        }
    }
}
