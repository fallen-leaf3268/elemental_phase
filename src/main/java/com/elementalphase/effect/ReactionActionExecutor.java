package com.elementalphase.effect;

import com.elementalphase.api.ElementalPhaseApi;
import com.elementalphase.capability.ElementalCapabilities;
import com.elementalphase.combat.QueuedHitExecution;
import com.elementalphase.combat.ReactionExecutionBudget;
import com.elementalphase.combat.ResistancePolicy;
import com.elementalphase.config.ElementalPhaseServerConfig;
import com.elementalphase.event.CommonEvents;
import com.elementalphase.reaction.ReactionEngine;
import com.elementalphase.reaction.ReactionRequest;
import com.elementalphase.data.model.ReactionAction;
import com.elementalphase.data.model.ReactionDamageDefinition;
import com.elementalphase.display.ReactionDamageContext;
import com.elementalphase.integration.damagenumber.DamageNumberCompat;
import com.elementalphase.reaction.ReactionFacts;
import com.elementalphase.reaction.ReactionOutcome;
import com.elementalphase.reaction.runtime.ReactionRuntimeController;
import com.elementalphase.reaction.formula.ReactionFormulaContext;
import com.elementalphase.state.ElementSourceSnapshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class ReactionActionExecutor {
    private static final double MIN_AMOUNT = 0.000001D;
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    static double effectiveRadius(double computed, double configuredMax) {
        return !Double.isFinite(computed) || computed <= 0 ? 0 : Math.min(computed, configuredMax);
    }

    static int effectiveTargetLimit(Optional<Integer> requested, int configuredMax) {
        return Math.min(requested.orElse(configuredMax), configuredMax);
    }

    static void runAreaParts(boolean primaryTarget, Runnable damage,
                             java.util.function.BooleanSupplier validAfterDamage, Runnable attachment) {
        damage.run();
        if (!primaryTarget && validAfterDamage.getAsBoolean()) attachment.run();
    }

    static <T> List<T> selectCandidates(T primary, T attacker, Iterable<T> candidates,
            java.util.function.Predicate<T> valid, java.util.function.ToDoubleFunction<T> distanceSquared,
            java.util.function.ToIntFunction<T> stableId, double radiusSquared, int limit,
            boolean includeTarget, boolean includeAttacker, ReactionExecutionBudget budget) {
        if (limit <= 0 || budget.targetOperationsRemaining() == 0) return List.of();
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<T, Boolean>());
        var eligible = new ArrayList<T>();
        var iterator = candidates.iterator();
        boolean primaryPending = includeTarget && primary != null;
        while (primaryPending || iterator.hasNext()) {
            if (budget.targetOperationsRemaining() == 0) break;
            T candidate;
            if (primaryPending) {
                candidate = primary;
                primaryPending = false;
            } else {
                candidate = iterator.next();
            }
            if (candidate == null || candidate == primary && !includeTarget || !seen.add(candidate)
                    || candidate == attacker && candidate != primary && !includeAttacker) continue;
            if (budget.claimTargetOperations(1) != 1) break;
            if (!valid.test(candidate)) continue;
            double distance = distanceSquared.applyAsDouble(candidate);
            if (!Double.isFinite(distance) || distance > radiusSquared) continue;
            eligible.add(candidate);
        }
        eligible.sort(Comparator.<T>comparingInt(candidate -> candidate == primary ? 0 : 1)
                .thenComparingDouble(distanceSquared).thenComparingInt(stableId));
        return List.copyOf(eligible.subList(0, Math.min(limit, eligible.size())));
    }

    public void executeOrdered(QueuedHitExecution execution, ReactionExecutionBudget budget) {
        if (execution == null || !execution.isCurrent() || execution.target() == null || execution.target().isRemoved()) return;
        for (var planned : execution.plan().actions()) {
            if (!execution.isCurrent()) return;
            execute(execution, planned.action(), planned.facts(), planned.stateSnapshot(), budget);
        }
    }

    private void execute(QueuedHitExecution execution, ReactionAction action, ReactionFacts facts,
                         Optional<ReactionAction.StateSnapshot> stateSnapshot, ReactionExecutionBudget budget) {
        if (action instanceof ReactionAction.AdditionalDamage damage) {
            double amount = damage.formula().evaluate(context(facts, execution.target(), 0.0D, 0.0D,
                    damage.settings().resistanceElement()));
            hurt(execution, execution.target(), damage.settings(), facts, amount);
        } else if (action instanceof ReactionAction.Area area) {
            Vec3 center = execution.target().position();
            double computed = area.radius().evaluate(context(facts, execution.target(), 0, 0));
            if (!Double.isFinite(computed)) {
                LOGGER.error("Skipping non-finite area radius for reaction {}", facts.reactionId());
                return;
            }
            double radius = effectiveRadius(computed, ElementalPhaseServerConfig.MAX_RADIUS.get());
            if (radius <= 0) return;
            int limit = effectiveTargetLimit(area.maxTargets(), ElementalPhaseServerConfig.MAX_TARGETS.get());
            boolean includeTarget = area.damage().map(ReactionAction.AreaDamageValue::includeTarget).orElse(false);
            List<LivingEntity> targets = select(execution, center, radius, includeTarget, area.includeAttacker(), limit, budget);
            for (LivingEntity target : targets) {
                if (!execution.isCurrent()) return;
                double distance = Math.sqrt(target.position().distanceToSqr(center));
                runAreaParts(target == execution.target(), () -> area.damage().ifPresent(damage -> {
                    double amount = damage.formula().evaluate(context(facts, target, distance, radius,
                            damage.settings().resistanceElement()));
                    hurt(execution, target, damage.settings(), facts, amount);
                }), () -> execution.isCurrent() && valid(target, execution.level()), () -> area.attachment().ifPresent(attachment -> {
                    double amount = attachment.amount().evaluate(context(facts, target, distance, radius));
                    applyExplicitAttachment(execution, target, resolve(attachment.element(), facts), amount, facts, budget);
                }));
            }
        } else if (action instanceof ReactionAction.MobEffect effect) {
            LivingEntity target = execution.target();
            var type = BuiltInRegistries.MOB_EFFECT.get(effect.effect());
            if (valid(target, execution.level()) && type != null) {
                target.addEffect(new MobEffectInstance(type, effect.durationTicks(), effect.level(), false, true, true));
            }
        } else if (action instanceof ReactionAction.Special special) {
            LivingEntity target = execution.target();
            if (valid(target, execution.level())) {
                for (var entry : special.entries()) {
                    if (!execution.isCurrent()) return;
                    if (entry.type() == ReactionAction.SpecialEntry.Kind.IGNITE) {
                        if (!target.fireImmune() && entry.durationTicks() >= Math.max(0, target.getRemainingFireTicks())) {
                            target.setRemainingFireTicks(entry.durationTicks());
                        }
                    } else {
                        ReactionRuntimeController.INSTANCE.applyFreeze(execution.level(), target,
                                entry.durationTicks(), execution.level().getGameTime());
                    }
                }
            }
        } else if (action instanceof ReactionAction.ScheduleDamage scheduled) {
            LivingEntity target = execution.target();
            if (valid(target, execution.level()) && stateSnapshot.isPresent()) {
                ReactionRuntimeController.INSTANCE.scheduleDamage(execution.level(), target, scheduled.id(),
                        facts.reactionId(), facts.scale(), scheduled.durationTicks(), scheduled.intervalTicks(),
                        scheduled.damage(), reactionActionSource(execution, facts), stateSnapshot.orElseThrow(),
                        execution.level().getGameTime());
            }
        } else if (action instanceof ReactionAction.ModifyElement modify) {
            LivingEntity target = target(execution, modify.target());
            if (valid(target, execution.level())) modifyElement(execution, target, modify, facts);
        } else if (action instanceof ReactionAction.AttachElement attach) {
            double amount = attach.amount().evaluate(context(facts, execution.target(), 0, 0));
            applyExplicitAttachment(execution, execution.target(), attach.element(), amount, facts, budget);
        }
    }

    private void applyExplicitAttachment(QueuedHitExecution execution, LivingEntity target, ResourceLocation element,
                                         double amount, ReactionFacts parentFacts, ReactionExecutionBudget budget) {
        if (!execution.isCurrent()) return;
        var definition = execution.snapshot().elements().get(element);
        if (!valid(target, execution.level()) || definition == null || !Double.isFinite(amount) || amount < MIN_AMOUNT) return;
        double incoming = Math.min(amount, definition.attachment().maxAmount());
        long now = execution.level().getGameTime();
        var source = reactionProductSource(execution, element, parentFacts);
        var attacker = valid(execution.attacker(), execution.level()) ? execution.attacker() : null;
        ElementalCapabilities.get(target).ifPresent(state -> {
            if (!execution.isCurrent()) return;
            var admission = state.tryTriggerVirtual(element, incoming, now, definition.attachment().cooldownTicks());
            var current = state.state(element);
            if (!admission.changed() || definition.attachment().virtual() && current != null
                    && incoming < current.effectiveAmount(now)) return;
            var guard = execution.guard().child();
            var plan = new ReactionEngine().react(new ReactionRequest(state, element, incoming, now, definition,
                    execution.snapshot().reactionIndex(), execution.snapshot().elements(), 0, parentFacts.attackerLevel(),
                    source.elementStrength(), target.getHealth(), target.getMaxHealth(), state.resistance(element),
                    Optional.of(source), condition -> CommonEvents.conditionMatches(condition, execution.source(), attacker, target, 0),
                    guard, false));
            if (plan.applyResult().changed()) state.startElementApplicationCooldown(element, now, definition.attachment().cooldownTicks());
            if (!plan.actions().isEmpty()) executeOrdered(new QueuedHitExecution(execution.level(), attacker, target,
                    execution.source(), plan, execution.snapshot(), now, guard), budget);
        });
    }

    private static ElementSourceSnapshot reactionProductSource(QueuedHitExecution execution,
                                                                ResourceLocation element, ReactionFacts facts) {
        var source = facts.source().orElse(null);
        var attacker = source == null
                ? (execution.attacker() != null ? execution.attacker() : execution.source().getEntity()) : null;
        return new ElementSourceSnapshot(
                source == null ? Optional.ofNullable(attacker).map(Entity::getUUID) : source.attacker(),
                source == null ? Optional.ofNullable(execution.source().getDirectEntity()).map(entity -> entity.getUUID())
                        : source.directEntity(),
                facts.reactionId(), source == null ? execution.source().typeHolder().unwrapKey()
                        .map(net.minecraft.resources.ResourceKey::location).orElse(ReactionAction.DEFAULT_DAMAGE_TYPE)
                        : source.damageType(),
                element, source == null ? facts.elementStrength() : source.elementStrength(), execution.level().getGameTime(),
                source == null ? (attacker instanceof Player player ? Optional.of(player.getScoreboardName()) : Optional.empty())
                        : source.playerName());
    }

    private static ElementSourceSnapshot reactionActionSource(QueuedHitExecution execution, ReactionFacts facts) {
        var source = facts.source().orElse(null);
        var attacker = source == null
                ? (execution.attacker() != null ? execution.attacker() : execution.source().getEntity()) : null;
        return new ElementSourceSnapshot(
                source == null ? Optional.ofNullable(attacker).map(Entity::getUUID) : source.attacker(),
                source == null ? Optional.ofNullable(execution.source().getDirectEntity()).map(entity -> entity.getUUID())
                        : source.directEntity(),
                facts.reactionId(), source == null ? execution.source().typeHolder().unwrapKey()
                        .map(ResourceKey::location).orElse(ReactionAction.DEFAULT_DAMAGE_TYPE) : source.damageType(),
                source == null ? facts.direction().trigger() : source.element(),
                source == null ? facts.elementStrength() : source.elementStrength(), execution.level().getGameTime(),
                source == null ? (attacker instanceof Player player ? Optional.of(player.getScoreboardName()) : Optional.empty())
                        : source.playerName());
    }

    private static void modifyElement(QueuedHitExecution execution, LivingEntity target,
                                      ReactionAction.ModifyElement action, ReactionFacts facts) {
        ResourceLocation element = resolve(action.element(), facts);
        double amount = action.amount().map(value -> value.evaluate(context(facts, target, 0, 0))).orElse(0.0D);
        var definition = execution.snapshot().elements().get(element);
        if (!execution.isCurrent() || definition == null) return;
        boolean createsElement = action.operation() == ReactionAction.ElementOperation.ADD
                || action.operation() == ReactionAction.ElementOperation.SET;
        ElementalCapabilities.get(target).ifPresent(state -> {
            if (!execution.isCurrent()) return;
            if (action.operation() == ReactionAction.ElementOperation.CLEAR) {
                state.remove(element);
            } else {
                if (createsElement && definition.attachment().virtual()) {
                    state.applyElement(definition, amount, execution.level().getGameTime(),
                            definition.attachment().durationTicks(), true, reactionProductSource(execution, element, facts));
                    return;
                }
                state.applyEffect(element, action.operation().name().toLowerCase(java.util.Locale.ROOT), amount,
                        execution.level().getGameTime(), definition.attachment().durationTicks(),
                        definition.attachment().cooldownTicks(), definition.attachment().maxAmount(), true);
            }
        });
    }

    private static DamageOutcome hurt(QueuedHitExecution execution, LivingEntity target,
                                ReactionAction.DamageSettings settings, ReactionFacts facts, double rawAmount) {
        if (!execution.isCurrent() || !valid(target, execution.level()) || !Double.isFinite(rawAmount) || rawAmount <= 0.0D) {
            return DamageOutcome.REJECTED;
        }
        double resistance = settings.resistanceElement().map(value -> resolve(value, facts))
                .map(element -> ElementalPhaseApi.getResistance(target, element)).orElse(0.0D);
        double reactionResistance = ElementalPhaseApi.getReactionResistance(target, facts.reactionId());
        float amount = (float) ResistancePolicy.apply(rawAmount, resistance, reactionResistance);
        var holder = execution.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolder(ResourceKey.create(Registries.DAMAGE_TYPE, settings.damageType()));
        if (holder.isEmpty()) return DamageOutcome.REJECTED;
        DamageSource source = new DamageSource(holder.get(), execution.source().getDirectEntity(),
                execution.source().getEntity(), execution.source().sourcePositionRaw());
        if (amount <= 0.0F) {
            return DamageOutcome.REJECTED;
        }
        ReactionOutcome.TriggeredReaction label = new ReactionOutcome.TriggeredReaction(facts.reactionId(), facts.scale(),
                amount, ReactionDamageDefinition.Mode.ADDITIONAL, Optional.of(settings.damageType()),
                facts.direction().display().color().resolve(facts.direction().trigger(), facts.direction().aura(),
                        execution.snapshot().elements()), Optional.empty(), facts.reactionId(), facts.direction().display().showReaction());
        DamageNumberCompat.record(source, execution.level().getServer().getTickCount(),
                DamageNumberCompat.appearance(label));
        return ReactionDamageContext.call(label, source, false, false,
                execution.guard().child(), () -> target.hurt(source, amount))
                ? DamageOutcome.ACCEPTED : DamageOutcome.REJECTED;
    }

    enum DamageOutcome {
        REJECTED, ACCEPTED
    }

    private static List<LivingEntity> select(QueuedHitExecution execution, Vec3 center, double radius,
                                             boolean includeTarget, boolean includeAttacker,
                                             int maxTargets, ReactionExecutionBudget budget) {
        if (!execution.isCurrent() || maxTargets <= 0 || budget.targetOperationsRemaining() == 0) return List.of();
        List<LivingEntity> candidates = new ArrayList<>(execution.level().getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(radius)));
        if (includeAttacker && execution.attacker() != null) candidates.add(execution.attacker());
        return selectCandidates(execution.target(), execution.attacker(), candidates,
                entity -> valid(entity, execution.level()), entity -> entity.position().distanceToSqr(center),
                LivingEntity::getId, radius * radius, maxTargets, includeTarget, includeAttacker, budget);
    }

    private static ReactionFormulaContext context(ReactionFacts facts, LivingEntity target, double distance, double radius) {
        return context(facts, target, distance, radius, Optional.of(ReactionAction.ElementReference.trigger()));
    }

    private static ReactionFormulaContext context(ReactionFacts facts, LivingEntity target, double distance, double radius,
                                                   Optional<ReactionAction.ElementReference> resistanceElement) {
        double health = target == null ? facts.targetHealth() : target.getHealth();
        double maxHealth = target == null ? facts.targetMaxHealth() : target.getMaxHealth();
        double resistance = resistanceElement.map(value -> resolve(value, facts))
                .map(element -> target == null ? facts.targetResistance() : ElementalPhaseApi.getResistance(target, element))
                .orElse(0.0D);
        return new ReactionFormulaContext(facts.originalDamage(), facts.damageBeforeReaction(), facts.scale(),
                facts.triggerAmount(), facts.auraAmount(), facts.consumedTrigger(), facts.consumedAura(),
                facts.remainingTrigger(), facts.remainingAura(), facts.attackerLevel(), facts.elementStrength(),
                health, maxHealth, maxHealth > 0 ? health / maxHealth : 0, resistance, distance, radius);
    }

    private static ResourceLocation resolve(ReactionAction.ElementReference reference, ReactionFacts facts) {
        return reference.resolve(facts.direction().trigger(), facts.direction().aura());
    }

    private static LivingEntity target(QueuedHitExecution execution, ReactionAction.Target target) {
        return target == ReactionAction.Target.TARGET ? execution.target() : execution.attacker();
    }

    private static boolean valid(LivingEntity entity, ServerLevel level) {
        return entity != null && entity.level() == level && entity.isAlive() && !entity.isRemoved();
    }

}
