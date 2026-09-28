package com.elementalphase.reaction.runtime;

import com.elementalphase.combat.ResistancePolicy;
import com.elementalphase.data.model.ReactionDamageDefinition;
import com.elementalphase.display.ReactionDamageContext;
import com.elementalphase.integration.damagenumber.DamageNumberCompat;
import com.elementalphase.reaction.ReactionChainGuard;
import com.elementalphase.reaction.ReactionOutcome;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Optional;

final class ScheduledReactionDamageExecutor {

    private ScheduledReactionDamageExecutor() {
    }

    static boolean execute(ServerLevel level, LivingEntity target, ScheduledDamageState state) {
        Entity attacker = state.source().attacker().map(level::getEntity).filter(ScheduledReactionDamageExecutor::validSource).orElse(null);
        Entity direct = state.source().directEntity().map(level::getEntity).filter(ScheduledReactionDamageExecutor::validSource).orElse(null);
        float amount = (float) damageAmount(state);
        if (amount <= 0.0F) return false;

        var holder = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolder(ResourceKey.create(Registries.DAMAGE_TYPE, state.damage().damageType()));
        if (holder.isEmpty()) return false;
        DamageSource source = new DamageSource(holder.orElseThrow(), direct, attacker);
        var label = label(state, amount);
        DamageNumberCompat.record(source, level.getServer().getTickCount(),
                DamageNumberCompat.appearance(label));
        ReactionChainGuard guard = ReactionDamageContext.chainGuard().orElseGet(ReactionChainGuard::new).child();
        return ReactionDamageContext.call(label, source, false, false, guard, () -> target.hurt(source, amount));
    }

    static double damageAmount(ScheduledDamageState state) {
        double raw = state.damage().formula().evaluate(state.snapshot().context());
        return !Double.isFinite(raw) || raw <= 0 ? 0 : ResistancePolicy.apply(raw,
                state.snapshot().elementResistance(), state.snapshot().reactionResistance());
    }

    static ReactionOutcome.TriggeredReaction label(ScheduledDamageState state, double amount) {
        return new ReactionOutcome.TriggeredReaction(state.effectId(), state.scale(), amount,
                ReactionDamageDefinition.Mode.ADDITIONAL, Optional.of(state.damage().damageType()), state.snapshot().color(),
                Optional.empty(), state.reactionId(), state.snapshot().showName());
    }

    private static boolean validSource(Entity entity) {
        return !entity.isRemoved() && (!(entity instanceof LivingEntity living) || living.isAlive());
    }
}
