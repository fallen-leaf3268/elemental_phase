package com.elementalphase.client;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.function.Predicate;

final class DamagePopupOcclusion {
    private static final double INSET = 0.15D;

    private DamagePopupOcclusion() {
    }

    static boolean isFullyBlocked(AABB bounds, Predicate<Vec3> pointBlocked) {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(pointBlocked, "pointBlocked");
        double centerX = (bounds.minX + bounds.maxX) * 0.5D;
        double centerY = (bounds.minY + bounds.maxY) * 0.5D;
        double centerZ = (bounds.minZ + bounds.maxZ) * 0.5D;
        double lowX = bounds.minX + bounds.getXsize() * INSET;
        double highX = bounds.maxX - bounds.getXsize() * INSET;
        double lowY = bounds.minY + bounds.getYsize() * INSET;
        double highY = bounds.maxY - bounds.getYsize() * INSET;
        double lowZ = bounds.minZ + bounds.getZsize() * INSET;
        double highZ = bounds.maxZ - bounds.getZsize() * INSET;

        if (!pointBlocked.test(new Vec3(centerX, centerY, centerZ))
                || !pointBlocked.test(new Vec3(lowX, centerY, centerZ))
                || !pointBlocked.test(new Vec3(highX, centerY, centerZ))
                || !pointBlocked.test(new Vec3(centerX, lowY, centerZ))
                || !pointBlocked.test(new Vec3(centerX, highY, centerZ))
                || !pointBlocked.test(new Vec3(centerX, centerY, lowZ))
                || !pointBlocked.test(new Vec3(centerX, centerY, highZ))) {
            return false;
        }
        for (int xIndex = 0; xIndex < 2; xIndex++) {
            double x = xIndex == 0 ? lowX : highX;
            for (int yIndex = 0; yIndex < 2; yIndex++) {
                double y = yIndex == 0 ? lowY : highY;
                for (int zIndex = 0; zIndex < 2; zIndex++) {
                    double z = zIndex == 0 ? lowZ : highZ;
                    if (!pointBlocked.test(new Vec3(x, y, z))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    static boolean shouldRefresh(double checkedAt, double now,
                                 double previousCameraX, double previousCameraY, double previousCameraZ,
                                 double cameraX, double cameraY, double cameraZ,
                                 AABB previousBounds, AABB bounds) {
        return previousBounds == null || bounds == null
                || previousBounds.minX != bounds.minX || previousBounds.minY != bounds.minY
                || previousBounds.minZ != bounds.minZ || previousBounds.maxX != bounds.maxX
                || previousBounds.maxY != bounds.maxY || previousBounds.maxZ != bounds.maxZ
                || DamagePopupPlacement.shouldRefreshOcclusion(
                checkedAt, now,
                previousCameraX, previousCameraY, previousCameraZ,
                cameraX, cameraY, cameraZ);
    }
}
