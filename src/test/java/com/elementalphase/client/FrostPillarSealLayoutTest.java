package com.elementalphase.client;

import com.elementalphase.network.DamagePopupPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrostPillarSealLayoutTest {
    @Test
    void usesThreeEvenlySpacedPillarsAndEightyPercentCrossing() {
        assertEquals(3, FrostShellRenderer.PILLAR_COUNT);
        assertEquals(0.80F, FrostShellRenderer.CROSS_HEIGHT, 0.0001F);
        float first = FrostShellRenderer.pillar(42, 0).angle();
        float second = FrostShellRenderer.pillar(42, 1).angle();
        float third = FrostShellRenderer.pillar(42, 2).angle();
        assertEquals(1.0F / 3.0F, circularDistance(first, second), 0.0001F);
        assertEquals(1.0F / 3.0F, circularDistance(second, third), 0.0001F);
        assertEquals(1.0F / 3.0F, circularDistance(third, first), 0.0001F);
    }

    @Test
    void derivesStableBoundedAndStaggeredPillars() {
        var first = FrostShellRenderer.pillar(91, 0);
        assertEquals(first, FrostShellRenderer.pillar(91, 0));
        assertNotEquals(first, FrostShellRenderer.pillar(91, 1));
        for (int index = 0; index < FrostShellRenderer.PILLAR_COUNT; index++) {
            var pillar = FrostShellRenderer.pillar(91, index);
            if (index == 0) assertRange(pillar.tipHeight(), 0.92F, 0.93F);
            if (index == 1) assertRange(pillar.tipHeight(), 0.95F, 0.97F);
            if (index == 2) assertRange(pillar.tipHeight(), 0.98F, 1.00F);
            assertUnit(pillar.baseScale());
            assertUnit(pillar.twist());
            assertUnit(pillar.shade());
        }
    }

    private static float circularDistance(float first, float second) {
        float distance = Math.abs(first - second);
        return Math.min(distance, 1.0F - distance);
    }

    private static void assertUnit(float value) {
        assertRange(value, 0.0F, 1.0F);
    }

    private static void assertRange(float value, float minimum, float maximum) {
        assertTrue(value >= minimum && value <= maximum,
                () -> value + " outside " + minimum + ".." + maximum);
    }
}

class DamagePopupManagerTest {
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
    void oneEntityCanHaveMoreThanThreePopups() {
        var manager = new DamagePopupManager(() -> 96);
        for (int i = 0; i < 7; i++) {
            add(manager, packet(7, i, 2, 3, 20), 100);
        }
        assertEquals(7, active(manager, 100).size());
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
