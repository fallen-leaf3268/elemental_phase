package com.elementalphase.combat;

import com.elementalphase.state.ElementalState;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResistancePolicyTest {
    private static final ResourceLocation FIRE = ResourceLocation.parse("test:fire");
    private static final ResourceLocation REACTION = ResourceLocation.parse("test:reaction");

    @Test
    void compoundsElementAndReactionResistanceAsSeparateMultipliers() {
        assertEquals(25, ResistancePolicy.apply(100, 0.5, 0.5));
        assertEquals(75, ResistancePolicy.apply(100, -0.5, 0.5));
        assertEquals(1100, ResistancePolicy.apply(100, -10));
        assertEquals(0, ResistancePolicy.apply(100, 0, 1));
        assertEquals(0, ResistancePolicy.apply(100, 1, -10));
        assertEquals(0, ResistancePolicy.apply(100, 2, 2));
        assertEquals(0, ResistancePolicy.apply(100, 0, 1));
        assertEquals(1_000_000, ResistancePolicy.apply(1_000_000, -10, -10));
        assertEquals(1_000_000, ResistancePolicy.apply(100, -2147483647, -2147483647));
        assertEquals(0, ResistancePolicy.apply(Double.NaN, 0));
    }

    @Test
    void stateSupportsBothResistanceMapsAndPreservesThemInNbt() {
        var state = new ElementalState();
        state.setResistance(FIRE, -2147483647);
        state.setReactionResistance(REACTION, 0.75);
        var restored = new ElementalState();
        restored.deserializeNBT(state.serializeNBT());
        assertEquals(-2147483647, restored.resistance(FIRE));
        assertEquals(0.75, restored.reactionResistance(REACTION));
        assertEquals(0, restored.reactionResistance(ResourceLocation.parse("test:unknown")));
        restored.replaceReactionResistances(Map.of());
        assertEquals(0, restored.reactionResistance(REACTION));
        assertEquals(-2147483647, restored.resistance(FIRE));
        state.clear();
        assertEquals(0, state.reactionResistance(REACTION));
    }

    @Test
    void rejectsNonFiniteAndOutOfRangeResistanceValues() {
        var state = new ElementalState();
        for (double invalid : new double[]{-2147483648.0, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> state.setResistance(FIRE, invalid));
            assertThrows(IllegalArgumentException.class, () -> state.setReactionResistance(REACTION, invalid));
        }
        state.setResistance(FIRE, 1);
        state.setReactionResistance(REACTION, -2147483647);
        assertEquals(-2147483647, state.reactionResistance(REACTION));
    }
}
