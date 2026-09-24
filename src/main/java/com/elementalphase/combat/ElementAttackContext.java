package com.elementalphase.combat;

import com.elementalphase.data.model.AttackSourceDefinition;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record ElementAttackContext(ResourceLocation element, double mountAmount, SourceKind sourceKind,
                                   ResourceLocation sourceId, Optional<AttackSourceDefinition.Application> application,
                                   double elementStrength) {
    public ElementAttackContext {
        Objects.requireNonNull(element, "element");
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(sourceId, "sourceId");
        application = application == null ? Optional.empty() : application;
        if (!Double.isFinite(mountAmount) || mountAmount < 0.000001D || mountAmount > 1_000_000.0D) {
            throw new IllegalArgumentException("Invalid mount amount");
        }
        if (!Double.isFinite(elementStrength) || elementStrength < 0.0D || elementStrength > 1_000_000.0D) {
            throw new IllegalArgumentException("Invalid element strength");
        }
    }

    public ElementAttackContext(ResourceLocation element, double mountAmount, SourceKind sourceKind,
                                ResourceLocation sourceId) {
        this(element, mountAmount, sourceKind, sourceId, Optional.empty(), 1.0D);
    }

    public ElementAttackContext(ResourceLocation element, double mountAmount, SourceKind sourceKind,
                                ResourceLocation sourceId, Optional<AttackSourceDefinition.Application> application) {
        this(element, mountAmount, sourceKind, sourceId, application, 1.0D);
    }

    public enum SourceKind {
        ENCHANTMENT,
        INTRINSIC,
        DAMAGE_TYPE_ID,
        DAMAGE_TYPE_TAG
    }
}
