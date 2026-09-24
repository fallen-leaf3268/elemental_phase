package com.elementalphase.client;

import com.elementalphase.network.FrozenStateSyncPacket;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

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
            states.remove(packet.entityId());
            return;
        }
        long now = minecraft.level.getGameTime();
        long startedAt = now - Math.max(0, packet.durationTicks() - packet.remainingTicks());
        states.put(packet.entityId(), new ClientFrozenState(packet.durationTicks(), startedAt,
                deadline(now, packet.remainingTicks())));
    }

    public ClientFrozenState state(int entityId, long now) {
        ClientFrozenState state = states.get(entityId);
        if (state != null && !state.visible(now)) {
            states.remove(entityId);
            return null;
        }
        return state;
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
        lastCleanup = Long.MIN_VALUE;
    }

    private static long deadline(long now, int ticks) {
        return now > Long.MAX_VALUE - ticks ? Long.MAX_VALUE : now + ticks;
    }

    public record ClientFrozenState(int durationTicks, long startedAt, long expiresAt) {
        public boolean active(long now) {
            return now <= expiresAt;
        }

        public boolean visible(long now) {
            return now <= expiresAt || expiresAt <= Long.MAX_VALUE - RENDER_GRACE_TICKS
                    && now <= expiresAt + RENDER_GRACE_TICKS;
        }
    }
}
