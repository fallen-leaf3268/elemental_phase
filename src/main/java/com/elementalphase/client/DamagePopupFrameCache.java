package com.elementalphase.client;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.world.phys.AABB;

import java.util.function.IntFunction;

public final class DamagePopupFrameCache<T> {
    private final Int2ObjectOpenHashMap<T> entries = new Int2ObjectOpenHashMap<>();

    public void beginFrame() {
        entries.clear();
    }

    public T getOrCompute(int entityId, IntFunction<T> compute) {
        if (entries.containsKey(entityId)) {
            return entries.get(entityId);
        }
        T result = compute.apply(entityId);
        entries.put(entityId, result);
        return result;
    }

    public static boolean shouldCull(boolean frustumVisible, AABB conservativeBounds,
                                     double x, double y, double z, double radius) {
        return !frustumVisible && Double.isFinite(radius) && radius >= 0.0D
                && x - radius >= conservativeBounds.minX && x + radius <= conservativeBounds.maxX
                && y - radius >= conservativeBounds.minY && y + radius <= conservativeBounds.maxY
                && z - radius >= conservativeBounds.minZ && z + radius <= conservativeBounds.maxZ;
    }
}
