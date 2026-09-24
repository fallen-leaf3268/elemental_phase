package com.elementalphase.data.model;

import com.elementalphase.reaction.formula.DamageFormula;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record ReactionDamageDefinition(
        Mode mode,
        String formulaSource,
        DamageFormula formula,
        Optional<ResourceLocation> damageType,
        Optional<ReactionAreaDefinition> area) {
    public static final ResourceLocation DEFAULT_DAMAGE_TYPE = ResourceLocation.fromNamespaceAndPath("elemental_phase", "reaction");

    public ReactionDamageDefinition {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(formulaSource, "formulaSource");
        Objects.requireNonNull(formula, "formula");
        damageType = Objects.requireNonNull(damageType, "damageType");
        area = Objects.requireNonNull(area, "area");
        if (formulaSource.isBlank()) {
            throw new IllegalArgumentException("formulaSource must not be blank");
        }
        if (mode == Mode.AMPLIFY && damageType.isPresent()) {
            throw new IllegalArgumentException("amplify damage must not specify a damage type");
        }
        if (mode == Mode.AMPLIFY && area.isPresent()) {
            throw new IllegalArgumentException("area is only supported for additional damage");
        }
        if (mode == Mode.ADDITIONAL && damageType.isEmpty()) {
            damageType = Optional.of(DEFAULT_DAMAGE_TYPE);
        }
    }

    public ReactionDamageDefinition(Mode mode, String formulaSource, DamageFormula formula,
                                    Optional<ResourceLocation> damageType) {
        this(mode, formulaSource, formula, damageType, Optional.empty());
    }

    public enum Mode {
        AMPLIFY,
        ADDITIONAL
    }
}
