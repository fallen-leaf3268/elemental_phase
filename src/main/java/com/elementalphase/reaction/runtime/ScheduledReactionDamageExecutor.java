package com.elementalphase.reaction.runtime;

import com.elementalphase.capability.ElementalCapabilities;
import com.elementalphase.data.model.ReactionAction;
import com.elementalphase.data.model.ReactionDamageDefinition;
import com.elementalphase.display.ReactionDamageContext;
import com.elementalphase.integration.damagenumber.DamageNumberCompat;
import com.elementalphase.reaction.ReactionChainGuard;
import com.elementalphase.reaction.ReactionOutcome;
import com.elementalphase.reaction.formula.ReactionFormulaContext;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.Optional;

final class ScheduledReactionDamageExecutor {
    private static final double MAX_DAMAGE = 1_000_000.0D;

    private ScheduledReactionDamageExecutor() {
    }

    static boolean execute(ServerLevel level, LivingEntity target, ScheduledDamageState state) {
        Entity attacker = state.source().attacker().map(level::getEntity).orElse(null);
        Entity direct = state.source().directEntity().map(level::getEntity).orElse(attacker);
        double attackerLevel = attacker instanceof Player player ? player.experienceLevel : 0.0D;
        double rawDamage = state.damage().formula()
                .evaluate(ReactionFormulaContext.stateDamage(state.scale(), attackerLevel));
        if (!Double.isFinite(rawDamage) || rawDamage <= 0.0D) return false;

        double resistance = state.damage().resistanceElement()
                .filter(value -> value.kind() == ReactionAction.ElementReference.Kind.FIXED)
                .map(ReactionAction.ElementReference::fixed)
                .map(element -> ElementalCapabilities.get(target).resolve()
                        .map(targetState -> targetState.resistance(element)).orElse(0.0D))
                .orElse(0.0D);
        float amount = (float) Math.min(MAX_DAMAGE, Math.max(0.0D, rawDamage * (1.0D - resistance)));
        if (amount <= 0.0F) return false;

        var holder = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolder(ResourceKey.create(Registries.DAMAGE_TYPE, state.damage().damageType()));
        if (holder.isEmpty()) return false;
        DamageSource source = new DamageSource(holder.orElseThrow(), direct, attacker);
        int color = state.damage().color().orElse(0xFFFFFF);
        var label = new ReactionOutcome.TriggeredReaction(state.reactionId(), state.scale(), amount,
                ReactionDamageDefinition.Mode.ADDITIONAL, Optional.of(state.damage().damageType()), color);
        DamageNumberCompat.record(source, level.getServer().getTickCount(),
                List.of(DamageNumberCompat.label(label)));
        ReactionChainGuard guard = ReactionDamageContext.chainGuard().orElseGet(ReactionChainGuard::new).child();
        return ReactionDamageContext.call(label, source, false, false, guard, () -> target.hurt(source, amount));
    }
}
