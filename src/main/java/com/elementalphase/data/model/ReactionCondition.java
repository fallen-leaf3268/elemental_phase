package com.elementalphase.data.model;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public sealed interface ReactionCondition permits ReactionCondition.AttackerPresent,
        ReactionCondition.AttackerEntity, ReactionCondition.TargetEntity, ReactionCondition.DamageType,
        ReactionCondition.Source, ReactionCondition.MinimumDamage, ReactionCondition.TargetOnFire,
        ReactionCondition.TargetInWater, ReactionCondition.TargetIsBoss {
    boolean inverted();

    record AttackerPresent(boolean value, boolean inverted) implements ReactionCondition {}
    record AttackerEntity(Optional<ResourceLocation> entity, Optional<ResourceLocation> tag,
                          boolean inverted) implements ReactionCondition {}
    record TargetEntity(Optional<ResourceLocation> entity, Optional<ResourceLocation> tag,
                        boolean inverted) implements ReactionCondition {}
    record DamageType(Optional<ResourceLocation> damageType, Optional<ResourceLocation> tag,
                      boolean inverted) implements ReactionCondition {}
    record Source(Kind kind, boolean inverted) implements ReactionCondition {
        public enum Kind { MELEE, PROJECTILE, MAGIC, ENVIRONMENT }
    }
    record MinimumDamage(double value, boolean inverted) implements ReactionCondition {}
    record TargetOnFire(boolean value, boolean inverted) implements ReactionCondition {}
    record TargetInWater(boolean value, boolean inverted) implements ReactionCondition {}
    record TargetIsBoss(boolean value, boolean inverted) implements ReactionCondition {}
}
