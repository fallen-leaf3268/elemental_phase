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
import com.elementalphase.state.ApplicationCooldownKey;
import com.elementalphase.state.ElementSourceSnapshot;
import com.elementalphase.state.ElementalState;

import java.util.Optional;
import java.util.function.Predicate;

public final class CombatPipeline {
    private static final double MAX_DAMAGE = 1_000_000.0D;

    private final ReactionEngine reactionEngine = new ReactionEngine();

    public CombatResult resolve(CombatInput input) {
        double originalDamage = Double.isFinite(input.originalDamage()) ? Math.max(0.0D, input.originalDamage()) : 0.0D;
        double resistance = Double.isFinite(input.resistance()) ? Math.max(-1.0D, Math.min(1.0D, input.resistance())) : 0.0D;
        double baseDamage = Math.min(MAX_DAMAGE, originalDamage * (1.0D - resistance));
        ElementRuntimeState.ApplyResult applyResult;
        ReactionOutcome outcome = ReactionOutcome.empty();
        ReactionPlan plan = ReactionPlan.empty(baseDamage, ElementRuntimeState.ApplyResult.NO_AMOUNT);
        if (input.attack() == null || input.elementDefinition() == null
                || !input.elementDefinition().application().fromAttack() || input.attack().mountAmount() <= 0.0D) {
            applyResult = ElementRuntimeState.ApplyResult.NO_AMOUNT;
        } else {
            double incoming = Math.min(input.attack().mountAmount(),
                    input.elementDefinition().attachment().maxAmount());
            var application = input.attack().application();
            boolean groupBlocked = application.isPresent() && input.applicationCooldownKey() != null
                    && !input.targetState().applicationCooldownReady(input.applicationCooldownKey(), input.gameTime());
            if (groupBlocked) {
                applyResult = ElementRuntimeState.ApplyResult.BLOCKED_COOLDOWN;
            } else {
                applyResult = input.targetState().tryTriggerVirtual(input.attack().element(), incoming,
                        input.gameTime(), input.elementDefinition().attachment().cooldownTicks());
            }
            if (applyResult.changed()) {
                plan = reactionEngine.react(new ReactionRequest(input.targetState(), input.attack().element(),
                        incoming, input.gameTime(), input.elementDefinition(), input.reactionIndex(),
                        input.elements(),
                        baseDamage, input.attackerLevel(), input.attack().elementStrength(), input.targetHealth(),
                        input.targetMaxHealth(), resistance, Optional.ofNullable(input.source()), input.conditionEvaluator(),
                        input.guard()));
                applyResult = plan.applyResult();
                if (applyResult.changed()) {
                    input.targetState().startElementApplicationCooldown(input.attack().element(), input.gameTime(),
                            input.elementDefinition().attachment().cooldownTicks());
                    if (application.isPresent() && input.applicationCooldownKey() != null) {
                        input.targetState().startApplicationCooldown(input.applicationCooldownKey(), input.gameTime(),
                                application.orElseThrow().cooldownTicks());
                    }
                }
                outcome = new ReactionOutcome(plan.mainDamageBonus(), java.util.List.of(), plan.labels().stream()
                        .map(label -> new ReactionOutcome.TriggeredReaction(label.reactionId(), label.scale(), 0.0D,
                                ReactionDamageDefinition.Mode.AMPLIFY, Optional.empty(), label.color()))
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
                              ApplicationCooldownKey applicationCooldownKey, double attackerLevel,
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
                           ReactionIndex reactionIndex, ElementSourceSnapshot source,
                           ApplicationCooldownKey applicationCooldownKey) {
            this(originalDamage, resistance, attack, targetState, gameTime, elementDefinition, reactionIndex,
                    java.util.Map.of(), source,
                    applicationCooldownKey, 0.0D, 0.0D, 0.0D, ignored -> true, new ReactionChainGuard());
        }
    }

    public record CombatResult(double baseDamageAfterResistance, double amplifyDamage, double finalPreArmorDamage,
                               ElementRuntimeState.ApplyResult applyResult, ReactionOutcome reactionOutcome,
                               ReactionPlan reactionPlan) {
    }
}
