package com.elementalphase.effect;

import com.elementalphase.capability.ElementalCapabilities;
import com.elementalphase.data.ElementDataSnapshot;
import com.elementalphase.data.model.ReactionEffectDefinition;
import com.elementalphase.reaction.ReactionOutcome;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

public final class ReactionEffectExecutor {
    public void execute(ServerLevel level, LivingEntity attacker, LivingEntity target, ReactionOutcome outcome,
                        ElementDataSnapshot snapshot) {
        for (ReactionOutcome.TriggeredEffect triggered : outcome.effects()) {
            ReactionEffectDefinition effect = triggered.definition();
            LivingEntity recipient = effect.target() == ReactionEffectDefinition.EffectTarget.TARGET ? target : attacker;
            if (recipient == null || !recipient.isAlive()) {
                continue;
            }
            switch (effect.type()) {
                case MOB_EFFECT -> {
                    var mobEffect = BuiltInRegistries.MOB_EFFECT.get(effect.effectId());
                    if (mobEffect != null) {
                        recipient.addEffect(new MobEffectInstance(mobEffect, effect.durationTicks(), effect.amplifier()));
                    }
                }
                case IGNITE -> {
                    if (!recipient.fireImmune()) {
                        recipient.setRemainingFireTicks(addTicks(recipient.getRemainingFireTicks(), effect.durationTicks()));
                    }
                }
                case FREEZE -> {
                    if (recipient.canFreeze()) {
                        recipient.setTicksFrozen(addTicks(recipient.getTicksFrozen(), effect.durationTicks()));
                    }
                }
                case KNOCKBACK -> {
                    if (attacker != null) {
                        knockbackVector(attacker.getX(), attacker.getZ(), target.getX(), target.getZ())
                                .ifPresent(vector -> target.knockback(effect.strength(), vector.x(), vector.z()));
                    }
                }
                case ELEMENT_CHANGE -> ElementalCapabilities.get(recipient).ifPresent(state -> {
                    var definition = snapshot.elements().get(effect.element());
                    if (definition == null) return;
                    boolean createsElement = effect.operation() == ReactionEffectDefinition.ElementOperation.ADD
                            || effect.operation() == ReactionEffectDefinition.ElementOperation.SET;
                    if (createsElement && !definition.application().fromReaction()) return;
                    int duration = definition.attachment().durationTicks();
                    state.applyEffect(effect.element(), effect.operation().name().toLowerCase(java.util.Locale.ROOT), effect.amount(),
                            level.getGameTime(), duration, definition.attachment().cooldownTicks(),
                            definition.attachment().maxAmount(), true);
                });
            }
        }
    }

    private static int addTicks(int current, int addition) {
        return (int) Math.min(Integer.MAX_VALUE, (long) Math.max(0, current) + Math.max(0, addition));
    }

    static java.util.Optional<KnockbackVector> knockbackVector(double attackerX, double attackerZ,
                                                                double targetX, double targetZ) {
        double x = attackerX - targetX;
        double z = attackerZ - targetZ;
        return x * x + z * z < 1.0E-12D ? java.util.Optional.empty()
                : java.util.Optional.of(new KnockbackVector(x, z));
    }

    record KnockbackVector(double x, double z) {
    }
}
