package com.elementalphase.data.model;

import net.minecraft.resources.ResourceLocation;

public record ReactionEffectDefinition(
        EffectType type,
        EffectTarget target,
        ResourceLocation effectId,
        int durationTicks,
        int amplifier,
        double strength,
        ResourceLocation element,
        ElementOperation operation,
        double amount) {
    public enum EffectType {
        MOB_EFFECT,
        IGNITE,
        FREEZE,
        KNOCKBACK,
        ELEMENT_CHANGE
    }

    public enum EffectTarget {
        ATTACKER,
        TARGET
    }

    public enum ElementOperation {
        ADD,
        SET,
        REMOVE
    }
}
