package com.elementalphase.reaction.runtime;

import com.elementalphase.data.model.ReactionAction;
import com.elementalphase.reaction.formula.DamageFormulaParser;
import com.elementalphase.state.ElementSourceSnapshot;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledDamageStateTest {
    @Test
    void schedulesOnlyIntervalTicksThroughInclusiveEnd() {
        var state = state(5.0D, 100L, 100, 20, source("old"));

        for (long tick : new long[]{100, 120, 140, 160, 180, 200}) {
            assertTrue(state.due(tick, Long.MIN_VALUE), "tick " + tick);
        }
        for (long tick : new long[]{101, 199, 201}) {
            assertFalse(state.due(tick, Long.MIN_VALUE), "tick " + tick);
        }
        assertFalse(state.due(120L, 120L));
        assertFalse(state.expiredAfter(200L));
        assertTrue(state.expiredAfter(201L));
    }

    @Test
    void doesNotCreateSyntheticHitAtNonDivisibleEnd() {
        var state = state(5.0D, 100L, 95, 20, source("old"));

        assertTrue(state.due(180L, Long.MIN_VALUE));
        assertFalse(state.due(195L, Long.MIN_VALUE));
        assertTrue(state.expiredAfter(196L));
    }

    @Test
    void replacesHigherRefreshesEqualAndIgnoresLower() {
        var original = state(5.0D, 100L, 100, 20, source("old"));

        var higher = ScheduledDamageState.apply(original, id("reaction"), 6.0D, 120L,
                80, 10, damage(), source("higher"));
        assertEquals(ScheduledDamageState.ApplyResult.REPLACED_HIGHER, higher.result());
        assertEquals(6.0D, higher.state().scale());
        assertEquals(120L, higher.state().startedAt());
        assertEquals(id("higher"), higher.state().source().sourceId());

        var equal = ScheduledDamageState.apply(higher.state(), id("reaction"), 6.0D, 130L,
                60, 15, damage(), source("equal"));
        assertEquals(ScheduledDamageState.ApplyResult.REFRESHED_EQUAL, equal.result());
        assertEquals(130L, equal.state().startedAt());
        assertEquals(id("equal"), equal.state().source().sourceId());

        var lower = ScheduledDamageState.apply(equal.state(), id("reaction"), 4.0D, 140L,
                40, 5, damage(), source("lower"));
        assertEquals(ScheduledDamageState.ApplyResult.IGNORED_LOWER, lower.result());
        assertSame(equal.state(), lower.state());
    }

    private static ScheduledDamageState state(double scale, long now, int duration, int interval,
                                               ElementSourceSnapshot source) {
        return ScheduledDamageState.create(id("reaction"), scale, now, duration, interval, damage(), source);
    }

    private static ReactionAction.StateDamage damage() {
        return new ReactionAction.StateDamage(
                new ReactionAction.Formula("scale", DamageFormulaParser.parseReaction("scale")),
                id("reaction"), Optional.empty(), Optional.empty());
    }

    private static ElementSourceSnapshot source(String source) {
        return new ElementSourceSnapshot(Optional.empty(), Optional.empty(), id(source), id("reaction"),
                id("lightning"), 1.0D, 0L);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }
}
