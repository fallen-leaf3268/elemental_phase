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
