package com.elementalphase.effect;

import com.elementalphase.api.ElementalPhaseApi;
import com.elementalphase.capability.ElementalCapabilities;
import com.elementalphase.combat.QueuedHitExecution;
import com.elementalphase.combat.ReactionExecutionBudget;
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
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ReactionActionExecutor {
    private static final double MAX_DAMAGE = 1_000_000.0D;
    private static final double MIN_AMOUNT = 0.000001D;

    public void executeOrdered(QueuedHitExecution execution, ReactionExecutionBudget budget) {
        if (execution == null || execution.target() == null || execution.target().isRemoved()) return;
        Map<String, List<LivingEntity>> targetSets = new HashMap<>();
        for (var planned : execution.plan().actions()) {
            execute(execution, planned.action(), planned.facts(), budget, targetSets);
        }
    }

    private void execute(QueuedHitExecution execution, ReactionAction action, ReactionFacts facts,
                         ReactionExecutionBudget budget, Map<String, List<LivingEntity>> targetSets) {
        if (action instanceof ReactionAction.AdditionalDamage damage) {
            double amount = damage.formula().evaluate(context(facts, execution.target(), 0.0D, 0.0D,
                    damage.settings().resistanceElement()));
            hurt(execution, execution.target(), damage.settings(), facts, amount);
        } else if (action instanceof ReactionAction.AreaDamage area) {
            Vec3 center = center(execution, area.center());
            List<LivingEntity> targets = select(execution, center, area.radius(), area.includeOriginalTarget(),
                    area.includeAttacker(), area.maxTargets(), budget);
            List<LivingEntity> accepted = area.targetSet().isPresent() ? new ArrayList<>() : null;
            for (LivingEntity target : targets) {
                double distance = Math.sqrt(target.position().distanceToSqr(center));
                ReactionFormulaContext context = context(facts, target, distance, area.radius(),
                        area.settings().resistanceElement());
                double falloff = area.falloff().evaluate(context);
                double amount = area.formula().evaluate(context) * finiteNonNegative(falloff);
                boolean hurtAccepted = hurt(execution, target, area.settings(), facts, amount);
                if (hurtAccepted && accepted != null) accepted.add(target);
            }
            if (accepted != null) area.targetSet().ifPresent(name -> targetSets.put(name, List.copyOf(accepted)));
        } else if (action instanceof ReactionAction.MobEffect effect) {
            LivingEntity target = target(execution, effect.target());
            var type = BuiltInRegistries.MOB_EFFECT.get(effect.effect());
            if (valid(target, execution.level()) && type != null) {
                target.addEffect(new MobEffectInstance(type, effect.durationTicks(), effect.amplifier(),
                        effect.ambient(), effect.visible()));
            }
        } else if (action instanceof ReactionAction.Ignite ignite) {
            LivingEntity target = target(execution, ignite.target());
            if (valid(target, execution.level()) && !target.fireImmune()) {
                target.setRemainingFireTicks(addTicks(target.getRemainingFireTicks(), ignite.durationTicks()));
            }
        } else if (action instanceof ReactionAction.ApplyFreeze freeze) {
            LivingEntity target = target(execution, freeze.target());
            if (valid(target, execution.level())) {
                ReactionRuntimeController.INSTANCE.applyFreeze(execution.level(), target,
                        freeze.durationTicks(), execution.level().getGameTime());
            }
        } else if (action instanceof ReactionAction.ScheduleDamage scheduled) {
            LivingEntity target = target(execution, scheduled.target());
            if (valid(target, execution.level())) {
                ReactionRuntimeController.INSTANCE.scheduleDamage(execution.level(), target, scheduled.id(),
                        facts.reactionId(), facts.scale(), scheduled.durationTicks(), scheduled.intervalTicks(),
                        scheduled.damage(), reactionActionSource(execution, facts), execution.level().getGameTime());
            }
        } else if (action instanceof ReactionAction.Knockback knockback) {
            LivingEntity target = target(execution, knockback.target());
            LivingEntity origin = switch (knockback.origin()) {
                case ATTACKER -> execution.attacker();
                case TARGET, REACTION -> execution.target();
            };
            if (valid(target, execution.level()) && origin != null && origin != target) {
                double strength = finiteNonNegative(knockback.strength().evaluate(context(facts, target, 0, 0)));
                double x = origin.getX() - target.getX();
                double z = origin.getZ() - target.getZ();
                if (x * x + z * z >= 1.0E-12D) target.knockback(strength, x, z);
            }
        } else if (action instanceof ReactionAction.ModifyElement modify) {
            LivingEntity target = target(execution, modify.target());
            if (valid(target, execution.level())) modifyElement(execution, target, modify, facts);
        } else if (action instanceof ReactionAction.SpreadElement spread) {
            Vec3 center = center(execution, spread.center());
            List<LivingEntity> targets = spread.sourceTargetSet().map(targetSets::get).orElse(null);
            if (targets == null) {
                targets = select(execution, center, spread.radius(), spread.includeOriginalTarget(),
                        spread.includeAttacker(), spread.maxTargets(), budget);
            } else {
                targets = reuse(execution, targets, spread.includeOriginalTarget(), spread.includeAttacker(),
                        spread.maxTargets(), budget);
            }
            ResourceLocation element = resolve(spread.element(), facts);
            for (LivingEntity target : targets) {
                double distance = Math.sqrt(target.position().distanceToSqr(center));
                double amount = spread.amount().evaluate(context(facts, target, distance, spread.radius()));
                if (Double.isFinite(amount) && amount >= MIN_AMOUNT) {
                    ElementalPhaseApi.applyTemporary(target, element, amount,
                            spread.respectAttachmentCooldown(), spreadSource(spread.element(), facts));
                }
            }
        } else if (action instanceof ReactionAction.AttachElement attach) {
            LivingEntity target = target(execution, attach.target());
            var definition = execution.snapshot().elements().get(attach.element());
            if (valid(target, execution.level()) && definition != null && definition.application().fromReaction()) {
                double amount = attach.amount().evaluate(context(facts, target, 0.0D, 0.0D));
                if (Double.isFinite(amount) && amount >= MIN_AMOUNT) {
                    int duration = attach.durationTicks().orElse(definition.attachment().durationTicks());
                    ElementSourceSnapshot source = reactionProductSource(execution, attach.element(), facts);
                    ElementalCapabilities.get(target).ifPresent(state -> {
                        state.applyTemporary(attach.element(), Math.min(amount, definition.attachment().maxAmount()),
                                execution.level().getGameTime(), duration,
                                definition.attachment().cooldownTicks(), source);
                    });
                }
            }
        }
    }

    private static ElementSourceSnapshot reactionProductSource(QueuedHitExecution execution,
                                                                ResourceLocation element, ReactionFacts facts) {
        var source = facts.source().orElse(null);
        return new ElementSourceSnapshot(
                source == null ? Optional.ofNullable(execution.attacker()).map(LivingEntity::getUUID) : source.attacker(),
                source == null ? Optional.ofNullable(execution.source().getDirectEntity()).map(entity -> entity.getUUID())
                        : source.directEntity(),
                facts.reactionId(), source == null ? execution.source().typeHolder().unwrapKey()
                        .map(net.minecraft.resources.ResourceKey::location).orElse(ReactionAction.DEFAULT_DAMAGE_TYPE)
                        : source.damageType(),
                element, source == null ? 0.0D : source.elementStrength(), execution.level().getGameTime());
    }

    private static ElementSourceSnapshot reactionActionSource(QueuedHitExecution execution, ReactionFacts facts) {
        var source = facts.source().orElse(null);
        return new ElementSourceSnapshot(
                source == null ? Optional.ofNullable(execution.attacker()).map(LivingEntity::getUUID) : source.attacker(),
                source == null ? Optional.ofNullable(execution.source().getDirectEntity()).map(entity -> entity.getUUID())
                        : source.directEntity(),
                facts.reactionId(), source == null ? execution.source().typeHolder().unwrapKey()
                        .map(ResourceKey::location).orElse(ReactionAction.DEFAULT_DAMAGE_TYPE) : source.damageType(),
                source == null ? facts.direction().trigger() : source.element(),
                source == null ? facts.elementStrength() : source.elementStrength(), execution.level().getGameTime());
    }

    private static void modifyElement(QueuedHitExecution execution, LivingEntity target,
                                      ReactionAction.ModifyElement action, ReactionFacts facts) {
        ResourceLocation element = resolve(action.element(), facts);
        double amount = action.amount().map(value -> value.evaluate(context(facts, target, 0, 0))).orElse(0.0D);
        var definition = execution.snapshot().elements().get(element);
        if (definition == null) return;
        boolean createsElement = action.operation() == ReactionAction.ElementOperation.ADD
                || action.operation() == ReactionAction.ElementOperation.SET;
        if (createsElement && !definition.application().fromReaction()) return;
        ElementalCapabilities.get(target).ifPresent(state -> {
            if (action.operation() == ReactionAction.ElementOperation.CLEAR) {
                state.remove(element);
            } else {
                state.applyEffect(element, action.operation().name().toLowerCase(java.util.Locale.ROOT), amount,
                        execution.level().getGameTime(), definition.attachment().durationTicks(),
                        definition.attachment().cooldownTicks(), definition.attachment().maxAmount(), true);
            }
        });
    }

    private static boolean hurt(QueuedHitExecution execution, LivingEntity target,
                                ReactionAction.DamageSettings settings, ReactionFacts facts, double rawAmount) {
        if (!valid(target, execution.level()) || !Double.isFinite(rawAmount) || rawAmount <= 0.0D) return false;
        double resistance = settings.resistanceElement().map(value -> resolve(value, facts))
                .map(element -> ElementalCapabilities.get(target).resolve().map(state -> state.resistance(element)).orElse(0.0D))
                .orElse(0.0D);
        float amount = (float) Math.min(MAX_DAMAGE, Math.max(0.0D, rawAmount * (1.0D - resistance)));
        if (amount <= 0.0F) return false;
        var holder = execution.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolder(ResourceKey.create(Registries.DAMAGE_TYPE, settings.damageType()));
        if (holder.isEmpty()) return false;
        DamageSource source = settings.bypassArmor()
                ? new DamageSource(holder.get(), execution.source().getDirectEntity(), execution.source().getEntity(),
                execution.source().sourcePositionRaw()) {
                    @Override
                    public boolean is(TagKey<DamageType> tag) {
                        return tag.equals(DamageTypeTags.BYPASSES_ARMOR) || super.is(tag);
                    }
                }
                : new DamageSource(holder.get(), execution.source().getDirectEntity(),
                execution.source().getEntity(), execution.source().sourcePositionRaw());
        ReactionOutcome.TriggeredReaction label = new ReactionOutcome.TriggeredReaction(facts.reactionId(), facts.scale(),
                amount, ReactionDamageDefinition.Mode.ADDITIONAL, Optional.of(settings.damageType()),
                facts.direction().display().color());
        int invulnerableTime = target.invulnerableTime;
        if (settings.bypassInvulnerability()) target.invulnerableTime = 0;
        try {
            DamageNumberCompat.record(source, execution.level().getServer().getTickCount(),
                    List.of(DamageNumberCompat.label(label)));
            return ReactionDamageContext.call(label, source, settings.allowElementApplication(), settings.allowReactions(),
                    execution.guard().child(), () -> target.hurt(source, amount));
        } finally {
            if (settings.bypassInvulnerability() && target.invulnerableTime < invulnerableTime) {
                target.invulnerableTime = invulnerableTime;
            }
        }
    }

    private static List<LivingEntity> select(QueuedHitExecution execution, Vec3 center, double radius,
                                             boolean includeOriginal, boolean includeAttacker, int maxTargets,
                                             ReactionExecutionBudget budget) {
        double radiusSquared = radius * radius;
        Comparator<LivingEntity> nearest = Comparator
                .comparingDouble((LivingEntity value) -> value.position().distanceToSqr(center))
                .thenComparingInt(LivingEntity::getId);
        List<LivingEntity> selected = new ArrayList<>();
        LivingEntity original = execution.target();
        boolean originalIncluded = includeOriginal && valid(original, execution.level())
                && original.position().distanceToSqr(center) <= radiusSquared
                && budget.claimTargetOperations(1) == 1;
        if (originalIncluded) selected.add(original);
        LivingEntity attacker = execution.attacker();
        boolean attackerIncluded = includeAttacker && attacker != original && valid(attacker, execution.level())
                && attacker.position().distanceToSqr(center) <= radiusSquared
                && budget.claimTargetOperations(1) == 1;
        int capacity = Math.max(0, maxTargets - selected.size());
        if (capacity == 0) {
            if (attackerIncluded) selected.add(attacker);
            return List.copyOf(selected);
        }
        List<LivingEntity> candidates = execution.level().getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(radius));
        java.util.PriorityQueue<LivingEntity> nearestTargets = new java.util.PriorityQueue<>(
                Math.max(1, capacity), nearest.reversed());
        for (LivingEntity candidate : candidates) {
            if (candidate == original || candidate == attacker) continue;
            if (budget.claimTargetOperations(1) == 0) break;
            if (!valid(candidate, execution.level()) || candidate.position().distanceToSqr(center) > radiusSquared) continue;
            nearestTargets.add(candidate);
            if (nearestTargets.size() > capacity) nearestTargets.poll();
        }
        List<LivingEntity> regular = new ArrayList<>(nearestTargets);
        regular.sort(nearest);
        selected.addAll(regular);
        if (attackerIncluded) selected.add(attacker);
        return List.copyOf(selected);
    }

    private static List<LivingEntity> reuse(QueuedHitExecution execution, List<LivingEntity> captured,
                                            boolean includeOriginal, boolean includeAttacker, int maxTargets,
                                            ReactionExecutionBudget budget) {
        List<LivingEntity> selected = new ArrayList<>();
        LivingEntity original = execution.target();
        if (includeOriginal && valid(original, execution.level()) && budget.claimTargetOperations(1) == 1) {
            selected.add(original);
        }
        LivingEntity attacker = execution.attacker();
        boolean appendAttacker = includeAttacker && attacker != original && valid(attacker, execution.level())
                && budget.claimTargetOperations(1) == 1;
        if (selected.size() >= maxTargets) {
            if (appendAttacker && !selected.contains(attacker)) selected.add(attacker);
            return List.copyOf(selected);
        }
        for (LivingEntity candidate : captured) {
            if (candidate == original || candidate == attacker) continue;
            if (selected.size() >= maxTargets || budget.claimTargetOperations(1) == 0) break;
            if (!valid(candidate, execution.level())) continue;
            if (!selected.contains(candidate)) selected.add(candidate);
        }
        if (appendAttacker && !selected.contains(attacker)) selected.add(attacker);
        return List.copyOf(selected);
    }

    private static ReactionFormulaContext context(ReactionFacts facts, LivingEntity target, double distance, double radius) {
        return context(facts, target, distance, radius, Optional.empty());
    }

    private static ReactionFormulaContext context(ReactionFacts facts, LivingEntity target, double distance, double radius,
                                                   Optional<ReactionAction.ElementReference> resistanceElement) {
        double health = target == null ? facts.targetHealth() : target.getHealth();
        double maxHealth = target == null ? facts.targetMaxHealth() : target.getMaxHealth();
        ResourceLocation element = resistanceElement.map(value -> resolve(value, facts)).orElse(facts.direction().trigger());
        double resistance = target == null ? facts.targetResistance() : ElementalPhaseApi.getResistance(target, element);
        return new ReactionFormulaContext(facts.originalDamage(), facts.damageBeforeReaction(), facts.scale(),
                facts.triggerAmount(), facts.auraAmount(), facts.consumedTrigger(), facts.consumedAura(),
                facts.remainingTrigger(), facts.remainingAura(), facts.attackerLevel(), facts.elementStrength(),
                health, maxHealth, maxHealth > 0 ? health / maxHealth : 0, resistance, distance, radius);
    }

    private static ResourceLocation resolve(ReactionAction.ElementReference reference, ReactionFacts facts) {
        return switch (reference.kind()) {
            case TRIGGER -> facts.direction().trigger();
            case AURA -> facts.direction().aura();
            case FIXED -> reference.fixed();
        };
    }

    private static com.elementalphase.state.ElementSourceSnapshot spreadSource(
            ReactionAction.ElementReference reference, ReactionFacts facts) {
        if (reference.kind() == ReactionAction.ElementReference.Kind.AURA) {
            for (var portion : facts.auraPortions()) {
                var source = portion.temporarySource().or(() -> portion.permanentSource());
                if (source.isPresent()) return source.orElseThrow();
            }
        }
        return facts.source().orElse(null);
    }

    private static LivingEntity target(QueuedHitExecution execution, ReactionAction.Target target) {
        return target == ReactionAction.Target.TARGET ? execution.target() : execution.attacker();
    }

    private static Vec3 center(QueuedHitExecution execution, ReactionAction.Center center) {
        return center == ReactionAction.Center.ATTACKER && execution.attacker() != null
                ? execution.attacker().position() : execution.target().position();
    }

    private static boolean valid(LivingEntity entity, ServerLevel level) {
        return entity != null && entity.level() == level && entity.isAlive() && !entity.isRemoved();
    }

    private static double finiteNonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0.0D, value) : 0.0D;
    }

    private static int addTicks(int current, int addition) {
        return (int) Math.min(Integer.MAX_VALUE, (long) Math.max(0, current) + Math.max(0, addition));
    }
}
