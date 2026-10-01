package com.elementalphase.client;

import com.elementalphase.network.DamagePopupPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrostPillarSealLayoutTest {
    @Test
    void collisionIceFormsExactlySixClosedFacesAroundTheHitbox() throws Exception {
        for (var hitbox : List.of(new AABB(-0.45, 0, -0.45, 0.45, 2.9, 0.45),
                new AABB(-0.2, 0, -0.2, 0.2, 0.7, 0.2),
                new AABB(-2, 0.3, -1, 2, 1.1, 1))) {
            var vertices = new RecordedVertices();
            var original = new AABB(hitbox.minX, hitbox.minY, hitbox.minZ, hitbox.maxX, hitbox.maxY, hitbox.maxZ);
            drawBox(hitbox, new com.mojang.blaze3d.vertex.PoseStack(), vertices, 0x00300020, 1, 1);
            assertEquals(24, vertices.positions.size(), "One collision ice block needs six complete quads");
            var expected = hitbox.inflate(0.04);
            assertEquals(expected.minX, vertices.positions.stream().mapToDouble(point -> point.x).min().orElseThrow(), 0.000001);
            assertEquals(expected.minY, vertices.positions.stream().mapToDouble(point -> point.y).min().orElseThrow(), 0.000001);
            assertEquals(expected.minZ, vertices.positions.stream().mapToDouble(point -> point.z).min().orElseThrow(), 0.000001);
            assertEquals(expected.maxX, vertices.positions.stream().mapToDouble(point -> point.x).max().orElseThrow(), 0.000001);
            assertEquals(expected.maxY, vertices.positions.stream().mapToDouble(point -> point.y).max().orElseThrow(), 0.000001);
            assertEquals(expected.maxZ, vertices.positions.stream().mapToDouble(point -> point.z).max().orElseThrow(), 0.000001);
            assertEquals(8, new java.util.HashSet<>(vertices.positions).size(), "All six faces must share the same eight corners");
            assertEquals(6, new java.util.HashSet<>(vertices.normals).size());
            assertTrue(vertices.alphas.stream().allMatch(alpha -> alpha == 255));
            assertTrue(vertices.lights.stream().allMatch(light -> light == 0x00300020), "The ice must use ambient light");
            assertEquals(original, hitbox, "The visual shell must not change the actual collision box");
            for (int index = 0; index < 24; index += 4) {
                var normal = vertices.normals.get(index);
                var a = vertices.positions.get(index);
                var b = vertices.positions.get(index + 1);
                var d = vertices.positions.get(index + 3);
                assertEquals(1, normal.length(), 0.000001);
                assertTrue(b.subtract(a).cross(d.subtract(a)).normalize().dot(normal) > 0.999,
                        "Every face must have outward winding matching its normal");
                assertTrue(a.subtract(expected.getCenter()).dot(normal) > 0);
            }
        }
    }

    @Test
    void collisionIceGrowsFromTheFeetAndThawsAsOneBlock() throws Exception {
        var hitbox = new AABB(-0.45, 0, -0.45, 0.45, 2.9, 0.45);
        var pose = new com.mojang.blaze3d.vertex.PoseStack();
        var full = new RecordedVertices();
        var half = new RecordedVertices();
        var thawing = new RecordedVertices();
        drawBox(hitbox, pose, full, 0, 1, 1);
        drawBox(hitbox, pose, half, 0, 0.5F, 1);
        drawBox(hitbox, pose, thawing, 0, 1, 0.5F);
        assertEquals(24, half.positions.size());
        double low = full.positions.stream().mapToDouble(point -> point.y).min().orElseThrow();
        double high = full.positions.stream().mapToDouble(point -> point.y).max().orElseThrow();
        assertEquals(low, half.positions.stream().mapToDouble(point -> point.y).min().orElseThrow(), 0.000001);
        assertEquals((low + high) * 0.5, half.positions.stream().mapToDouble(point -> point.y).max().orElseThrow(), 0.000001);
        for (int index = 0; index < 24; index++) {
            assertEquals(full.positions.get(index).x, half.positions.get(index).x);
            assertEquals(full.positions.get(index).z, half.positions.get(index).z);
        }
        assertEquals(full.positions, thawing.positions, "Thaw must fade the existing ice block without moving its faces");
        assertTrue(thawing.alphas.stream().allMatch(alpha -> alpha == 128));
        assertEquals(full.normals, half.normals);
    }

    @Test
    void collisionIceUsesTheEntityRootPoseWithoutModelRotations() throws Exception {
        var hitbox = new AABB(-0.3, -0.2, -0.4, 0.7, 1.6, 0.5);
        var base = new RecordedVertices();
        drawBox(hitbox, new com.mojang.blaze3d.vertex.PoseStack(), base, 0, 1, 1);
        var pose = new com.mojang.blaze3d.vertex.PoseStack();
        pose.translate(123, -7, 8);
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(35));
        var before = new Matrix4f(pose.last().pose());
        var moved = new RecordedVertices();
        drawBox(hitbox, pose, moved, 0, 1, 1);
        assertEquals(before, pose.last().pose(), "Rendering must preserve the caller's pose stack");
        assertEquals(base.uvs, moved.uvs, "The ice texture must stay fixed when camera coordinates change");
        for (int index = 0; index < 24; index++) {
            var source = base.positions.get(index);
            var point = before.transformPosition(new org.joml.Vector3f((float) source.x, (float) source.y, (float) source.z));
            assertEquals(point.x(), moved.positions.get(index).x, 0.00001);
            assertEquals(point.y(), moved.positions.get(index).y, 0.00001);
            assertEquals(point.z(), moved.positions.get(index).z, 0.00001);
            var normal = base.normals.get(index);
            var expected = pose.last().normal().transform(new org.joml.Vector3f((float) normal.x, (float) normal.y, (float) normal.z));
            assertEquals(expected.x(), moved.normals.get(index).x, 0.000001);
            assertEquals(expected.y(), moved.normals.get(index).y, 0.000001);
            assertEquals(expected.z(), moved.normals.get(index).z, 0.000001);
        }
    }

    @Test
    void collisionIceRemovesRendererOffsetsWithoutMovingTheParticleAnchors() throws Exception {
        var hitbox = new AABB(-0.3, 0, -0.3, 0.3, 1.5, 0.3);
        var basePose = new com.mojang.blaze3d.vertex.PoseStack();
        basePose.translate(30, -7, 6);
        basePose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(37));
        var baseline = new RecordedVertices();
        var expectedAnchors = drawBox(hitbox, basePose, baseline, 0, 1, 1);
        for (var offset : List.of(new Vec3(0, -0.125, 0), new Vec3(0.3, -0.125, 0.2))) {
            var pose = new com.mojang.blaze3d.vertex.PoseStack();
            pose.translate(30, -7, 6);
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(37));
            pose.translate(offset.x, offset.y, offset.z);
            var before = new Matrix4f(pose.last().pose());
            var corrected = new RecordedVertices();
            var anchors = FrostShellRenderer.renderBox(hitbox, pose, corrected, 0, 1, 1, offset);
            assertEquals(before, pose.last().pose(), "Offset correction must restore the caller's root pose");
            assertEquals(expectedAnchors, anchors, "Particle anchors must stay relative to the actual entity position");
            for (int index = 0; index < 24; index++) {
                assertEquals(baseline.positions.get(index).x, corrected.positions.get(index).x, 0.00001);
                assertEquals(baseline.positions.get(index).y, corrected.positions.get(index).y, 0.00001,
                        "The crouching render offset must not lower the actual collision ice");
                assertEquals(baseline.positions.get(index).z, corrected.positions.get(index).z, 0.00001);
            }
        }
    }

    @Test
    void rendererOffsetCaptureKeepsTheAppliedValueAndRestoresNestedRenders() throws Exception {
        var applied = new Vec3(0.013, -0.125, -0.007);
        var nested = new Vec3(-0.017, 0, 0.025);
        FrozenReactionRenderer.beginRender(null);
        try {
            assertEquals(Vec3.ZERO, FrozenReactionRenderer.renderOffset(null));
            org.junit.jupiter.api.Assertions.assertSame(applied, FrozenReactionRenderer.captureRenderOffset(applied, null));
            org.junit.jupiter.api.Assertions.assertSame(applied, FrozenReactionRenderer.renderOffset(null),
                    "The ice must reuse the exact offset already applied by the dispatcher");
            FrozenReactionRenderer.beginRender(null);
            try {
                FrozenReactionRenderer.captureRenderOffset(nested, null);
                org.junit.jupiter.api.Assertions.assertSame(nested, FrozenReactionRenderer.renderOffset(null));
            } finally {
                FrozenReactionRenderer.endRender(null);
            }
            org.junit.jupiter.api.Assertions.assertSame(applied, FrozenReactionRenderer.renderOffset(null),
                    "A nested render must restore the parent's applied offset");
        } finally {
            FrozenReactionRenderer.endRender(null);
        }
        assertEquals(Vec3.ZERO, FrozenReactionRenderer.renderOffset(null), "Offset capture must be cleared when rendering ends");
        FrozenReactionRenderer.endRender(null);
    }

    @Test
    void collisionIceTextureRepeatsAtBlockScaleWithoutStretchingDuringGrowth() throws Exception {
        var hitbox = new AABB(-0.45, 0, -0.45, 0.45, 2.9, 0.45);
        for (float growth : new float[]{0.25F, 1}) {
            var vertices = new RecordedVertices();
            drawBox(hitbox, new com.mojang.blaze3d.vertex.PoseStack(), vertices, 0, growth, 1);
            for (int index = 0; index < 24; index += 4) {
                var a = vertices.positions.get(index);
                var b = vertices.positions.get(index + 1);
                var d = vertices.positions.get(index + 3);
                var uv = vertices.uvs.get(index);
                assertEquals(a.distanceTo(b), uv.distanceTo(vertices.uvs.get(index + 1)), 0.000001);
                assertEquals(a.distanceTo(d), uv.distanceTo(vertices.uvs.get(index + 3)), 0.000001);
            }
        }
    }

    @Test
    void collisionIceRejectsInvalidBoundsAndInvisibleAnimationStates() throws Exception {
        var pose = new com.mojang.blaze3d.vertex.PoseStack();
        for (var hitbox : List.of(new AABB(0, 0, 0, 0, 1, 1), new AABB(0, 0, 0, 1, 0, 1),
                new AABB(0, 0, 0, 1, 1, 0), new AABB(Double.NaN, 0, 0, 1, 1, 1),
                new AABB(0, 0, 0, Double.POSITIVE_INFINITY, 1, 1))) {
            var vertices = new RecordedVertices();
            assertTrue(drawBox(hitbox, pose, vertices, 0, 1, 1).isEmpty());
            assertTrue(vertices.positions.isEmpty());
        }
        var hitbox = new AABB(-0.45, 0, -0.45, 0.45, 2.9, 0.45);
        for (float[] animation : List.of(new float[]{0, 1}, new float[]{1, 0}, new float[]{1, 0.001F},
                new float[]{-1, 1}, new float[]{1, -1}, new float[]{Float.NaN, 1},
                new float[]{1, Float.NaN}, new float[]{Float.POSITIVE_INFINITY, 1})) {
            var vertices = new RecordedVertices();
            assertTrue(drawBox(hitbox, pose, vertices, 0, animation[0], animation[1]).isEmpty());
            assertTrue(vertices.positions.isEmpty());
        }
        var tiny = new RecordedVertices();
        drawBox(hitbox, pose, tiny, 0, 0.0000001F, 0.5F);
        assertTrue(tiny.normals.stream().allMatch(normal -> Double.isFinite(normal.x)
                && Double.isFinite(normal.y) && Double.isFinite(normal.z)));
    }

    @Test
    void collisionIceClampsAnimationAndDoesNotShareGeometryBetweenEntities() throws Exception {
        var pose = new com.mojang.blaze3d.vertex.PoseStack();
        var hitbox = new AABB(-0.45, 0, -0.45, 0.45, 2.9, 0.45);
        var full = new RecordedVertices();
        var excess = new RecordedVertices();
        drawBox(hitbox, pose, full, 0, 1, 1);
        drawBox(hitbox, pose, excess, 0, 2, 2);
        assertEquals(full.positions, excess.positions);
        assertEquals(full.alphas, excess.alphas);
        var other = new RecordedVertices();
        drawBox(new AABB(-3, 0, -3, 3, 1, 3), pose, other, 0, 1, 1);
        var repeated = new RecordedVertices();
        drawBox(hitbox, pose, repeated, 0, 1, 1);
        assertEquals(full.positions, repeated.positions, "Another entity's size must not alter the first entity's block");
    }

    @Test
    void particleAnchorsStayOnTheCollisionIceSurfaceAtEveryGrowthStage() throws Exception {
        var hitbox = new AABB(-0.45, 0, -0.45, 0.45, 2.9, 0.45);
        for (float growth : new float[]{0.25F, 1}) {
            var vertices = new RecordedVertices();
            var anchors = drawBox(hitbox, new com.mojang.blaze3d.vertex.PoseStack(), vertices, 0, growth, 1);
            assertEquals(6, anchors.size());
            double minY = vertices.positions.stream().mapToDouble(point -> point.y).min().orElseThrow();
            double maxY = vertices.positions.stream().mapToDouble(point -> point.y).max().orElseThrow();
            var expanded = hitbox.inflate(0.04);
            for (var point : anchors) {
                assertTrue(Float.isFinite(point.x()) && Float.isFinite(point.y()) && Float.isFinite(point.z()));
                assertTrue(point.y() >= minY - 0.000001 && point.y() <= maxY + 0.000001);
                assertTrue(point.x() >= expanded.minX - 0.000001 && point.x() <= expanded.maxX + 0.000001);
                assertTrue(point.z() >= expanded.minZ - 0.000001 && point.z() <= expanded.maxZ + 0.000001);
                assertTrue(Math.abs(point.x() - expanded.minX) < 0.000001 || Math.abs(point.x() - expanded.maxX) < 0.000001
                        || Math.abs(point.z() - expanded.minZ) < 0.000001 || Math.abs(point.z() - expanded.maxZ) < 0.000001,
                        "Cold mist and thaw particles must originate on the actual ice block");
            }
        }
    }

    @Test
    void freezeAppearsFullyOnTheFirstFrameAndThawsWithoutRestrictingMovement() throws Exception {
        var state = new ClientFrozenStateManager.ClientFrozenState(100, 20, 120);
        var vertices = new RecordedVertices();
        drawBox(new AABB(-0.45, 0, -0.45, 0.45, 2.9, 0.45),
                new com.mojang.blaze3d.vertex.PoseStack(), vertices, 0, state.growth(20), state.opacity(20));
        assertEquals(24, vertices.positions.size(), "The first frozen frame must already contain the complete ice block");
        assertEquals(2.94, vertices.positions.stream().mapToDouble(point -> point.y).max().orElseThrow(), 0.000001);
        assertEquals(1, state.growth(20), 0.0001);
        assertEquals(1, state.growth(20.1), 0.0001);
        assertEquals(1, state.growth(26), 0.0001);
        assertEquals(1, state.opacity(120), 0.0001);
        assertTrue(state.opacity(123) > 0 && state.opacity(123) < 1);
        var movement = new Vec3(0.3, -0.8, -0.6);
        org.junit.jupiter.api.Assertions.assertSame(movement, state.constrainMovement(movement, 123));
        assertEquals(0, state.opacity(125), 0.0001);
    }

    @Test
    void stopPacketReleasesMovementImmediatelyButKeepsBriefThawVisuals() {
        var manager = ClientFrozenStateManager.INSTANCE;
        manager.clear();
        try {
            manager.start(7, 100, 100, 20);
            manager.stop(7, 35);
            var state = manager.state(7, 35);
            org.junit.jupiter.api.Assertions.assertNotNull(state);
            assertFalse(state.active(35));
            assertTrue(state.visible(35));
            var movement = new Vec3(0.3, -0.8, -0.6);
            org.junit.jupiter.api.Assertions.assertSame(movement, state.constrainMovement(movement, 35));
            manager.stop(7, 37);
            assertEquals(state.expiresAt(), manager.state(7, 37).expiresAt(), "Repeated stop packets must not extend the visual tail");
            org.junit.jupiter.api.Assertions.assertNull(manager.state(7, 40));
        } finally {
            manager.clear();
        }
    }

    @Test
    void particleTimingIsIndependentOfRenderFrequencyAndThawOnlyBurstsOnce() {
        var clock = new FrozenReactionRenderer.ParticleClock();
        var state = new ClientFrozenStateManager.ClientFrozenState(100, 20, 120);
        int starts = 0, mists = 0, thaws = 0;
        for (long now = 20; now <= 130; now++) {
            int events = clock.step(state, now);
            if ((events & 1) != 0) starts++;
            if ((events & 2) != 0) mists++;
            if ((events & 4) != 0) thaws++;
            for (int frame = 0; frame < 10; frame++) assertEquals(0, clock.step(state, now));
        }
        assertEquals(0, starts, "Freeze creation must not emit particles");
        assertEquals(0, mists, "Frozen entities must not continuously emit particles");
        assertEquals(1, thaws);
    }

    @Test
    void continuousFreezeRefreshKeepsTheVisualStartAndOnlyExtendsTheDeadline() {
        var manager = ClientFrozenStateManager.INSTANCE;
        manager.clear();
        try {
            for (long now = 20; now <= 32; now += 2) {
                manager.start(7, 100, 100, now);
                var state = manager.state(7, now);
                assertEquals(20, state.startedAt(), "Refreshing an active freeze must preserve the same visual cycle");
                assertEquals(now + 100, state.expiresAt());
            }
            assertEquals(1, manager.state(7, 32).growth(32), 0.0001);
            manager.stop(7, 35);
            manager.start(7, 100, 100, 37);
            assertEquals(37, manager.state(7, 37).startedAt(), "A new freeze after thaw must start a new visual cycle");
            assertEquals(1, manager.state(7, 37).growth(37), 0.0001,
                    "A new freeze must also appear fully on its first frame");
        } finally {
            manager.clear();
        }
    }

    @Test
    void particleAnchorsAreClearedOnWorldChangeAndLogout() throws Exception {
        var field = FrozenReactionRenderer.class.getDeclaredField("VISUALS");
        field.setAccessible(true);
        var visuals = (java.util.Map<?, ?>) field.get(null);
        var manager = ClientFrozenStateManager.INSTANCE;
        manager.updateWorldToken(new Object());
        var points = List.of(new FrostShellRenderer.Point(0, 1, 0));
        FrozenReactionRenderer.observe(7, new java.util.UUID(1, 7), 20, 20, points);
        assertEquals(1, visuals.size());
        manager.updateWorldToken(new Object());
        assertTrue(visuals.isEmpty());
        FrozenReactionRenderer.observe(7, new java.util.UUID(1, 7), 20, 20, points);
        ClientDamagePopupRenderer.logout(null);
        assertTrue(visuals.isEmpty());
        manager.updateWorldToken(null);
    }

    private static List<FrostShellRenderer.Point> drawBox(AABB hitbox, com.mojang.blaze3d.vertex.PoseStack pose,
            RecordedVertices vertices, int light, float growth, float opacity) throws Exception {
        return FrostShellRenderer.renderBox(hitbox, pose, vertices, light, growth, opacity, Vec3.ZERO);
    }

    private static final class RecordedVertices implements com.mojang.blaze3d.vertex.VertexConsumer {
        private final List<Vec3> positions = new ArrayList<>();
        private final List<Integer> alphas = new ArrayList<>();
        private final List<Vec3> normals = new ArrayList<>();
        private final List<Vec3> colours = new ArrayList<>();
        private final List<Integer> lights = new ArrayList<>();
        private final List<Vec3> uvs = new ArrayList<>();
        private Vec3 position;
        private Vec3 normal = Vec3.ZERO;
        private int alpha = 255;
        private Vec3 colour = new Vec3(255, 255, 255);
        private int light;
        private Vec3 uv = Vec3.ZERO;

        @Override public com.mojang.blaze3d.vertex.VertexConsumer vertex(double x, double y, double z) {
            position = new Vec3(x, y, z);
            return this;
        }
        @Override public com.mojang.blaze3d.vertex.VertexConsumer color(int r, int g, int b, int a) {
            alpha = a; colour = new Vec3(r, g, b); return this;
        }
        @Override public com.mojang.blaze3d.vertex.VertexConsumer uv(float u, float v) { uv = new Vec3(u, v, 0); return this; }
        @Override public com.mojang.blaze3d.vertex.VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public com.mojang.blaze3d.vertex.VertexConsumer uv2(int u, int v) { light = u | v << 16; return this; }
        @Override public com.mojang.blaze3d.vertex.VertexConsumer normal(float x, float y, float z) {
            normal = new Vec3(x, y, z); return this;
        }
        @Override public void endVertex() {
            positions.add(position); alphas.add(alpha); normals.add(normal); colours.add(colour); lights.add(light); uvs.add(uv); alpha = 255;
        }
        @Override public void defaultColor(int r, int g, int b, int a) { alpha = a; }
        @Override public void unsetDefaultColor() { alpha = 255; }
    }
}

class DamagePopupManagerTest {
    @Test
    void visualGracePeriodDoesNotExtendTheMovementRestriction() {
        var state = new ClientFrozenStateManager.ClientFrozenState(20, 100, 120);
        var movement = new Vec3(0.3, -0.8, -0.6);
        assertEquals(new Vec3(0, -0.8, 0), state.constrainMovement(movement, 120));
        assertTrue(state.visible(121));
        assertFalse(state.active(121));
        org.junit.jupiter.api.Assertions.assertSame(movement, state.constrainMovement(movement, 121));
        assertFalse(state.visible(126));
    }

    @Test
    void resourceReloadPreservesFreezeWhileLogoutAndWorldChangesClearIt() throws Exception {
        var frozen = ClientFrozenStateManager.INSTANCE;
        var world = new Object();
        frozen.updateWorldToken(world);
        var field = ClientFrozenStateManager.class.getDeclaredField("states");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var states = (it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<ClientFrozenStateManager.ClientFrozenState>)
                field.get(frozen);
        var state = new ClientFrozenStateManager.ClientFrozenState(100, 0, 100);
        states.put(7, state);
        try (var resources = new net.minecraft.server.packs.resources.ReloadableResourceManager(
                net.minecraft.server.packs.PackType.CLIENT_RESOURCES)) {
            ClientDamagePopupRenderer.ClientModEvents.registerReloadListeners(
                    new net.minecraftforge.client.event.RegisterClientReloadListenersEvent(resources));
            resources.createReload(Runnable::run, Runnable::run,
                    java.util.concurrent.CompletableFuture.completedFuture(net.minecraft.util.Unit.INSTANCE),
                    List.of()).done().join();
            org.junit.jupiter.api.Assertions.assertSame(state, frozen.state(7, 50));
            frozen.updateWorldToken(world);
            org.junit.jupiter.api.Assertions.assertSame(state, frozen.state(7, 50));
            frozen.updateWorldToken(new Object());
            org.junit.jupiter.api.Assertions.assertNull(frozen.state(7, 50));
            states.put(7, state);
            ClientDamagePopupRenderer.logout(null);
            org.junit.jupiter.api.Assertions.assertNull(frozen.state(7, 50));
        } finally {
            frozen.updateWorldToken(null);
            frozen.clear();
        }
    }

    @Test
    void spawnRegionStaysFixedWhenTheEntityLimitChanges() {
        var latestSpawnPositions = new ArrayList<List<Double>>();
        for (int limit : List.of(3, 9, 0)) {
            var manager = new DamagePopupManager(() -> 96, () -> limit);
            for (int damage = 1; damage <= 14; damage++) {
                add(manager, packet(7, 1, 2, 3, damage), 100);
            }
            for (var popup : active(manager, 100)) {
                assertTrue(popup.verticalOffset() >= -24.0 && popup.verticalOffset() <= 0.0);
                assertTrue(Math.abs(popup.horizontalOffset()) <= 6.0);
            }
            for (double now : List.of(100.0, 120.0, 134.5)) {
                manager.forEachActive(now, (popup, x, y, alpha) -> {
                    assertTrue(y >= -18.0 && y <= 24.0);
                    assertTrue(Math.abs(x) <= 12.0);
                });
            }
            var latest = active(manager, 100).get(active(manager, 100).size() - 1);
            latestSpawnPositions.add(List.of(latest.horizontalOffset(), latest.verticalOffset()));
        }
        assertEquals(latestSpawnPositions.get(0), latestSpawnPositions.get(1));
        assertEquals(latestSpawnPositions.get(1), latestSpawnPositions.get(2));
    }

    @Test
    void laterHitSpawnsAtTheHitRegionRatherThanAboveTheOlderRisingNumber() {
        var manager = new DamagePopupManager();
        add(manager, packet(7, 1, 2, 3, 10), 100);
        add(manager, packet(7, 1, 2, 3, 20), 120);
        var popup = active(manager, 120).get(1);
        assertTrue(popup.verticalOffset() >= -24.0 && popup.verticalOffset() <= 0.0);
        assertEquals(new Vec3(1, 2.2, 3), popup.anchor());
    }

    @Test
    void aNewHitAtTheEntityLimitLeavesAllSurvivingTrajectoriesUnchanged() {
        var manager = new DamagePopupManager();
        for (int damage = 1; damage <= 9; damage++) {
            add(manager, packet(7, 1, 2, 3, damage), 100);
        }
        var previous = active(manager, 100);
        assertEquals(9, previous.stream().map(popup ->
                List.of(popup.horizontalOffset(), popup.verticalOffset())).distinct().count());
        var previousPositions = new ArrayList<List<Double>>();
        manager.forEachActive(100.5, (popup, x, y, alpha) -> previousPositions.add(List.of(x, y)));
        add(manager, packet(7, 1, 2, 3, 10), 100.5);
        var current = active(manager, 100.5);
        assertEquals(previous.subList(1, 9), current.subList(0, 8));
        var currentPositions = new ArrayList<List<Double>>();
        manager.forEachActive(100.5, (popup, x, y, alpha) -> currentPositions.add(List.of(x, y)));
        assertEquals(previousPositions.subList(1, 9), currentPositions.subList(0, 8));
    }

    @Test
    void eachPopupDriftsSidewaysSmoothlyWithinTheHitRegion() {
        var manager = new DamagePopupManager();
        add(manager, packet(7, 1, 2, 3, 20), 100);
        var popup = active(manager, 100).get(0);
        var horizontalPositions = new ArrayList<Double>();
        var verticalPositions = new ArrayList<Double>();
        for (double now : List.of(100.0, 100.5, 101.0, 110.0, 120.0, 130.0, 134.5)) {
            manager.forEachActive(now, (current, x, y, alpha) -> {
                assertEquals(popup, current);
                assertTrue(Math.abs(x) <= 12.0);
                assertTrue(y >= -18.0 && y <= 24.0);
                horizontalPositions.add(x);
                verticalPositions.add(y);
            });
        }
        assertEquals(popup.horizontalOffset(), horizontalPositions.get(0));
        assertTrue(horizontalPositions.stream().mapToDouble(Double::doubleValue).max().orElseThrow()
                - horizontalPositions.stream().mapToDouble(Double::doubleValue).min().orElseThrow() > 0.1);
        assertTrue(Math.abs(horizontalPositions.get(1) - horizontalPositions.get(0)) < 0.5);
        assertTrue(Math.abs(horizontalPositions.get(2) - horizontalPositions.get(1)) < 0.5);
        for (int index = 1; index < verticalPositions.size(); index++) {
            assertTrue(verticalPositions.get(index) < verticalPositions.get(index - 1));
        }
    }

    @Test
    void defaultEntityLimitRetainsOnlyTheLatestNineIndividualDamageValues() {
        var manager = new DamagePopupManager();
        for (int damage = 1; damage <= 12; damage++) {
            add(manager, packet(7, 1, 2, 3, damage), 100);
        }
        assertEquals(List.of(4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0), active(manager, 100).stream()
                .map(popup -> popup.packet().damage()).toList());
    }

    @Test
    void crowdedEntitiesEachRetainTheirOwnLatestNinePopups() {
        var manager = new DamagePopupManager();
        for (int damage = 1; damage <= 12; damage++) {
            for (int entity = 7; entity <= 11; entity++) {
                add(manager, packet(entity, entity * 2, 2, 3, damage), 100);
            }
        }
        var popups = active(manager, 100);
        assertEquals(45, popups.size());
        for (int entity = 7; entity <= 11; entity++) {
            int entityId = entity;
            assertEquals(List.of(4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0), popups.stream()
                    .filter(popup -> popup.packet().entityId() == entityId)
                    .map(popup -> popup.packet().damage()).toList());
        }
    }

    @Test
    void continuousHitsStayNearTheHitPositionWithoutMovingSurvivingPopups() {
        var manager = new DamagePopupManager();
        add(manager, packet(7, 1, 2, 3, 10), 100);
        add(manager, packet(7, 1, 2, 3, 20), 120);
        var survivor = active(manager, 120).get(1);
        add(manager, packet(7, 1, 2, 3, 30), 120);
        var popups = active(manager, 120);
        assertTrue(popups.stream().allMatch(popup ->
                popup.verticalOffset() >= -24.0 && popup.verticalOffset() <= 0.0));
        assertTrue(popups.contains(survivor));
        for (int i = 0; i < 60; i++) {
            double now = 120.5 + i * 0.5;
            add(manager, packet(7, 1, 2, 3, 40 + i), now);
            assertTrue(active(manager, now).stream().allMatch(popup ->
                    popup.verticalOffset() >= -24.0 && popup.verticalOffset() <= 0.0));
            var rows = new ArrayList<Double>();
            manager.forEachActive(now, (popup, x, y, alpha) -> rows.add(y));
            assertTrue(rows.size() <= 9);
            assertTrue(rows.stream().allMatch(y -> y >= -18.0 && y <= 24.0));
        }
    }

    @Test
    void replacingAnEntityPopupDoesNotEvictAnotherEntityAtTheGlobalLimit() {
        var manager = new DamagePopupManager(() -> 10);
        add(manager, packet(8, 11, 2, 3, 30), 100);
        for (int damage = 1; damage <= 10; damage++) {
            add(manager, packet(7, 1, 2, 3, damage), 100);
        }
        assertEquals(List.of(8, 7, 7, 7, 7, 7, 7, 7, 7, 7), active(manager, 100).stream()
                .map(popup -> popup.packet().entityId()).toList());
        assertEquals(List.of(30.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0), active(manager, 100).stream()
                .map(popup -> popup.packet().damage()).toList());
    }

    @Test
    void loweringTheEntityLimitKeepsTheNewestRowsWithoutRepositioningThem() {
        var limit = new AtomicInteger(4);
        var manager = new DamagePopupManager(() -> 96, limit::get);
        for (int entity : List.of(7, 8)) {
            for (int damage = 1; damage <= 4; damage++) {
                add(manager, packet(entity, entity, 2, 3, damage), 100);
            }
        }
        var survivors = active(manager, 100).stream()
                .filter(popup -> popup.packet().damage() >= 3).toList();
        limit.set(2);
        assertEquals(survivors, active(manager, 100.5));
    }

    @Test
    void theEntityLimitCanBeRaisedAndThenDisabled() {
        var limit = new AtomicInteger(1);
        var manager = new DamagePopupManager(() -> 96, limit::get);
        for (int damage = 1; damage <= 3; damage++) {
            add(manager, packet(7, 1, 2, 3, damage), 100);
        }
        assertEquals(List.of(3.0), active(manager, 100).stream()
                .map(popup -> popup.packet().damage()).toList());
        limit.set(3);
        for (int damage = 4; damage <= 5; damage++) {
            add(manager, packet(7, 1, 2, 3, damage), 100);
        }
        assertEquals(List.of(3.0, 4.0, 5.0), active(manager, 100).stream()
                .map(popup -> popup.packet().damage()).toList());
        limit.set(0);
        for (int damage = 6; damage <= 9; damage++) {
            add(manager, packet(7, 1, 2, 3, damage), 100);
        }
        assertEquals(List.of(3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0), active(manager, 100).stream()
                .map(popup -> popup.packet().damage()).toList());
    }

    @Test
    void aSingleGlobalSlotKeepsEachReplacementWithinTheHitRegion() {
        var manager = new DamagePopupManager(() -> 1);
        add(manager, packet(7, 1, 2, 3, 10), 100);
        add(manager, packet(7, 1, 2, 3, 20), 100);
        var popup = active(manager, 100).get(0);
        assertEquals(20.0, popup.packet().damage());
        assertTrue(Math.abs(popup.horizontalOffset()) <= 6.0);
        assertTrue(popup.verticalOffset() >= -24.0 && popup.verticalOffset() <= 0.0);
    }

    @Test
    void hiddenTinyDamageDoesNotEvictAPopupAtTheEntityLimit() {
        var manager = new DamagePopupManager();
        for (int damage = 1; damage <= 9; damage++) {
            add(manager, packet(7, 1, 2, 3, damage), 100);
        }
        var earlier = active(manager, 100);
        add(manager, packet(7, 5, 6, 7, 0.1F), 100);
        assertEquals(earlier, active(manager, 100));
    }

    @Test
    void nearbyPopupsUseSmallOffsetsOnBothSidesOfTheHitPosition() {
        var manager = new DamagePopupManager(() -> 96, () -> 0);
        for (int i = 0; i < 12; i++) {
            add(manager, packet(7, 1, 2, 3, 20), 100);
        }
        var offsets = new ArrayList<Double>();
        manager.forEachActive(100, (popup, x, y, alpha) -> offsets.add(x));
        double minimum = offsets.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        double maximum = offsets.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        assertTrue(minimum >= -6.0 && minimum < 0.0);
        assertTrue(maximum <= 6.0 && maximum > 0.0);
    }

    @Test
    void simultaneousPopupsSpawnAtOrBelowTheSameHitPositionWithoutFormingATallStack() {
        var manager = new DamagePopupManager(() -> 96, () -> 0);
        for (int i = 0; i < 9; i++) {
            add(manager, packet(7, 1, 2, 3, 20), 100);
        }
        var rows = new ArrayList<Double>();
        manager.forEachActive(100, (popup, x, y, alpha) -> rows.add(y));
        assertEquals(9, rows.size());
        assertTrue(rows.stream().allMatch(y -> y >= 0.0 && y <= 24.0));
    }

    @Test
    void anotherEntityStartsAtItsOwnHitPositionWithoutShiftingExistingRows() {
        var manager = new DamagePopupManager();
        for (int i = 0; i < 2; i++) {
            add(manager, packet(7, 1, 2, 3, 20), 100);
        }
        var earlier = active(manager, 100);
        add(manager, packet(8, 11, 2, 3, 20), 100);
        var popups = active(manager, 100);
        assertTrue(popups.get(2).verticalOffset() >= -24.0 && popups.get(2).verticalOffset() <= 0.0);
        assertEquals(earlier, popups.subList(0, 2));
    }

    @Test
    void distantPopupMovementShrinksAtTheSameRateAsTheEntity() {
        double previousDistance = 0.0;
        double previousMovement = 0.0;
        double previousEntityHeight = 0.0;
        for (double distance : List.of(8.0, 16.0, 64.0)) {
            var projected = new DamagePopupPlacement.ScreenPoint();
            var entityTop = new DamagePopupPlacement.ScreenPoint();
            var projection = new Matrix4f().perspective((float) Math.toRadians(70.0),
                    640.0F / 360.0F, 0.05F, 1024.0F);
            assertTrue(DamagePopupPlacement.projectToScreen(
                    0, 0, distance, 0, 0, 0,
                    -1, 0, 0, 0, 1, 0, 0, 0, 1,
                    projection, 640, 360, projected));
            assertTrue(DamagePopupPlacement.projectToScreen(
                    0, 2, distance, 0, 0, 0,
                    -1, 0, 0, 0, 1, 0, 0, 0, 1,
                    projection, 640, 360, entityTop));
            projected.offset(2.0, -20.0, distance * distance);
            assertEquals(320.0, projected.x(), 1.0E-9);
            double movement = 180.0 - projected.y();
            double entityHeight = 180.0 - entityTop.y();
            if (previousDistance > 0.0) {
                assertEquals(previousMovement * previousDistance / distance, movement, 1.0E-6);
                assertEquals(previousMovement / previousEntityHeight, movement / entityHeight, 1.0E-6);
            }
            previousDistance = distance;
            previousMovement = movement;
            previousEntityHeight = entityHeight;
        }
    }

    @Test
    void distantPopupMovementUsesTheSameFieldOfViewAsTheEntity() {
        double previousMovementRatio = 0.0;
        for (double fieldOfView : List.of(70.0, 90.0, 110.0)) {
            var projected = new DamagePopupPlacement.ScreenPoint();
            var entityTop = new DamagePopupPlacement.ScreenPoint();
            var projection = new Matrix4f().perspective((float) Math.toRadians(fieldOfView),
                    640.0F / 360.0F, 0.05F, 1024.0F);
            assertTrue(DamagePopupPlacement.projectToScreen(
                    0, 0, 32, 0, 0, 0,
                    -1, 0, 0, 0, 1, 0, 0, 0, 1,
                    projection, 640, 360, projected));
            assertTrue(DamagePopupPlacement.projectToScreen(
                    0, 2, 32, 0, 0, 0,
                    -1, 0, 0, 0, 1, 0, 0, 0, 1,
                    projection, 640, 360, entityTop));
            projected.offset(0, 24, 32.0 * 32.0);
            double movementRatio = (projected.y() - 180.0) / (180.0 - entityTop.y());
            if (previousMovementRatio > 0.0) assertEquals(previousMovementRatio, movementRatio, 1.0E-6);
            previousMovementRatio = movementRatio;
        }
    }

    @Test
    void distantPopupMovementScalesWithTheProjectedViewport() {
        double previousMovement = 0.0;
        for (int height : List.of(180, 360, 720)) {
            var projected = new DamagePopupPlacement.ScreenPoint();
            var projection = new Matrix4f().perspective((float) Math.toRadians(70.0),
                    16.0F / 9.0F, 0.05F, 1024.0F);
            assertTrue(DamagePopupPlacement.projectToScreen(
                    0, 0, 32, 0, 0, 0,
                    -1, 0, 0, 0, 1, 0, 0, 0, 1,
                    projection, height * 16 / 9, height, projected));
            projected.offset(0, -18, 32.0 * 32.0);
            double movement = height * 0.5 - projected.y();
            if (previousMovement > 0.0) assertEquals(previousMovement * 2.0, movement, 1.0E-6);
            previousMovement = movement;
        }
    }

    @Test
    void laterHitsLeaveAnOlderPopupFollowingItsOwnRise() {
        var manager = new DamagePopupManager();
        add(manager, packet(7, 1, 2, 3, 20), 100);
        var earlier = active(manager, 100).get(0);
        add(manager, packet(7, 1, 2, 3, 20), 120);
        var later = active(manager, 120).get(1);
        for (double now : List.of(120.0, 120.5, 121.0)) {
            var rows = new ArrayList<Double>();
            manager.forEachActive(now, (popup, x, y, alpha) -> rows.add(y));
            assertEquals(-earlier.verticalOffset() - (now - 100.0) / 35.0 * 18.0, rows.get(0), 1.0E-9);
            assertEquals(-later.verticalOffset() - (now - 120.0) / 35.0 * 18.0, rows.get(1), 1.0E-9);
        }
        assertEquals(earlier, active(manager, 121).get(0));
    }

    @Test
    void unlimitedDenseHitsReuseTheSameSmallRegionAndRemainVisibleWhenOverlapping() {
        var manager = new DamagePopupManager(() -> 96, () -> 0);
        for (int i = 0; i < 12; i++) {
            add(manager, packet(7, 1, 2, 3, 20), 100);
        }
        var rows = new ArrayList<Double>();
        manager.forEachActive(100, (popup, x, y, alpha) -> rows.add(y));
        assertEquals(12, rows.size());
        assertTrue(rows.stream().allMatch(y -> y >= 0.0 && y <= 24.0));
    }

    @Test
    void expiredPopupsDisappearWithoutRepositioningSurvivingPopups() {
        var manager = new DamagePopupManager();
        add(manager, packet(7, 1, 2, 3, 20), 100);
        add(manager, packet(7, 1, 2, 3, 20), 120);
        var survivor = active(manager, 140).get(0);
        add(manager, packet(7, 1, 2, 3, 20), 140);
        var popups = active(manager, 140);
        assertEquals(2, popups.size());
        assertEquals(survivor, popups.get(0));
        assertEquals(140.0, popups.get(1).createdAt());
    }

    @Test
    void closePopupsSpreadGentlyAndDistantPopupsStayCentered() {
        double previousOffset = Double.POSITIVE_INFINITY;
        for (double distance : List.of(1.0, 2.0, 4.0, 6.0, 8.0, 16.0, 64.0)) {
            var projected = new DamagePopupPlacement.ScreenPoint();
            assertTrue(DamagePopupPlacement.projectToScreen(
                    0, 0, distance, 0, 0, 0,
                    -1, 0, 0, 0, 1, 0, 0, 0, 1,
                    new Matrix4f().perspective((float) Math.toRadians(70.0),
                            640.0F / 360.0F, 0.05F, 1024.0F), 640, 360, projected));
            projected.offset(6.0, -12.0, distance * distance);
            double offset = projected.x() - 320.0;
            assertTrue(offset <= previousOffset);
            if (distance < 8.0) assertTrue(offset > 0.0 && offset <= 12.0);
            else assertEquals(0.0, offset, 1.0E-9);
            previousOffset = offset;
        }
    }

    @Test
    void popupFontShrinksWithDistanceAndPreservesTheConfiguredBaseScale() {
        assertEquals(1.0F, DamagePopupPlacement.fontScale(1.0F, 4.0 * 4.0), 1.0E-6F);
        assertEquals(0.5F, DamagePopupPlacement.fontScale(1.0F, 16.0 * 16.0), 1.0E-6F);
        assertEquals(0.25F, DamagePopupPlacement.fontScale(1.0F, 64.0 * 64.0), 1.0E-6F);
        assertEquals(2.5F, DamagePopupPlacement.fontScale(2.5F, 4.0 * 4.0), 1.0E-6F);
        assertEquals(1.25F, DamagePopupPlacement.fontScale(2.5F, 16.0 * 16.0), 1.0E-6F);
    }

    @Test
    void popupFontStaysBoundedWhenTheCameraIsVeryClose() {
        for (double distanceSquared : List.of(0.0, 1.0E-12, 0.25, 1.0)) {
            assertEquals(2.0F, DamagePopupPlacement.fontScale(1.0F, distanceSquared), 1.0E-6F);
            assertEquals(6.0F, DamagePopupPlacement.fontScale(3.0F, distanceSquared), 1.0E-6F);
        }
        assertTrue(DamagePopupPlacement.fontScale(1.0F, 2.0 * 2.0) > 1.0F);
        assertTrue(DamagePopupPlacement.fontScale(1.0F, 2.0 * 2.0) < 2.0F);
    }

    @Test
    void popupFontDistanceChangesSmoothlyBetweenFrames() {
        float first = DamagePopupPlacement.fontScale(1.0F, 4.0 * 4.0);
        float middle = DamagePopupPlacement.fontScale(1.0F, 4.05 * 4.05);
        float last = DamagePopupPlacement.fontScale(1.0F, 4.1 * 4.1);
        assertTrue(first > middle);
        assertTrue(middle > last);
        assertTrue(first - last < 0.02F);
    }

    @Test
    void movingTheCameraChangesFontScaleWithoutMovingTheSavedWorldAnchor() {
        var manager = new DamagePopupManager();
        add(manager, packet(7, 1, 2, 3, 20), 100);
        var popup = active(manager, 100).get(0);
        var anchor = popup.anchor();
        assertEquals(1.0F, DamagePopupPlacement.fontScale(1.0F,
                anchor.distanceToSqr(anchor.add(0, 0, 4))), 1.0E-6F);
        assertEquals(0.5F, DamagePopupPlacement.fontScale(1.0F,
                anchor.distanceToSqr(anchor.add(0, 0, 16))), 1.0E-6F);
        assertEquals(anchor, active(manager, 100.5).get(0).anchor());
    }

    @Test
    void tinyDamageNeverCreatesAPopup() {
        var manager = new DamagePopupManager();
        for (double damage : List.of(0.0, 0.01, 0.1, (double) 0.1F)) {
            add(manager, packet(7, 1, 2, 3, damage), 100);
        }
        assertTrue(active(manager, 100).isEmpty());
    }

    @Test
    void damageAboveTheFloatThresholdStillCreatesPopups() {
        var manager = new DamagePopupManager();
        add(manager, packet(7, 1, 2, 3, Math.nextUp(0.1F)), 100);
        add(manager, packet(7, 1, 2, 3, 20), 100);
        assertEquals(2, active(manager, 100).size());
    }

    @Test
    void unlimitedEntityModeCanShowMoreThanNinePopups() {
        var manager = new DamagePopupManager(() -> 96, () -> 0);
        for (int i = 0; i < 12; i++) {
            add(manager, packet(7, i, 2, 3, 20), 100);
        }
        assertEquals(12, active(manager, 100).size());
    }

    @Test
    void hiddenTinyDamageDoesNotEvictAnExistingPopup() {
        var manager = new DamagePopupManager(() -> 1);
        add(manager, packet(7, 1, 2, 3, 20), 100);
        add(manager, packet(7, 5, 6, 7, 0.1F), 100);
        assertEquals(20, active(manager, 100).get(0).packet().damage());
    }

    @Test
    void capturesPacketPositionUsingTheEntitySizeAndConfiguredHeight() {
        var manager = new DamagePopupManager();
        var bounds = new AABB(19, 39, 59, 21, 41, 61);
        manager.add(packet(7, 1, 2, 3, 20), Component.literal("damage"), 20, 100, bounds, 0.75);
        var popup = active(manager, 100).get(0);
        assertEquals(new Vec3(1, 2.5, 3), popup.anchor());
        assertEquals(new AABB(0, 1, 2, 2, 3, 4), popup.bounds());
    }

    @Test
    void laterEntityPositionAndHeightChangesLeaveEarlierPopupsInPlace() {
        var manager = new DamagePopupManager();
        manager.add(packet(7, 1, 2, 3, 20), Component.literal("damage"), 20, 100,
                new AABB(0, 1, 2, 2, 3, 4), 0.75);
        var earlier = active(manager, 100).get(0);
        manager.add(packet(7, 11, 12, 13, 20), Component.literal("damage"), 20, 101,
                new AABB(10, 10, 12, 12, 14, 14), 1.0);
        var popups = active(manager, 101.5);
        assertEquals(2, popups.size());
        assertEquals(new Vec3(1, 2.5, 3), popups.get(0).anchor());
        assertEquals(new AABB(0, 1, 2, 2, 3, 4), popups.get(0).bounds());
        assertEquals(earlier, popups.get(0));
        assertEquals(new Vec3(11, 14, 13), popups.get(1).anchor());
        assertNotEquals(popups.get(0).id(), popups.get(1).id());
    }

    @Test
    void riseAndFadeAdvanceBetweenTicksWithoutChangingTheWorldAnchor() {
        var manager = new DamagePopupManager();
        add(manager, packet(7, 1, 2, 3, 20), 100);
        var anchor = active(manager, 100).get(0).anchor();
        var positions = new ArrayList<Double>();
        var alphas = new ArrayList<Integer>();
        for (double now : List.of(100.0, 100.5, 101.0, 130.0, 130.5, 134.5)) {
            manager.forEachActive(now, (popup, x, y, alpha) -> {
                assertEquals(anchor, popup.anchor());
                positions.add(y);
                alphas.add(alpha);
            });
        }
        assertTrue(positions.get(0) > positions.get(1));
        assertTrue(positions.get(1) > positions.get(2));
        assertEquals((positions.get(0) + positions.get(2)) * 0.5, positions.get(1), 1.0E-9);
        assertTrue(alphas.get(3) > alphas.get(4));
        assertTrue(alphas.get(4) > alphas.get(5));
        assertTrue(active(manager, 135).isEmpty());
    }

    @Test
    void globalLimitRetainsTheNewestPopupsAcrossDifferentEntities() {
        var manager = new DamagePopupManager(() -> 2);
        add(manager, packet(7, 1, 2, 3, 10), 100);
        add(manager, packet(8, 1, 2, 3, 20), 100);
        add(manager, packet(9, 1, 2, 3, 30), 100);
        assertEquals(List.of(8, 9), active(manager, 100).stream().map(popup -> popup.packet().entityId()).toList());
    }

    @Test
    void changingWorldClearsExistingPopups() {
        var manager = new DamagePopupManager();
        var world = new Object();
        manager.updateWorldToken(world);
        add(manager, packet(7, 1, 2, 3, 20), 100);
        manager.updateWorldToken(world);
        assertEquals(1, active(manager, 100).size());
        manager.updateWorldToken(new Object());
        assertTrue(active(manager, 100).isEmpty());
    }

    @Test
    void packetVisibilityUsesActualFloatDamageWithoutChangingPacketValidity() {
        for (double damage : List.of(0.01, 0.1, (double) 0.1F)) {
            var packet = packet(7, 1, 2, 3, damage);
            assertTrue(packet.isValid());
            assertFalse(packet.shouldDisplay());
        }
        assertTrue(packet(7, 1, 2, 3, Math.nextUp(0.1F)).shouldDisplay());
        assertFalse(packet(7, 1, 2, 3, Double.NaN).shouldDisplay());
    }

    private static void add(DamagePopupManager manager, DamagePopupPacket packet, double now) {
        manager.add(packet, Component.literal("damage"), 20, now,
                new AABB(packet.x() - 0.5, packet.y() - 1, packet.z() - 0.5,
                        packet.x() + 0.5, packet.y() + 1, packet.z() + 0.5), 0.6);
    }

    private static List<DamagePopupManager.Popup> active(DamagePopupManager manager, double now) {
        var result = new ArrayList<DamagePopupManager.Popup>();
        manager.forEachActive(now, (popup, x, y, alpha) -> result.add(popup));
        return result;
    }

    private static DamagePopupPacket packet(int entity, double x, double y, double z, double damage) {
        return new DamagePopupPacket(entity, x, y, z, 0.45, damage, 0xFFFFFF, List.of());
    }
}
