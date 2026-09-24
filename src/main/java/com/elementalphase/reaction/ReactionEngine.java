package com.elementalphase.reaction;

import com.elementalphase.data.ReactionKey;
import com.elementalphase.data.ReactionIndex;
import com.elementalphase.data.model.ReactionAction;
import com.elementalphase.data.model.ReactionDamageDefinition;
import com.elementalphase.data.model.ReactionDefinition;
import com.elementalphase.data.model.ReactionEffectDefinition;
import com.elementalphase.state.ElementRuntimeState;
import com.elementalphase.state.AuraHandle;
import com.elementalphase.state.ElementPortion;
import com.elementalphase.state.ElementalState;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Comparator;
import com.elementalphase.reaction.formula.ReactionFormulaContext;

public final class ReactionEngine {
    private static final double EPSILON = 0.000001D;
    private static final double GLOBAL_MINIMUM_SCALE = 0.1D;
    private static final double MAX_DAMAGE = 1_000_000.0D;

    public ReactionPlan react(ReactionRequest request) {
        if (!Double.isFinite(request.triggerAmount()) || request.triggerAmount() < EPSILON) {
            return ReactionPlan.empty(clampDamage(request.originalDamage()), ElementRuntimeState.ApplyResult.NO_AMOUNT);
        }
        double remainingTrigger = Math.min(MAX_DAMAGE, request.triggerAmount());
        double currentDamage = clampDamage(request.originalDamage());
        List<ReactionPlan.Label> labels = new ArrayList<>();
        List<ReactionPlan.PlannedAction> actions = new ArrayList<>();
        List<PendingAction> pendingActions = new ArrayList<>();
        boolean reacted = false;

        while (remainingTrigger >= EPSILON) {
            List<DirectionalCandidate> candidates = new ArrayList<>();
            for (AuraHandle aura : request.state().auraHandles(request.gameTime(), request.elements())) {
                if (aura.element().equals(request.trigger()) || aura.amount() < EPSILON) continue;
                for (ReactionIndex.Candidate candidate : request.index().candidates(request.trigger(), aura.element())) {
                    if (conditionsPass(candidate, request)) {
                        double scale = scale(remainingTrigger, aura.amount(), candidate.direction().consumption().trigger(),
                                candidate.direction().consumption().aura());
                        if (scale >= Math.max(GLOBAL_MINIMUM_SCALE, candidate.direction().minimumScale())) {
                            candidates.add(new DirectionalCandidate(candidate, aura, scale));
                        }
                    }
                }
            }
            candidates.sort(Comparator
                    .comparingInt((DirectionalCandidate value) -> value.candidate().direction().priority()).reversed()
                    .thenComparingLong(value -> value.aura().order())
                    .thenComparing(value -> value.candidate().reaction().id().toString())
                    .thenComparing(value -> value.aura().element().toString()));
            if (candidates.isEmpty() || !request.guard().tryReaction()) break;

            DirectionalCandidate selected = candidates.get(0);
            var direction = selected.candidate().direction();
            double triggerBefore = remainingTrigger;
            double auraBefore = selected.aura().amount();
            double consumedTrigger = selected.scale() * direction.consumption().trigger();
            double consumedAura = selected.scale() * direction.consumption().aura();
            remainingTrigger = Math.max(0.0D, remainingTrigger - consumedTrigger);
            List<ElementPortion> triggerPortions = List.of(new ElementPortion(ElementPortion.Origin.TRIGGER_POOL,
                    consumedTrigger, request.source(), Optional.empty(), Long.MIN_VALUE));
            List<ElementPortion> auraPortions = selected.aura().consume(consumedAura);
            if (remainingTrigger >= EPSILON && remainingTrigger < GLOBAL_MINIMUM_SCALE) remainingTrigger = 0.0D;
            double auraRemaining = selected.aura().amount();
            if (auraRemaining >= EPSILON && auraRemaining < GLOBAL_MINIMUM_SCALE) {
                selected.aura().consume(auraRemaining);
                auraRemaining = 0.0D;
            }

            ReactionFacts facts = new ReactionFacts(selected.candidate().reaction().id(), direction,
                    request.originalDamage(), currentDamage, triggerBefore, auraBefore, selected.scale(),
                    consumedTrigger, consumedAura, remainingTrigger, auraRemaining,
                    request.attackerLevel(), request.elementStrength(), request.targetHealth(),
                    request.targetMaxHealth(), request.targetResistance(), request.source(), triggerPortions, auraPortions);
            labels.add(new ReactionPlan.Label(facts.reactionId(), facts.scale(), direction.display().color(),
                    direction.display().showReaction(), facts));
            for (ReactionAction action : direction.actions()) {
                ReactionFormulaContext context = formulaContext(facts, currentDamage);
                if (action instanceof ReactionAction.MainDamageBonus bonus) {
                    double when = action.when().evaluate(context);
                    if (!Double.isFinite(when) || when == 0.0D) continue;
                    double value = bonus.formula().evaluate(context);
                    if (Double.isFinite(value) && value > 0.0D) currentDamage = clampDamage(currentDamage + value);
                } else {
                    pendingActions.add(new PendingAction(action, facts));
                }
            }
            reacted = true;
        }

        for (PendingAction pending : pendingActions) {
            ReactionFacts facts = withCurrentDamage(pending.facts(), currentDamage);
            double when = pending.action().when().evaluate(formulaContext(facts, currentDamage));
            if (Double.isFinite(when) && when != 0.0D) {
                actions.add(new ReactionPlan.PlannedAction(pending.action(), facts));
            }
        }

        ElementRuntimeState.ApplyResult applyResult = ElementRuntimeState.ApplyResult.APPLIED;
        if (request.elementDefinition().attachment().retainAfterAttack() && remainingTrigger >= EPSILON) {
            applyResult = request.state().applyTemporaryIgnoringCooldown(request.trigger(), remainingTrigger,
                    request.gameTime(), request.elementDefinition().attachment().durationTicks(),
                    request.source().orElse(null));
            if (reacted && !applyResult.changed()) applyResult = ElementRuntimeState.ApplyResult.APPLIED;
        }
        return new ReactionPlan(currentDamage - clampDamage(request.originalDamage()), currentDamage,
                applyResult, labels, actions);
    }

    private static boolean conditionsPass(ReactionIndex.Candidate candidate, ReactionRequest request) {
        return candidate.direction().conditions().stream().allMatch(request.conditionEvaluator());
    }

    private static ReactionFormulaContext formulaContext(ReactionFacts facts, double currentDamage) {
        double maxHealth = Math.max(0.0D, facts.targetMaxHealth());
        double ratio = maxHealth > 0.0D ? facts.targetHealth() / maxHealth : 0.0D;
        return new ReactionFormulaContext(facts.originalDamage(), currentDamage, facts.scale(), facts.triggerAmount(),
                facts.auraAmount(), facts.consumedTrigger(), facts.consumedAura(), facts.remainingTrigger(),
                facts.remainingAura(), facts.attackerLevel(), facts.elementStrength(), facts.targetHealth(), maxHealth,
                ratio, facts.targetResistance(), 0.0D, 0.0D);
    }

    private static ReactionFacts withCurrentDamage(ReactionFacts facts, double currentDamage) {
        return new ReactionFacts(facts.reactionId(), facts.direction(), facts.originalDamage(), currentDamage,
                facts.triggerAmount(), facts.auraAmount(), facts.scale(), facts.consumedTrigger(), facts.consumedAura(),
                facts.remainingTrigger(), facts.remainingAura(), facts.attackerLevel(), facts.elementStrength(),
                facts.targetHealth(), facts.targetMaxHealth(), facts.targetResistance(), facts.source(),
                facts.triggerPortions(), facts.auraPortions());
    }

    private static double clampDamage(double value) {
        return Double.isFinite(value) ? Math.min(MAX_DAMAGE, Math.max(0.0D, value)) : 0.0D;
    }

    private record DirectionalCandidate(ReactionIndex.Candidate candidate, AuraHandle aura, double scale) {
    }

    private record PendingAction(ReactionAction action, ReactionFacts facts) {
    }

    public ReactionOutcome react(ElementalState state, ResourceLocation incoming, long now,
                                 Map<ReactionKey, ReactionDefinition> reactions, double originalDamage) {
        ElementRuntimeState incomingState = state.state(incoming);
        if (incomingState == null || incomingState.effectiveAmount(now) < EPSILON) {
            return ReactionOutcome.empty();
        }
        return react(state, incoming, now, reactions, originalDamage, new StateIncomingPool(incomingState, now));
    }

    public ReactionOutcome reactVirtual(ElementalState state, ResourceLocation incoming, double amount, long now,
                                        Map<ReactionKey, ReactionDefinition> reactions, double originalDamage) {
        if (state == null || incoming == null || reactions == null || !Double.isFinite(amount)
                || amount < EPSILON || amount > MAX_DAMAGE) {
            return ReactionOutcome.empty();
        }
        return react(state, incoming, now, reactions, originalDamage, new VirtualIncomingPool(amount));
    }

    private ReactionOutcome react(ElementalState state, ResourceLocation incoming, long now,
                                  Map<ReactionKey, ReactionDefinition> reactions, double originalDamage,
                                  IncomingPool incomingPool) {

        List<ResourceLocation> candidates = new ArrayList<>(state.orderedActiveElements(now));
        candidates.remove(incoming);
        List<ReactionOutcome.TriggeredEffect> effects = new ArrayList<>();
        List<ReactionOutcome.TriggeredReaction> triggered = new ArrayList<>();
        double amplifyDamage = 0.0D;

        for (ResourceLocation existing : candidates) {
            double incomingAmount = incomingPool.amount();
            if (incomingAmount < EPSILON) {
                break;
            }

            ElementRuntimeState existingState = state.state(existing);
            if (existingState == null) {
                continue;
            }
            double existingAmount = existingState.effectiveAmount(now);
            if (existingAmount < EPSILON) {
                continue;
            }

            ReactionDefinition definition = reactions.get(ReactionKey.of(incoming, existing));
            if (definition == null) {
                continue;
            }

            double incomingRatio = ratioFor(definition, incoming);
            double existingRatio = ratioFor(definition, existing);
            if (!validRatio(incomingRatio) || !validRatio(existingRatio)) {
                continue;
            }
            double scale = scale(incomingAmount, existingAmount, incomingRatio, existingRatio);
            if (scale < Math.max(GLOBAL_MINIMUM_SCALE, definition.minimumScale())) {
                continue;
            }

            incomingPool.consume(scale * incomingRatio);
            existingState.consume(scale * existingRatio, now);
            incomingPool.clearRemainderBelow(GLOBAL_MINIMUM_SCALE);
            clearRemainderBelow(existingState, now, GLOBAL_MINIMUM_SCALE);
            ReactionDamageDefinition damageDefinition = definition.damage();
            double evaluated = damageDefinition.formula().evaluate(originalDamage, scale);
            double reactionDamage = Double.isFinite(evaluated)
                    ? Math.min(MAX_DAMAGE, Math.max(0.0D, evaluated))
                    : 0.0D;
            if (damageDefinition.mode() == ReactionDamageDefinition.Mode.AMPLIFY) {
                amplifyDamage += reactionDamage;
            }
            Optional<ReactionOutcome.TriggeredArea> area = damageDefinition.area().map(definitionArea ->
                    new ReactionOutcome.TriggeredArea(
                            definitionArea.radius(),
                            definitionArea.spreadElement(),
                            scale * ratioFor(definition, definitionArea.spreadElement())
                                    * definitionArea.attachmentRatio(),
                            definitionArea.attachAttacker()));
            triggered.add(new ReactionOutcome.TriggeredReaction(
                    definition.id(), scale, reactionDamage, damageDefinition.mode(), damageDefinition.damageType(),
                    definition.displayColor(), area));
            for (ReactionEffectDefinition effect : definition.effects()) {
                effects.add(new ReactionOutcome.TriggeredEffect(effect, scale));
            }
        }

        return new ReactionOutcome(amplifyDamage, effects, triggered);
    }

    private interface IncomingPool {
        double amount();

        void consume(double requested);

        void clearRemainderBelow(double threshold);
    }

    private static final class StateIncomingPool implements IncomingPool {
        private final ElementRuntimeState state;
        private final long now;

        private StateIncomingPool(ElementRuntimeState state, long now) {
            this.state = state;
            this.now = now;
        }

        @Override
        public double amount() {
            return state.effectiveAmount(now);
        }

        @Override
        public void consume(double requested) {
            state.consume(requested, now);
        }

        @Override
        public void clearRemainderBelow(double threshold) {
            ReactionEngine.clearRemainderBelow(state, now, threshold);
        }
    }

    private static final class VirtualIncomingPool implements IncomingPool {
        private double remaining;

        private VirtualIncomingPool(double amount) {
            remaining = amount;
        }

        @Override
        public double amount() {
            return remaining;
        }

        @Override
        public void consume(double requested) {
            remaining = Math.max(0.0D, remaining - requested);
        }

        @Override
        public void clearRemainderBelow(double threshold) {
            if (remaining >= EPSILON && remaining < threshold) {
                remaining = 0.0D;
            }
        }
    }

    private static void clearRemainderBelow(ElementRuntimeState state, long now, double threshold) {
        double remaining = state.effectiveAmount(now);
        if (remaining >= EPSILON && remaining < threshold) {
            state.consume(remaining, now);
        }
    }

    public static double scale(double amountA, double amountB, double ratioA, double ratioB) {
        if (!Double.isFinite(amountA) || !Double.isFinite(amountB) || !validRatio(ratioA) || !validRatio(ratioB)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(amountA / ratioA, amountB / ratioB));
    }

    private static double ratioFor(ReactionDefinition definition, ResourceLocation element) {
        if (definition.elementA().equals(element)) {
            return definition.ratioA();
        }
        if (definition.elementB().equals(element)) {
            return definition.ratioB();
        }
        return 0.0D;
    }

    private static boolean validRatio(double value) {
        return Double.isFinite(value) && value >= EPSILON;
    }
}
