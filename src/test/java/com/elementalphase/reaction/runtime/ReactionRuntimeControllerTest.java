package com.elementalphase.reaction.runtime;

import com.elementalphase.data.model.ReactionAction;
import com.elementalphase.reaction.formula.DamageFormulaParser;
import com.elementalphase.state.ElementSourceSnapshot;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ReactionRuntimeControllerTest {
    @Test
    void lowerScaleStillHitsAndSameTickEqualRefreshHitsAgain() {
        var runtime = new ReactionRuntimeController.EntityRuntime();
        var old = task(4, 100, 100, 20, "old");
        runtime.task(old.effectId(), old);
        runtime.markRun(old.effectId(), 100);
        var hits = new java.util.ArrayList<ScheduledDamageState>();
        var low = task(2, 100, 40, 10, "low");
        assertEquals(ScheduledDamageState.ApplyResult.IGNORED_LOWER,
                ReactionRuntimeController.applyScheduled(runtime, low, hits::add, () -> true));
        assertSame(old, runtime.task(old.effectId()));
        assertTrue(runtime.ranAt(old.effectId(), 100));
        var equal = task(4, 100, 80, 10, "equal");
        ReactionRuntimeController.applyScheduled(runtime, equal, hits::add, () -> true);
        ReactionRuntimeController.applyScheduled(runtime, equal, hits::add, () -> true);
        assertEquals(java.util.List.of(low, equal, equal), hits);
        assertSame(equal, runtime.task(equal.effectId()));
        assertFalse(runtime.ranAt(equal.effectId(), 100));
        assertEquals(id("equal"), runtime.task(equal.effectId()).source().sourceId());
        assertTrue(runtime.task(equal.effectId()).due(110, Long.MIN_VALUE));
    }

    @Test
    void acceptedZeroDurationEndsTaskAndLowInstantKeepsOldTask() {
        var runtime = new ReactionRuntimeController.EntityRuntime();
        var old = task(4, 100, 100, 20, "old");
        runtime.task(old.effectId(), old);
        var hits = new java.util.ArrayList<ScheduledDamageState>();
        ReactionRuntimeController.applyScheduled(runtime, task(2, 110, 0, 20, "low"), hits::add, () -> true);
        assertSame(old, runtime.task(old.effectId()));
        ReactionRuntimeController.applyScheduled(runtime, task(5, 110, 0, 20, "higher"), hits::add, () -> true);
        assertNull(runtime.task(old.effectId()));
        assertEquals(2, hits.size());
    }

    @Test
    void firstPulseDoesNotDiscardOtherTaskEndpointAndInvalidTargetClearsAll() {
        var runtime = new ReactionRuntimeController.EntityRuntime();
        var endpoint = task(4, 100, 20, 20, "endpoint");
        runtime.task(id("other"), endpoint);
        var higher = task(5, 120, 100, 20, "higher");
        ReactionRuntimeController.applyScheduled(runtime, higher, ignored -> {}, () -> true);
        assertSame(endpoint, runtime.task(id("other")));
        assertTrue(endpoint.due(120, Long.MIN_VALUE));
        assertSame(higher, runtime.task(higher.effectId()));
        ReactionRuntimeController.applyScheduled(runtime, task(6, 120, 100, 20, "death"), ignored -> {}, () -> false);
        assertTrue(runtime.tasksInOrder().isEmpty());
    }

    @Test
    void retainsOnlyLiveStateAndCurrentTickTombstones() {
        var runtime = new ReactionRuntimeController.EntityRuntime();
        assertFalse(runtime.retainAt(100L));

        runtime.freeze(FrozenReactionState.create(100L, 20));
        assertTrue(runtime.retainAt(100L));
        runtime.clearFreeze();

        ResourceLocation taskId = id("pulse");
        runtime.task(taskId, task(100L, 0));
        runtime.markRun(taskId, 100L);
        runtime.removeExpiredTasks(100L);
        assertTrue(runtime.retainAt(100L));
        assertTrue(runtime.ranAt(taskId, 100L));
        assertFalse(runtime.retainAt(101L));
    }

    @Test
    void keepsDistinctTasksAndClearRemovesEverything() {
        var runtime = new ReactionRuntimeController.EntityRuntime();
        runtime.freeze(FrozenReactionState.create(100L, 20));
        runtime.task(id("one"), task(100L, 20));
        runtime.task(id("two"), task(100L, 20));

        assertTrue(runtime.retainAt(100L));
        runtime.clear();
        assertFalse(runtime.retainAt(100L));
    }

    private static ScheduledDamageState task(long now, int duration) {
        return task(1, now, duration, 20, "source");
    }

    private static ScheduledDamageState task(double scale, long now, int duration, int interval, String sourceId) {
        var damage = new ReactionAction.StateDamage(
                new ReactionAction.Formula("scale", DamageFormulaParser.parseReaction("scale")),
                id("reaction_dot"), Optional.empty());
        var source = new ElementSourceSnapshot(Optional.empty(), Optional.empty(), id(sourceId), id("reaction_dot"),
                id("lightning"), 1.0D, now);
        var snapshot = new ReactionAction.StateSnapshot(
                com.elementalphase.reaction.formula.ReactionFormulaContext.stateDamage(scale, 0), Optional.empty(),
                0, 0, sourceId.hashCode() & 0xFFFFFF, !sourceId.equals("low"));
        return ScheduledDamageState.create(id("pulse"), id("reaction"), scale, now, duration, interval, damage, source, snapshot);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }
}
