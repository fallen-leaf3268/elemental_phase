package com.elementalphase.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public final class FrostShellRenderer {
    private static final ResourceLocation ICE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/block/ice.png");
    private static final float MARGIN = 0.04F;

    private FrostShellRenderer() {
    }

    public static List<Point> render(AABB collisionBounds, PoseStack poseStack, MultiBufferSource buffers,
                                     int light, float growth, float opacity, Vec3 renderOffset) {
        return renderBox(collisionBounds, poseStack, buffers.getBuffer(RenderType.entityTranslucent(ICE_TEXTURE)),
                light, growth, opacity, renderOffset);
    }

    static List<Point> renderBox(AABB collisionBounds, PoseStack poseStack, VertexConsumer consumer,
                                 int light, float growth, float opacity, Vec3 renderOffset) {
        if (renderOffset == null || !Double.isFinite(renderOffset.x) || !Double.isFinite(renderOffset.y)
                || !Double.isFinite(renderOffset.z)) return List.of();
        poseStack.pushPose();
        try {
            poseStack.translate(-renderOffset.x, -renderOffset.y, -renderOffset.z);
            return drawBox(collisionBounds, poseStack, consumer, light, growth, opacity);
        } finally {
            poseStack.popPose();
        }
    }

    private static List<Point> drawBox(AABB collisionBounds, PoseStack poseStack, VertexConsumer consumer,
                                 int light, float growth, float opacity) {
        if (!validBounds(collisionBounds) || !Float.isFinite(growth) || !Float.isFinite(opacity)
                || growth <= 0 || opacity <= 0) return List.of();
        var bounds = collisionBounds.inflate(MARGIN);
        float x0 = (float) bounds.minX, y0 = (float) bounds.minY, z0 = (float) bounds.minZ;
        float x1 = (float) bounds.maxX, z1 = (float) bounds.maxZ;
        float y1 = (float) (bounds.minY + bounds.getYsize() * Math.min(1, growth));
        if (!Float.isFinite(x0) || !Float.isFinite(y0) || !Float.isFinite(z0)
                || !Float.isFinite(x1) || !Float.isFinite(y1) || !Float.isFinite(z1)
                || !Float.isFinite(x1 - x0) || !Float.isFinite(y1 - y0) || !Float.isFinite(z1 - z0)
                || x1 <= x0 || y1 <= y0 || z1 <= z0) return List.of();
        int alpha = Math.round(255 * Math.min(1, opacity));
        if (alpha == 0) return List.of();
        var a = new Point(x0, y0, z0);
        var b = new Point(x1, y0, z0);
        var c = new Point(x1, y0, z1);
        var d = new Point(x0, y0, z1);
        var e = new Point(x0, y1, z0);
        var f = new Point(x1, y1, z0);
        var g = new Point(x1, y1, z1);
        var h = new Point(x0, y1, z1);
        var pose = poseStack.last();
        boxFace(consumer, pose, b, a, e, f, 0, 0, -1, light, alpha);
        boxFace(consumer, pose, d, c, g, h, 0, 0, 1, light, alpha);
        boxFace(consumer, pose, a, d, h, e, -1, 0, 0, light, alpha);
        boxFace(consumer, pose, c, b, f, g, 1, 0, 0, light, alpha);
        boxFace(consumer, pose, h, g, f, e, 0, 1, 0, light, alpha);
        boxFace(consumer, pose, a, b, c, d, 0, -1, 0, light, alpha);
        float middleX = x0 + (x1 - x0) * 0.5F, middleZ = z0 + (z1 - z0) * 0.5F;
        float low = y0 + (y1 - y0) * 0.15F, middle = y0 + (y1 - y0) * 0.5F;
        float high = y0 + (y1 - y0) * 0.85F;
        return List.of(new Point(middleX, low, z0), new Point(x1, low, middleZ),
                new Point(x0, middle, middleZ), new Point(x1, middle, middleZ),
                new Point(middleX, high, z0), new Point(middleX, high, z1));
    }

    private static boolean validBounds(AABB bounds) {
        return bounds != null && Double.isFinite(bounds.minX) && Double.isFinite(bounds.minY)
                && Double.isFinite(bounds.minZ) && Double.isFinite(bounds.maxX)
                && Double.isFinite(bounds.maxY) && Double.isFinite(bounds.maxZ)
                && bounds.getXsize() > 0 && bounds.getYsize() > 0 && bounds.getZsize() > 0;
    }

    private static void boxFace(VertexConsumer consumer, PoseStack.Pose pose, Point a, Point b, Point c, Point d,
                                float normalX, float normalY, float normalZ, int light, int alpha) {
        float width = distance(a, b), height = distance(a, d);
        emit(consumer, pose, a, 0, 0, normalX, normalY, normalZ, light, alpha);
        emit(consumer, pose, b, width, 0, normalX, normalY, normalZ, light, alpha);
        emit(consumer, pose, c, width, height, normalX, normalY, normalZ, light, alpha);
        emit(consumer, pose, d, 0, height, normalX, normalY, normalZ, light, alpha);
    }

    private static float distance(Point first, Point second) {
        return (float) Math.hypot(Math.hypot((double) first.x() - second.x(), (double) first.y() - second.y()),
                (double) first.z() - second.z());
    }

    private static void emit(VertexConsumer consumer, PoseStack.Pose pose, Point point, float u, float v,
                             float normalX, float normalY, float normalZ, int light, int alpha) {
        consumer.vertex(pose.pose(), point.x(), point.y(), point.z()).color(219, 237, 255, alpha).uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light)
                .normal(pose.normal(), normalX, normalY, normalZ).endVertex();
    }

    record Point(float x, float y, float z) {
    }

}
