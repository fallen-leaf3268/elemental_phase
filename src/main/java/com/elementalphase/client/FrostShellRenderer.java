package com.elementalphase.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public final class FrostShellRenderer {
    static final int PILLAR_COUNT = 3;
    static final float CROSS_HEIGHT = 0.80F;
    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/block/ice.png");
    private static final float TWO_PI = (float) (Math.PI * 2.0D);
    private static final int ALPHA = 190;
    private static final int MAIN_RED = 0x72;
    private static final int MAIN_GREEN = 0xB8;
    private static final int MAIN_BLUE = 0xED;
    private static final int SHADOW_RED = 0x39;
    private static final int SHADOW_GREEN = 0x7F;
    private static final int SHADOW_BLUE = 0xC4;
    private static final int HIGHLIGHT_RED = 0xB2;
    private static final int HIGHLIGHT_GREEN = 0xE2;
    private static final int HIGHLIGHT_BLUE = 0xFF;

    private FrostShellRenderer() {
    }

    public static void render(LivingEntity entity, PoseStack poseStack, MultiBufferSource buffers, int light) {
        float entityWidth = Math.max(0.1F, entity.getBbWidth());
        float entityHeight = Math.max(0.1F, entity.getBbHeight());
        float baseRadius = Math.max(0.34F, Math.min(2.4F, entityWidth * 0.72F));
        float baseWidth = Math.max(0.16F, Math.min(0.62F, entityWidth * 0.22F));
        VertexConsumer consumer = buffers.getBuffer(RenderType.entityTranslucent(TEXTURE));
        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();
        Matrix3f normal = pose.normal();

        for (int index = 0; index < PILLAR_COUNT; index++) {
            Pillar pillar = pillar(entity.getId(), index);
            float angle = TWO_PI * pillar.angle();
            float rootWidth = baseWidth * (0.85F + pillar.baseScale() * 0.30F);
            float baseX = (float) Math.cos(angle) * baseRadius;
            float baseZ = (float) Math.sin(angle) * baseRadius;
            float baseY = rootWidth * 0.24F;
            float tipY = entityHeight * pillar.tipHeight();
            float crossY = entityHeight * CROSS_HEIGHT;
            float crossingProgress = (crossY - baseY) / Math.max(0.001F, tipY - baseY);
            float tipFactor = -(1.0F - crossingProgress) / Math.max(0.001F, crossingProgress);
            Point base = new Point(baseX, baseY, baseZ);
            Point tip = new Point(baseX * tipFactor, tipY, baseZ * tipFactor);
            renderPillar(consumer, matrix, normal, base, tip, rootWidth,
                    pillar.twist(), pillar.shade(), light);
        }
    }

    static Pillar pillar(int entityId, int index) {
        int normalizedIndex = Math.floorMod(index, PILLAR_COUNT);
        int rotationSeed = mix(entityId * 0x45D9F3B);
        float rotation = unit(rotationSeed) / PILLAR_COUNT;
        float angle = rotation + normalizedIndex / (float) PILLAR_COUNT;
        if (angle >= 1.0F) angle -= 1.0F;
        int seed = mix(entityId * 0x27D4EB2D ^ normalizedIndex * 0x165667B1);
        float tipHeight = switch (normalizedIndex) {
            case 0 -> 0.92F + unit(seed) * 0.01F;
            case 1 -> 0.95F + unit(seed) * 0.02F;
            default -> 0.98F + unit(seed) * 0.02F;
        };
        return new Pillar(angle, tipHeight, unit(mix(seed + 0x2C1B3C6D)),
                unit(mix(seed + 0x51ED270B)), unit(mix(seed + 0x68E31DA4)));
    }

    private static void renderPillar(VertexConsumer consumer, Matrix4f matrix, Matrix3f normal,
                                     Point base, Point tip, float rootWidth,
                                     float twist, float shade, int light) {
        Vec direction = normalize(tip.x() - base.x(), tip.y() - base.y(), tip.z() - base.z());
        Vec side = normalize(direction.z(), 0.0F, -direction.x());
        Vec vertical = normalize(
                direction.y() * side.z() - direction.z() * side.y(),
                direction.z() * side.x() - direction.x() * side.z(),
                direction.x() * side.y() - direction.y() * side.x());
        float rootVertical = rootWidth * 0.78F;
        Point shoulderCenter = new Point(
                base.x() + (tip.x() - base.x()) * 0.68F + side.x() * (twist - 0.5F) * rootWidth * 0.22F,
                base.y() + (tip.y() - base.y()) * 0.68F + side.y() * (twist - 0.5F) * rootWidth * 0.22F,
                base.z() + (tip.z() - base.z()) * 0.68F + side.z() * (twist - 0.5F) * rootWidth * 0.22F);
        float shoulderWidth = rootWidth * (0.42F + shade * 0.10F);
        float shoulderVertical = shoulderWidth * 0.74F;

        Point rootBottomLeft = offset(base, side, -rootWidth, vertical, -rootVertical);
        Point rootBottomRight = offset(base, side, rootWidth, vertical, -rootVertical);
        Point rootTopRight = offset(base, side, rootWidth * 0.86F, vertical, rootVertical);
        Point rootTopLeft = offset(base, side, -rootWidth * 0.86F, vertical, rootVertical);
        Point shoulderBottomLeft = offset(shoulderCenter, side, -shoulderWidth, vertical, -shoulderVertical);
        Point shoulderBottomRight = offset(shoulderCenter, side, shoulderWidth, vertical, -shoulderVertical);
        Point shoulderTopRight = offset(shoulderCenter, side, shoulderWidth * 0.78F, vertical, shoulderVertical);
        Point shoulderTopLeft = offset(shoulderCenter, side, -shoulderWidth * 0.78F, vertical, shoulderVertical);

        quad(consumer, matrix, normal, rootBottomLeft, shoulderBottomLeft,
                shoulderBottomRight, rootBottomRight, MAIN_RED, MAIN_GREEN, MAIN_BLUE, light);
        quad(consumer, matrix, normal, rootBottomRight, shoulderBottomRight,
                shoulderTopRight, rootTopRight, HIGHLIGHT_RED, HIGHLIGHT_GREEN, HIGHLIGHT_BLUE, light);
        quad(consumer, matrix, normal, rootTopRight, shoulderTopRight,
                shoulderTopLeft, rootTopLeft, MAIN_RED, MAIN_GREEN, MAIN_BLUE, light);
        quad(consumer, matrix, normal, rootTopLeft, shoulderTopLeft,
                shoulderBottomLeft, rootBottomLeft, SHADOW_RED, SHADOW_GREEN, SHADOW_BLUE, light);
        quad(consumer, matrix, normal, shoulderBottomLeft, tip,
                shoulderBottomRight, shoulderBottomRight, MAIN_RED, MAIN_GREEN, MAIN_BLUE, light);
        quad(consumer, matrix, normal, shoulderBottomRight, tip,
                shoulderTopRight, shoulderTopRight, HIGHLIGHT_RED, HIGHLIGHT_GREEN, HIGHLIGHT_BLUE, light);
        quad(consumer, matrix, normal, shoulderTopRight, tip,
                shoulderTopLeft, shoulderTopLeft, MAIN_RED, MAIN_GREEN, MAIN_BLUE, light);
        quad(consumer, matrix, normal, shoulderTopLeft, tip,
                shoulderBottomLeft, shoulderBottomLeft, SHADOW_RED, SHADOW_GREEN, SHADOW_BLUE, light);
        quad(consumer, matrix, normal, rootTopLeft, rootTopRight,
                rootBottomRight, rootBottomLeft, MAIN_RED, MAIN_GREEN, MAIN_BLUE, light);
    }

    private static Point offset(Point center, Vec first, float firstScale, Vec second, float secondScale) {
        return new Point(center.x() + first.x() * firstScale + second.x() * secondScale,
                center.y() + first.y() * firstScale + second.y() * secondScale,
                center.z() + first.z() * firstScale + second.z() * secondScale);
    }

    private static Vec normalize(float x, float y, float z) {
        float inverseLength = 1.0F / Math.max(0.0001F, (float) Math.sqrt(x * x + y * y + z * z));
        return new Vec(x * inverseLength, y * inverseLength, z * inverseLength);
    }

    private static int mix(int value) {
        value ^= value >>> 16;
        value *= 0x7FEB352D;
        value ^= value >>> 15;
        value *= 0x846CA68B;
        return value ^ value >>> 16;
    }

    private static float unit(int value) {
        return (value >>> 8) * (1.0F / 16_777_215.0F);
    }

    private static void quad(VertexConsumer consumer, Matrix4f matrix, Matrix3f normal,
                             Point first, Point second, Point third, Point fourth,
                             int red, int green, int blue, int light) {
        float edgeOneX = second.x() - first.x();
        float edgeOneY = second.y() - first.y();
        float edgeOneZ = second.z() - first.z();
        float edgeTwoX = third.x() - first.x();
        float edgeTwoY = third.y() - first.y();
        float edgeTwoZ = third.z() - first.z();
        Vec faceNormal = normalize(edgeOneY * edgeTwoZ - edgeOneZ * edgeTwoY,
                edgeOneZ * edgeTwoX - edgeOneX * edgeTwoZ,
                edgeOneX * edgeTwoY - edgeOneY * edgeTwoX);
        vertex(consumer, matrix, normal, first, 0.0F, 1.0F, faceNormal, red, green, blue, light);
        vertex(consumer, matrix, normal, second, 0.0F, 0.0F, faceNormal, red, green, blue, light);
        vertex(consumer, matrix, normal, third, 1.0F, 0.0F, faceNormal, red, green, blue, light);
        vertex(consumer, matrix, normal, fourth, 1.0F, 1.0F, faceNormal, red, green, blue, light);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, Matrix3f normal,
                               Point point, float u, float v, Vec faceNormal,
                               int red, int green, int blue, int light) {
        consumer.vertex(matrix, point.x(), point.y(), point.z()).color(red, green, blue, ALPHA).uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light)
                .normal(normal, faceNormal.x(), faceNormal.y(), faceNormal.z()).endVertex();
    }

    record Pillar(float angle, float tipHeight, float baseScale, float twist, float shade) {
    }

    private record Point(float x, float y, float z) {
    }

    private record Vec(float x, float y, float z) {
    }
}
