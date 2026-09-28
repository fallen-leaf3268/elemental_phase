package com.elementalphase.data.model;

import com.elementalphase.reaction.formula.ReactionFormula;
import com.elementalphase.reaction.formula.ReactionFormulaContext;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;
import java.util.List;

public sealed interface ReactionAction permits ReactionAction.MainDamageBonus, ReactionAction.AdditionalDamage,
        ReactionAction.Area, ReactionAction.MobEffect, ReactionAction.Special,
        ReactionAction.ModifyElement,
        ReactionAction.AttachElement, ReactionAction.ScheduleDamage {
    ResourceLocation DEFAULT_DAMAGE_TYPE = ResourceLocation.fromNamespaceAndPath("elemental_phase", "reaction");
    ResourceLocation DEFAULT_DOT_DAMAGE_TYPE = ResourceLocation.fromNamespaceAndPath("elemental_phase", "reaction_dot");
    ReactionFormula when();

    enum Target { TARGET, ATTACKER }
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
        public ResourceLocation resolve(ResourceLocation trigger, ResourceLocation aura) {
            return switch (kind) { case TRIGGER -> trigger; case AURA -> aura; case FIXED -> fixed; };
        }
    }

    record DamageSettings(ResourceLocation damageType, Optional<ElementReference> resistanceElement) {
        public DamageSettings {
            Objects.requireNonNull(damageType);
            resistanceElement = resistanceElement == null ? Optional.empty() : resistanceElement;
        }
    }

    record MainDamageBonus(Formula formula, ReactionFormula when) implements ReactionAction {}
    record AdditionalDamage(Formula formula, DamageSettings settings, ReactionFormula when) implements ReactionAction {
        public ResourceLocation damageType() { return settings.damageType(); }
    }
    record Area(Formula radius, Optional<AreaDamageValue> damage, Optional<AreaAttachment> attachment,
                boolean includeAttacker, Optional<Integer> maxTargets, ReactionFormula when) implements ReactionAction {
        public Area { damage = Objects.requireNonNull(damage); attachment = Objects.requireNonNull(attachment); maxTargets = Objects.requireNonNull(maxTargets); }
    }
    record AreaDamageValue(Formula formula, DamageSettings settings, boolean includeTarget) {}
    record AreaAttachment(ElementReference element, Formula amount) {}
    record MobEffect(ResourceLocation effect, int durationTicks, int level, ReactionFormula when) implements ReactionAction {}
    record Special(List<SpecialEntry> entries, ReactionFormula when) implements ReactionAction {
        public Special { entries = List.copyOf(entries); }
    }
    record SpecialEntry(Kind type, int durationTicks) {
        public enum Kind { IGNITE, FREEZE }
        public SpecialEntry {
            Objects.requireNonNull(type);
            if (durationTicks < 1) throw new IllegalArgumentException("Invalid special duration");
        }
    }
    record ModifyElement(Target target, ElementReference element, ElementOperation operation,
                         Optional<Formula> amount, ReactionFormula when) implements ReactionAction {
        public ModifyElement { amount = amount == null ? Optional.empty() : amount; }
    }
    record AttachElement(ResourceLocation element, Formula amount, ReactionFormula when) implements ReactionAction {
        public AttachElement {
            Objects.requireNonNull(element);
            Objects.requireNonNull(amount);
        }
    }
    record ScheduleDamage(ResourceLocation id, int durationTicks, int intervalTicks,
                          StateDamage damage, ReactionFormula when) implements ReactionAction {
        public ScheduleDamage {
            Objects.requireNonNull(id);
            Objects.requireNonNull(damage);
            Objects.requireNonNull(when);
            if (durationTicks < 0) throw new IllegalArgumentException("Invalid scheduled duration");
            if (intervalTicks < 1) throw new IllegalArgumentException("Invalid scheduled interval");
        }
    }
    record StateDamage(Formula formula, ResourceLocation damageType, Optional<ElementReference> resistanceElement) {
        public StateDamage {
            Objects.requireNonNull(formula); Objects.requireNonNull(damageType);
            resistanceElement = resistanceElement == null ? Optional.empty() : resistanceElement;
        }
    }

    record StateSnapshot(ReactionFormulaContext context, Optional<ResourceLocation> resistanceElement,
                         double elementResistance, double reactionResistance, int color, boolean showName) {
        public StateSnapshot { Objects.requireNonNull(context); resistanceElement = Objects.requireNonNull(resistanceElement); }
        public StateSnapshot withCurrentDamage(double value) {
            var c = context;
            var updated = new ReactionFormulaContext(c.originalDamage(), value, c.scale(), c.triggerAmount(), c.auraAmount(),
                    c.consumedTrigger(), c.consumedAura(), c.remainingTrigger(), c.remainingAura(), c.attackerLevel(),
                    c.elementStrength(), c.targetHealth(), c.targetMaxHealth(), c.targetHealthRatio(), c.targetResistance(),
                    c.distance(), c.radius());
            return new StateSnapshot(updated, resistanceElement, elementResistance, reactionResistance, color, showName);
        }
    }
}
