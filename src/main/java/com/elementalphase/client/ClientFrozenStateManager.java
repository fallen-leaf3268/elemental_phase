package com.elementalphase.client;

import com.elementalphase.network.FrozenStateSyncPacket;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class ClientFrozenStateManager {
    public static final ClientFrozenStateManager INSTANCE = new ClientFrozenStateManager();
    private static final long RENDER_GRACE_TICKS = 5L;

    private final Int2ObjectOpenHashMap<ClientFrozenState> states = new Int2ObjectOpenHashMap<>();
    private Object worldToken;
    private long lastCleanup = Long.MIN_VALUE;

    private ClientFrozenStateManager() {
    }

    public void receive(FrozenStateSyncPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !packet.isValid()) return;
        updateWorldToken(minecraft.level);
        if (!packet.frozen()) {
            stop(packet.entityId(), minecraft.level.getGameTime());
            return;
        }
        start(packet.entityId(), packet.durationTicks(), packet.remainingTicks(), minecraft.level.getGameTime());
    }

    void start(int entityId, int durationTicks, int remainingTicks, long now) {
        var previous = states.get(entityId);
        long startedAt = previous != null && previous.active(now) ? previous.startedAt()
                : now - Math.max(0, durationTicks - remainingTicks);
        states.put(entityId, new ClientFrozenState(durationTicks, startedAt, deadline(now, remainingTicks)));
    }

    void stop(int entityId, long now) {
        var state = states.get(entityId);
        if (state != null) {
            states.put(entityId, new ClientFrozenState(state.durationTicks(), state.startedAt(),
                    Math.min(state.expiresAt(), now == Long.MIN_VALUE ? Long.MIN_VALUE : now - 1)));
        }
    }

    public ClientFrozenState state(int entityId, long now) {
        ClientFrozenState state = states.get(entityId);
        if (state != null && !state.visible(now)) {
            states.remove(entityId);
            return null;
        }
        return state;
    }

    public Vec3 constrainMovement(LivingEntity entity, Vec3 movement) {
        updateWorldToken(entity.level());
        long now = entity.level().getGameTime();
        ClientFrozenState state = state(entity.getId(), now);
        return state == null ? movement : state.constrainMovement(movement, now);
    }

    public void cleanup(Level level, long now) {
        updateWorldToken(level);
        if (lastCleanup != Long.MIN_VALUE && now - lastCleanup < 20L) return;
        lastCleanup = now;
        states.int2ObjectEntrySet().removeIf(entry -> level.getEntity(entry.getIntKey()) == null
                || !entry.getValue().visible(now));
    }

    public void updateWorldToken(Object token) {
        if (worldToken == token) return;
        clear();
        worldToken = token;
    }

    public void clear() {
        states.clear();
        FrozenReactionRenderer.clearVisuals();
        lastCleanup = Long.MIN_VALUE;
    }

    private static long deadline(long now, int ticks) {
        return now > Long.MAX_VALUE - ticks ? Long.MAX_VALUE : now + ticks;
    }

    public record ClientFrozenState(int durationTicks, long startedAt, long expiresAt) {
        public float growth(double now) {
            return 1;
        }

        public float opacity(double now) {
            return now <= expiresAt + 1.0 ? 1 : (float) Math.max(0, Math.min(1, (expiresAt + 5.0 - now) / 4.0));
        }

        public boolean active(long now) {
            return now <= expiresAt;
        }

        public Vec3 constrainMovement(Vec3 movement, long now) {
            return active(now) ? new Vec3(0.0D, movement.y, 0.0D) : movement;
        }

        public boolean visible(long now) {
            return now <= expiresAt || expiresAt <= Long.MAX_VALUE - RENDER_GRACE_TICKS
                    && now <= expiresAt + RENDER_GRACE_TICKS;
        }
    }
}
