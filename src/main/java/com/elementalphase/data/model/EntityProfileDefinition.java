package com.elementalphase.data.model;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record EntityProfileDefinition(
        ResourceLocation id,
        Selector selector,
        int priority,
        Map<ResourceLocation, PermanentElement> permanentElements,
        Map<ResourceLocation, Double> resistances,
        Map<ResourceLocation, Double> reactionResistances,
        Optional<IntrinsicAttack> intrinsicAttack,
        boolean clearIntrinsicAttack) {
    public EntityProfileDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(selector, "selector");
        permanentElements = Map.copyOf(permanentElements);
        resistances = Map.copyOf(resistances);
        reactionResistances = Map.copyOf(reactionResistances);
        intrinsicAttack = intrinsicAttack == null ? Optional.empty() : intrinsicAttack;
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
