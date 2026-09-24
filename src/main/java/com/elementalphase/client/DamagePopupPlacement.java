package com.elementalphase.client;

import org.joml.Matrix4f;
import net.minecraft.world.phys.AABB;

public final class DamagePopupPlacement {
    private static final double MIN_FORWARD_DEPTH = 0.05D;
    private static final double OCCLUSION_REFRESH_TICKS = 4.0D;
    private static final double OCCLUSION_CAMERA_MOVE_SQUARED = 0.0625D;

    private DamagePopupPlacement() {
    }

    public static double anchorY(AABB bounds, double heightRatio) {
        return bounds.minY + bounds.getYsize() * heightRatio;
    }

    public static boolean projectToScreen(
            double worldX, double worldY, double worldZ,
            double cameraX, double cameraY, double cameraZ,
            double leftX, double leftY, double leftZ,
            double upX, double upY, double upZ,
            double forwardX, double forwardY, double forwardZ,
            Matrix4f projection, int screenWidth, int screenHeight, ScreenPoint result) {
        if (projection == null || result == null || screenWidth <= 0 || screenHeight <= 0
                || !finite3(worldX, worldY, worldZ) || !finite3(cameraX, cameraY, cameraZ)
                || !finite3(leftX, leftY, leftZ) || !finite3(upX, upY, upZ)
                || !finite3(forwardX, forwardY, forwardZ)
                || !finite3(projection.m00(), projection.m10(), projection.m20())
                || !finite3(projection.m30(), projection.m01(), projection.m11())
                || !finite3(projection.m21(), projection.m31(), projection.m03())
                || !finite3(projection.m13(), projection.m23(), projection.m33())) {
            return false;
        }
        double deltaX = worldX - cameraX;
        double deltaY = worldY - cameraY;
        double deltaZ = worldZ - cameraZ;
        double depth = dot(deltaX, deltaY, deltaZ, forwardX, forwardY, forwardZ);
        if (!Double.isFinite(depth) || depth <= MIN_FORWARD_DEPTH) {
            return false;
        }
        double horizontal = dot(deltaX, deltaY, deltaZ, leftX, leftY, leftZ);
        double vertical = dot(deltaX, deltaY, deltaZ, upX, upY, upZ);
        double viewX = -horizontal;
        double viewZ = -depth;
        double clipX = projection.m00() * viewX + projection.m10() * vertical
                + projection.m20() * viewZ + projection.m30();
        double clipY = projection.m01() * viewX + projection.m11() * vertical
                + projection.m21() * viewZ + projection.m31();
        double clipW = projection.m03() * viewX + projection.m13() * vertical
                + projection.m23() * viewZ + projection.m33();
        if (!Double.isFinite(clipW) || clipW <= MIN_FORWARD_DEPTH) {
            return false;
        }
        double ndcX = clipX / clipW;
        double ndcY = clipY / clipW;
        double screenX = screenWidth * 0.5D * (1.0D + ndcX);
        double screenY = screenHeight * 0.5D * (1.0D - ndcY);
        if (!Double.isFinite(screenX) || !Double.isFinite(screenY)) {
            return false;
        }
        result.set(screenX, screenY);
        return true;
    }

    public static boolean shouldRefreshOcclusion(
            double lastCheckedAt, double now,
            double lastCameraX, double lastCameraY, double lastCameraZ,
            double cameraX, double cameraY, double cameraZ) {
        if (!Double.isFinite(lastCheckedAt) || !Double.isFinite(now)
                || !finite3(lastCameraX, lastCameraY, lastCameraZ)
                || !finite3(cameraX, cameraY, cameraZ)) {
            return true;
        }
        if (now < lastCheckedAt || now - lastCheckedAt >= OCCLUSION_REFRESH_TICKS) {
            return true;
        }
        double deltaX = cameraX - lastCameraX;
        double deltaY = cameraY - lastCameraY;
        double deltaZ = cameraZ - lastCameraZ;
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ >= OCCLUSION_CAMERA_MOVE_SQUARED;
    }

    private static double dot(double firstX, double firstY, double firstZ,
                              double secondX, double secondY, double secondZ) {
        return firstX * secondX + firstY * secondY + firstZ * secondZ;
    }

    private static boolean finite3(double first, double second, double third) {
        return Double.isFinite(first) && Double.isFinite(second) && Double.isFinite(third);
    }

    public static final class ScreenPoint {
        private double x;
        private double y;

        private void set(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }
    }
}
