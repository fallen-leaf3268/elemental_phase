package com.elementalphase.reaction.runtime;

import com.elementalphase.data.model.ReactionAction;
import com.elementalphase.state.ElementSourceSnapshot;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record ScheduledDamageState(ResourceLocation reactionId, double scale, long startedAt, long expiresAt,
                                   int durationTicks, int intervalTicks, ReactionAction.StateDamage damage,
                                   ElementSourceSnapshot source) {
    public ScheduledDamageState {
        Objects.requireNonNull(reactionId);
        Objects.requireNonNull(damage);
        Objects.requireNonNull(source);
        if (!Double.isFinite(scale) || scale < 0.0D || scale > 1_000_000.0D) {
            throw new IllegalArgumentException("Invalid scheduled scale");
        }
        if (durationTicks < 0) throw new IllegalArgumentException("Invalid scheduled duration");
        if (intervalTicks < 1) throw new IllegalArgumentException("Invalid scheduled interval");
        if (expiresAt < startedAt) throw new IllegalArgumentException("Invalid scheduled deadline");
    }

    public static ScheduledDamageState create(ResourceLocation reactionId, double scale, long now,
                                              int durationTicks, int intervalTicks,
                                              ReactionAction.StateDamage damage, ElementSourceSnapshot source) {
        return new ScheduledDamageState(reactionId, scale, now, deadline(now, durationTicks),
                durationTicks, intervalTicks, damage, source);
    }

    public static Application apply(ScheduledDamageState current, ResourceLocation reactionId, double scale,
                                    long now, int durationTicks, int intervalTicks,
                                    ReactionAction.StateDamage damage, ElementSourceSnapshot source) {
        Objects.requireNonNull(current);
        int comparison = Double.compare(scale, current.scale);
        if (comparison < 0) return new Application(current, ApplyResult.IGNORED_LOWER);
        ScheduledDamageState accepted = create(reactionId, scale, now, durationTicks, intervalTicks, damage, source);
        return new Application(accepted,
                comparison == 0 ? ApplyResult.REFRESHED_EQUAL : ApplyResult.REPLACED_HIGHER);
    }

    public boolean due(long now, long lastRunTick) {
        if (lastRunTick == now || now < startedAt || now > expiresAt) return false;
        return (now - startedAt) % intervalTicks == 0L;
    }

    public boolean expiredAfter(long now) {
        return now > expiresAt;
    }

    private static long deadline(long now, int durationTicks) {
        if (durationTicks < 0) throw new IllegalArgumentException("Invalid scheduled duration");
        return now > Long.MAX_VALUE - durationTicks ? Long.MAX_VALUE : now + durationTicks;
    }

    public enum ApplyResult {
        CREATED,
        REPLACED_HIGHER,
        REFRESHED_EQUAL,
        IGNORED_LOWER
    }

    public record Application(ScheduledDamageState state, ApplyResult result) {
        public Application {
            Objects.requireNonNull(state);
            Objects.requireNonNull(result);
        }
    }
}
