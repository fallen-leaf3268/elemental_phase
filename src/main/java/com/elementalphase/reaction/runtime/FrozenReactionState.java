package com.elementalphase.reaction.runtime;

import net.minecraft.world.phys.Vec3;

public record FrozenReactionState(long startedAt, long expiresAt, int durationTicks) {
    public FrozenReactionState {
        if (durationTicks < 1) throw new IllegalArgumentException("Invalid freeze duration");
        if (expiresAt < startedAt) throw new IllegalArgumentException("Invalid freeze deadline");
    }

    public static FrozenReactionState create(long now, int durationTicks) {
        return new FrozenReactionState(now, deadline(now, durationTicks), durationTicks);
    }

    public FrozenReactionState refresh(long now, int durationTicks) {
        if (durationTicks < 1) throw new IllegalArgumentException("Invalid freeze duration");
        if (active(now) && durationTicks < remainingTicks(now)) return this;
        return create(now, durationTicks);
    }

    public boolean active(long now) {
        return now >= startedAt && now <= expiresAt;
    }

    public Vec3 constrainMovement(Vec3 movement, long now) {
        return active(now) ? new Vec3(0.0D, movement.y, 0.0D) : movement;
    }

    public int remainingTicks(long now) {
        if (now <= startedAt) return durationTicks;
        if (now >= expiresAt) return 0;
        return (int) Math.min(durationTicks, expiresAt - now);
    }

    private static long deadline(long now, int durationTicks) {
        if (durationTicks < 1) throw new IllegalArgumentException("Invalid freeze duration");
        return now > Long.MAX_VALUE - durationTicks ? Long.MAX_VALUE : now + durationTicks;
    }
}
