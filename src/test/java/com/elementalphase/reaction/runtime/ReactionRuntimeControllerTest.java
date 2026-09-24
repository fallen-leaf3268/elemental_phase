package com.elementalphase.reaction.runtime;

import com.elementalphase.data.model.ReactionAction;
import com.elementalphase.reaction.formula.DamageFormulaParser;
import com.elementalphase.state.ElementSourceSnapshot;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactionRuntimeControllerTest {
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
        var damage = new ReactionAction.StateDamage(
                new ReactionAction.Formula("scale", DamageFormulaParser.parseReaction("scale")),
                id("reaction"), Optional.empty(), Optional.empty());
        var source = new ElementSourceSnapshot(Optional.empty(), Optional.empty(), id("source"), id("reaction"),
                id("lightning"), 1.0D, now);
        return ScheduledDamageState.create(id("reaction"), 1.0D, now, duration, 20, damage, source);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }
}
