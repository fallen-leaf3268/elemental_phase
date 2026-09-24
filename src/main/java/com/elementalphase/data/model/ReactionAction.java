package com.elementalphase.data.model;

import com.elementalphase.reaction.formula.ReactionFormula;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public sealed interface ReactionAction permits ReactionAction.MainDamageBonus, ReactionAction.AdditionalDamage,
        ReactionAction.AreaDamage, ReactionAction.MobEffect, ReactionAction.Ignite, ReactionAction.ApplyFreeze,
        ReactionAction.Knockback, ReactionAction.ModifyElement, ReactionAction.SpreadElement,
        ReactionAction.AttachElement, ReactionAction.ScheduleDamage {
    ResourceLocation DEFAULT_DAMAGE_TYPE = ResourceLocation.fromNamespaceAndPath("elemental_phase", "reaction");
    ReactionFormula when();

    enum Target { TARGET, ATTACKER }
    enum Center { TARGET, ATTACKER }
    enum KnockbackOrigin { ATTACKER, TARGET, REACTION }
    enum ElementOperation { ADD, SET, REMOVE, CLEAR }

    record Formula(String source, ReactionFormula compiled) {
        public Formula { Objects.requireNonNull(source); Objects.requireNonNull(compiled); }
        public double evaluate(com.elementalphase.reaction.formula.ReactionFormulaContext context) {
            return compiled.evaluate(context);
        }
    }

    record ElementReference(Kind kind, ResourceLocation fixed) {
        public ElementReference { Objects.requireNonNull(kind); }
        public enum Kind { TRIGGER, AURA, FIXED }
        public static ElementReference trigger() { return new ElementReference(Kind.TRIGGER, null); }
        public static ElementReference aura() { return new ElementReference(Kind.AURA, null); }
        public static ElementReference fixed(ResourceLocation id) { return new ElementReference(Kind.FIXED, Objects.requireNonNull(id)); }
    }

    record DamageSettings(ResourceLocation damageType, Optional<ElementReference> resistanceElement,
                          boolean bypassArmor, boolean bypassInvulnerability,
                          boolean allowElementApplication, boolean allowReactions) {
        public DamageSettings {
            Objects.requireNonNull(damageType);
            resistanceElement = resistanceElement == null ? Optional.empty() : resistanceElement;
        }
    }

    record MainDamageBonus(Formula formula, ReactionFormula when) implements ReactionAction {}
    record AdditionalDamage(Formula formula, DamageSettings settings, ReactionFormula when) implements ReactionAction {
        public ResourceLocation damageType() { return settings.damageType(); }
    }
    record AreaDamage(double radius, Formula formula, DamageSettings settings, Center center,
                      boolean includeOriginalTarget, boolean includeAttacker, int maxTargets,
                      Formula falloff, Optional<String> targetSet, ReactionFormula when) implements ReactionAction {
        public AreaDamage { targetSet = targetSet == null ? Optional.empty() : targetSet; }
    }
    record MobEffect(Target target, ResourceLocation effect, int durationTicks, int amplifier,
                     boolean ambient, boolean visible, ReactionFormula when) implements ReactionAction {}
    record Ignite(Target target, int durationTicks, ReactionFormula when) implements ReactionAction {}
    record ApplyFreeze(Target target, int durationTicks, ReactionFormula when) implements ReactionAction {
        public ApplyFreeze {
            Objects.requireNonNull(target);
            Objects.requireNonNull(when);
            if (durationTicks < 1) throw new IllegalArgumentException("Invalid freeze duration");
        }
    }
    record Knockback(Target target, Formula strength, KnockbackOrigin origin,
                     ReactionFormula when) implements ReactionAction {}
    record ModifyElement(Target target, ElementReference element, ElementOperation operation,
                         Optional<Formula> amount, ReactionFormula when) implements ReactionAction {
        public ModifyElement { amount = amount == null ? Optional.empty() : amount; }
    }
    record SpreadElement(Center center, double radius, ElementReference element, Formula amount,
                         boolean includeOriginalTarget, boolean includeAttacker, int maxTargets,
                         boolean respectAttachmentCooldown, Optional<String> sourceTargetSet,
                         ReactionFormula when) implements ReactionAction {
        public SpreadElement { sourceTargetSet = sourceTargetSet == null ? Optional.empty() : sourceTargetSet; }
    }
    record AttachElement(Target target, ResourceLocation element, Formula amount,
                         Optional<Integer> durationTicks, ReactionFormula when) implements ReactionAction {
        public AttachElement {
            Objects.requireNonNull(target);
            Objects.requireNonNull(element);
            Objects.requireNonNull(amount);
            durationTicks = durationTicks == null ? Optional.empty() : durationTicks;
            if (durationTicks.isPresent() && durationTicks.orElseThrow() < 1) {
                throw new IllegalArgumentException("Invalid attachment duration");
            }
        }
    }
    record ScheduleDamage(ResourceLocation id, Target target, int durationTicks, int intervalTicks,
                          StateDamage damage, ReactionFormula when) implements ReactionAction {
        public ScheduleDamage {
            Objects.requireNonNull(id);
            Objects.requireNonNull(target);
            Objects.requireNonNull(damage);
            Objects.requireNonNull(when);
            if (durationTicks < 0) throw new IllegalArgumentException("Invalid scheduled duration");
            if (intervalTicks < 1) throw new IllegalArgumentException("Invalid scheduled interval");
        }
    }
    record StateDamage(Formula formula, ResourceLocation damageType,
                       Optional<ElementReference> resistanceElement, Optional<Integer> color) {
        public StateDamage {
            Objects.requireNonNull(formula); Objects.requireNonNull(damageType);
            resistanceElement = resistanceElement == null ? Optional.empty() : resistanceElement;
            color = color == null ? Optional.empty() : color;
        }
    }
}
