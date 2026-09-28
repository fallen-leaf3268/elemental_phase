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
    void damageUsesSavedContextAndBothResistancesWhileDisplayUsesEffectIdentity() {
        var snapshot = new ReactionAction.StateSnapshot(
                com.elementalphase.reaction.formula.ReactionFormulaContext.legacy(100, 4),
                Optional.of(id("lightning")), 0.5, 0.5, 0xB388FF, false);
        var damage = new ReactionAction.StateDamage(new ReactionAction.Formula("original_damage + scale",
                DamageFormulaParser.parseReaction("original_damage + scale")), id("reaction_dot"),
                Optional.of(ReactionAction.ElementReference.aura()));
        var state = ScheduledDamageState.create(id("custom_mark"), id("origin"), 4, 100, 100, 20,
                damage, source("missing_actor"), snapshot);
        assertEquals(26, ScheduledReactionDamageExecutor.damageAmount(state));
        var label = ScheduledReactionDamageExecutor.label(state, 26);
        assertEquals(id("custom_mark"), label.id());
        assertEquals(id("origin"), label.originReactionId());
        assertEquals(0xB388FF, label.displayColor());
        assertFalse(label.showName());
    }

    @Test
    void totalAttemptsIncludeOneSeparateFirstPulse() {
        for (int duration : new int[]{100, 95, 0, 10}) {
            var state = state(2, 100, duration, 20, source("actor"));
            int attempts = 1;
            for (long now = 100; now <= 100 + duration; now++) if (state.due(now, Long.MIN_VALUE)) attempts++;
            assertEquals(1 + duration / 20, attempts);
        }
    }

    @Test
    void schedulesOnlyIntervalTicksThroughInclusiveEnd() {
        var state = state(5.0D, 100L, 100, 20, source("old"));

        for (long tick : new long[]{120, 140, 160, 180, 200}) {
            assertTrue(state.due(tick, Long.MIN_VALUE), "tick " + tick);
        }
        for (long tick : new long[]{100, 101, 199, 201}) {
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

        var higher = ScheduledDamageState.apply(original, state(6, 120, 80, 10, source("higher")));
        assertEquals(ScheduledDamageState.ApplyResult.REPLACED_HIGHER, higher.result());
        assertEquals(6.0D, higher.state().scale());
        assertEquals(120L, higher.state().startedAt());
        assertEquals(id("higher"), higher.state().source().sourceId());

        var equal = ScheduledDamageState.apply(higher.state(), state(6, 130, 60, 15, source("equal")));
        assertEquals(ScheduledDamageState.ApplyResult.REFRESHED_EQUAL, equal.result());
        assertEquals(130L, equal.state().startedAt());
        assertEquals(id("equal"), equal.state().source().sourceId());

        var lower = ScheduledDamageState.apply(equal.state(), state(4, 140, 40, 5, source("lower")));
        assertEquals(ScheduledDamageState.ApplyResult.IGNORED_LOWER, lower.result());
        assertSame(equal.state(), lower.state());
    }

    private static ScheduledDamageState state(double scale, long now, int duration, int interval,
                                               ElementSourceSnapshot source) {
        var snapshot = new ReactionAction.StateSnapshot(
                com.elementalphase.reaction.formula.ReactionFormulaContext.stateDamage(scale, 0), Optional.empty(),
                0, 0, 0xFFFFFF, true);
        return ScheduledDamageState.create(id("mark"), id("reaction"), scale, now, duration, interval, damage(), source, snapshot);
    }

    private static ReactionAction.StateDamage damage() {
        return new ReactionAction.StateDamage(
                new ReactionAction.Formula("scale", DamageFormulaParser.parseReaction("scale")),
                id("reaction_dot"), Optional.empty());
    }

    private static ElementSourceSnapshot source(String source) {
        return new ElementSourceSnapshot(Optional.empty(), Optional.empty(), id(source), id("reaction"),
                id("lightning"), 1.0D, 0L);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }
}
