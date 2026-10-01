package com.elementalphase.reaction.runtime;

import com.elementalphase.combat.ResistancePolicy;
import com.elementalphase.data.model.ReactionDamageDefinition;
import com.elementalphase.display.ReactionDamageContext;
import com.elementalphase.integration.damagenumber.DamageNumberCompat;
import com.elementalphase.reaction.ReactionChainGuard;
import com.elementalphase.reaction.ReactionOutcome;
import com.elementalphase.state.ElementSourceSnapshot;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;

final class ScheduledReactionDamageExecutor {

    private ScheduledReactionDamageExecutor() {
    }

    static boolean execute(ServerLevel level, LivingEntity target, ScheduledDamageState state) {
        Entity attacker = resolveAttacker(level, state.source());
        if (!canHarmTarget(level, target, state.source(), attacker)) return false;
        if (attacker != null && !validSource(attacker)) attacker = null;
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

    private static Entity resolveAttacker(ServerLevel level, ElementSourceSnapshot source) {
        if (source.attacker().isEmpty()) return null;
        var id = source.attacker().orElseThrow();
        var player = level.getServer().getPlayerList().getPlayer(id);
        return player != null ? player : level.getEntity(id);
    }

    private static boolean canHarmTarget(ServerLevel level, LivingEntity target,
                                         ElementSourceSnapshot source, Entity attacker) {
        if (!(target instanceof ServerPlayer player)) return true;
        if (attacker instanceof Player sourcePlayer) return player.canHarmPlayer(sourcePlayer);
        var cache = level.getServer().getProfileCache();
        Optional<String> playerName = cache == null ? source.playerName()
                : source.attacker().flatMap(cache::get).map(GameProfile::getName).or(source::playerName);
        if (playerName.isEmpty()) return true;
        if (!level.getServer().isPvpAllowed()) return false;
        var team = player.getTeam();
        return team == null || !team.isAlliedTo(level.getServer().getScoreboard().getPlayersTeam(playerName.orElseThrow()))
                || team.isAllowFriendlyFire();
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
