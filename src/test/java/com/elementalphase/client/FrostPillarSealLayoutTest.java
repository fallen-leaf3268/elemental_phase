package com.elementalphase.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
