package com.elementalphase.combat;

import com.elementalphase.data.ElementDataSnapshot;
import com.elementalphase.data.ElementDataManager;
import com.elementalphase.reaction.ReactionPlan;
import com.elementalphase.reaction.ReactionChainGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

public record QueuedHitExecution(ServerLevel level, LivingEntity attacker, LivingEntity target,
                                 DamageSource source, ReactionPlan plan,
                                 ElementDataSnapshot snapshot, long createdAtTick, ReactionChainGuard guard) {
    public boolean isCurrent() {
        return snapshot == ElementDataManager.snapshot();
    }
}
