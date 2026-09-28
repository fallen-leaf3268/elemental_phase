package com.elementalphase.combat;

import com.elementalphase.data.ReactionIndex;
import com.elementalphase.data.model.ElementDefinition;
import com.elementalphase.data.model.ReactionDamageDefinition;
import com.elementalphase.reaction.ReactionEngine;
import com.elementalphase.reaction.ReactionOutcome;
import com.elementalphase.reaction.ReactionPlan;
import com.elementalphase.reaction.ReactionRequest;
import com.elementalphase.reaction.ReactionChainGuard;
import com.elementalphase.data.model.ReactionCondition;
import com.elementalphase.state.ElementRuntimeState;
import com.elementalphase.state.ElementSourceSnapshot;
import com.elementalphase.state.ElementalState;

import java.util.Optional;
import java.util.function.Predicate;

public final class CombatPipeline {

    private final ReactionEngine reactionEngine = new ReactionEngine();

    public CombatResult resolve(CombatInput input) {
        double originalDamage = Double.isFinite(input.originalDamage()) ? Math.max(0.0D, input.originalDamage()) : 0.0D;
        double resistance = ResistancePolicy.clamp(input.resistance());
        double baseDamage = ResistancePolicy.apply(originalDamage, resistance);
        ElementRuntimeState.ApplyResult applyResult;
        ReactionOutcome outcome = ReactionOutcome.empty();
        ReactionPlan plan = ReactionPlan.empty(baseDamage, ElementRuntimeState.ApplyResult.NO_AMOUNT);
        if (input.attack() == null || input.elementDefinition() == null
                || input.attack().mountAmount() <= 0.0D) {
            applyResult = ElementRuntimeState.ApplyResult.NO_AMOUNT;
        } else {
            double incoming = Math.min(input.attack().mountAmount(),
                    input.elementDefinition().attachment().maxAmount());
            applyResult = input.targetState().tryTriggerVirtual(input.attack().element(), incoming,
                    input.gameTime(), input.elementDefinition().attachment().cooldownTicks());
            var current = input.targetState().state(input.attack().element());
            if (applyResult.changed() && input.elementDefinition().attachment().virtual()
                    && current != null && incoming < current.effectiveAmount(input.gameTime())) {
                applyResult = ElementRuntimeState.ApplyResult.IGNORED_LOWER;
            }
            if (applyResult.changed()) {
                plan = reactionEngine.react(new ReactionRequest(input.targetState(), input.attack().element(),
                        incoming, input.gameTime(), input.elementDefinition(), input.reactionIndex(),
                        input.elements(),
                        baseDamage, input.attackerLevel(), input.attack().elementStrength(), input.targetHealth(),
                        input.targetMaxHealth(), resistance, Optional.ofNullable(input.source()), input.conditionEvaluator(),
                        input.guard(), true));
                applyResult = plan.applyResult();
                if (applyResult.changed()) {
                    input.targetState().startElementApplicationCooldown(input.attack().element(), input.gameTime(),
                            input.elementDefinition().attachment().cooldownTicks());
                }
                outcome = new ReactionOutcome(plan.mainDamageBonus(), java.util.List.of(), plan.mainDamageLabels().stream()
                        .map(label -> new ReactionOutcome.TriggeredReaction(label.reactionId(), label.scale(), 0.0D,
                                ReactionDamageDefinition.Mode.AMPLIFY, Optional.empty(), label.color(), Optional.empty(),
                                label.reactionId(), label.visible()))
                        .toList());
            }
        }
        double finalDamage = plan.labels().isEmpty() ? baseDamage : plan.finalDamage();
        return new CombatResult(baseDamage, plan.mainDamageBonus(), finalDamage, applyResult, outcome, plan);
    }

    public record CombatInput(double originalDamage, double resistance, ElementAttackContext attack,
                              ElementalState targetState, long gameTime, ElementDefinition elementDefinition,
                              ReactionIndex reactionIndex, java.util.Map<net.minecraft.resources.ResourceLocation, ElementDefinition> elements,
                              ElementSourceSnapshot source,
                              double attackerLevel,
                              double targetHealth, double targetMaxHealth,
                              Predicate<ReactionCondition> conditionEvaluator, ReactionChainGuard guard) {
        public CombatInput {
            if (targetState == null || reactionIndex == null || elements == null) {
                throw new IllegalArgumentException("targetState and reactionIndex are required");
            }
            conditionEvaluator = conditionEvaluator == null ? ignored -> true : conditionEvaluator;
            guard = guard == null ? new ReactionChainGuard() : guard;
        }

        public CombatInput(double originalDamage, double resistance, ElementAttackContext attack,
                           ElementalState targetState, long gameTime, ElementDefinition elementDefinition,
                           ReactionIndex reactionIndex, ElementSourceSnapshot source) {
            this(originalDamage, resistance, attack, targetState, gameTime, elementDefinition, reactionIndex,
                    java.util.Map.of(), source,
                    0.0D, 0.0D, 0.0D, ignored -> true, new ReactionChainGuard());
        }
    }

    public record CombatResult(double baseDamageAfterResistance, double amplifyDamage, double finalPreArmorDamage,
                               ElementRuntimeState.ApplyResult applyResult, ReactionOutcome reactionOutcome,
                               ReactionPlan reactionPlan) {
    }
}
