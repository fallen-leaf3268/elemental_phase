package com.elementalphase.data.model;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public sealed interface ReactionCondition permits ReactionCondition.AttackerPresent,
        ReactionCondition.AttackerEntity, ReactionCondition.TargetEntity, ReactionCondition.DamageType,
        ReactionCondition.MinimumDamage, ReactionCondition.TargetState {
    boolean inverted();

    record AttackerPresent(boolean value) implements ReactionCondition {
        @Override public boolean inverted() { return false; }
    }
    record AttackerEntity(Optional<ResourceLocation> entity, Optional<ResourceLocation> tag,
                          boolean inverted) implements ReactionCondition {}
    record TargetEntity(Optional<ResourceLocation> entity, Optional<ResourceLocation> tag,
                        boolean inverted) implements ReactionCondition {}
    record DamageType(Optional<ResourceLocation> damageType, Optional<ResourceLocation> tag,
                      boolean inverted) implements ReactionCondition {}
    record MinimumDamage(double value, boolean inverted) implements ReactionCondition {}
    record TargetState(State state, boolean value) implements ReactionCondition {
        public enum State { ON_FIRE, IN_WATER, FROZEN }
        @Override public boolean inverted() { return false; }
    }
}
