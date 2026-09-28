package com.elementalphase.effect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReactionDamageTargetTest {
    @Test
    void configuredCapsCanExceedTheirDefaultsAndDamageRunsFirst() {
        assertEquals(48, ReactionActionExecutor.effectiveRadius(48, 96));
        assertEquals(96, ReactionActionExecutor.effectiveRadius(120, 96));
        assertEquals(0, ReactionActionExecutor.effectiveRadius(Double.NaN, 96));
        assertEquals(128, ReactionActionExecutor.effectiveTargetLimit(java.util.Optional.empty(), 128));
        var events = new java.util.ArrayList<String>();
        ReactionActionExecutor.runAreaParts(false, () -> events.add("damage"), () -> true, () -> events.add("attachment"));
        assertEquals(java.util.List.of("damage", "attachment"), events);
        events.clear();
        ReactionActionExecutor.runAreaParts(false, () -> events.add("damage"), () -> false, () -> events.add("attachment"));
        assertEquals(java.util.List.of("damage"), events);
    }

    private record Candidate(int id, double distanceSquared) {}

    @Test
    void parentAndChildSelectionsShareAndExhaustTheSameBudget() {
        var budget = new com.elementalphase.combat.ReactionExecutionBudget(1, 2);
        var far = new Candidate(1, 4);
        var near = new Candidate(2, 1);
        var unchecked = new Candidate(3, 0);
        var checked = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Predicate<Candidate> valid = candidate -> { checked.incrementAndGet(); return true; };
        var parent = ReactionActionExecutor.selectCandidates(null, null, java.util.List.of(far, near, unchecked),
                valid, Candidate::distanceSquared, Candidate::id, 100, 1, false, false, budget);
        assertEquals(java.util.List.of(near), parent);
        assertEquals(2, checked.get());
        assertEquals(0, budget.targetOperationsRemaining());
        assertTrue(ReactionActionExecutor.selectCandidates(null, null, java.util.List.of(unchecked), valid,
                Candidate::distanceSquared, Candidate::id, 100, 1, false, false, budget).isEmpty());
        assertEquals(2, checked.get());
    }

    @Test
    void attackerSharesLimitAndPrimaryDuplicatesAndOutsideAreExcluded() {
        var original = new Candidate(1, 0);
        var attacker = new Candidate(8, 4);
        var nearby = new Candidate(3, 4);
        var outside = new Candidate(9, 100);
        var budget = new com.elementalphase.combat.ReactionExecutionBudget(1, 20);
        assertEquals(java.util.List.of(nearby), ReactionActionExecutor.selectCandidates(original, attacker,
                java.util.List.of(original, attacker, nearby, nearby, outside), ignored -> true,
                Candidate::distanceSquared, Candidate::id, 9, 1, false, true, budget));
        assertEquals(17, budget.targetOperationsRemaining());
    }

    @Test
    void rejectedDamageStillAllowsAttachmentForValidTargets() {
        for (var outcome : ReactionActionExecutor.DamageOutcome.values()) {
            var events = new java.util.ArrayList<String>();
            ReactionActionExecutor.runAreaParts(false, () -> events.add(outcome.name()), () -> true, () -> events.add("attachment"));
            assertEquals(java.util.List.of(outcome.name(), "attachment"), events);
        }
    }

    @Test
    void includedPrimaryTakesDamageOnceWithoutAttachment() {
        var original = new Candidate(8, 0);
        var nearby = new Candidate(3, 4);
        var budget = new com.elementalphase.combat.ReactionExecutionBudget(1, 20);
        var targets = ReactionActionExecutor.selectCandidates(original, null,
                java.util.List.of(original, nearby, original, nearby), ignored -> true,
                Candidate::distanceSquared, Candidate::id, 9, 10, true, false, budget);
        assertEquals(java.util.List.of(original, nearby), targets);
        assertEquals(18, budget.targetOperationsRemaining());

        var events = new java.util.ArrayList<String>();
        for (var target : targets) {
            ReactionActionExecutor.runAreaParts(target == original, () -> events.add(target.id() + ":damage"),
                    () -> true, () -> events.add(target.id() + ":attachment"));
        }
        assertEquals(java.util.List.of("8:damage", "3:damage", "3:attachment"), events);
    }

    @Test
    void includedPrimaryOccupiesFirstTargetSlotEvenWhenEntitiesOverlap() {
        var original = new Candidate(8, 0);
        var overlapping = new Candidate(1, 0);
        var budget = new com.elementalphase.combat.ReactionExecutionBudget(1, 20);
        assertEquals(java.util.List.of(original), ReactionActionExecutor.selectCandidates(original, null,
                java.util.List.of(overlapping), ignored -> true, Candidate::distanceSquared, Candidate::id,
                9, 1, true, false, budget));
    }

    @Test
    void includedPrimarySharesAndExhaustsTheTargetBudget() {
        var original = new Candidate(8, 0);
        var nearby = new Candidate(3, 4);
        var unchecked = new Candidate(2, 1);
        var budget = new com.elementalphase.combat.ReactionExecutionBudget(1, 2);
        var checked = new java.util.ArrayList<Candidate>();
        assertEquals(java.util.List.of(original, nearby), ReactionActionExecutor.selectCandidates(original, null,
                java.util.List.of(original, nearby, unchecked), candidate -> { checked.add(candidate); return true; },
                Candidate::distanceSquared, Candidate::id, 9, 10, true, false, budget));
        assertEquals(java.util.List.of(original, nearby), checked);
        assertEquals(0, budget.targetOperationsRemaining());
        assertTrue(ReactionActionExecutor.selectCandidates(original, null, java.util.List.of(nearby),
                ignored -> fail("Exhausted budget must not check targets"), Candidate::distanceSquared,
                Candidate::id, 9, 10, true, false, budget).isEmpty());
    }

    @Test
    void invalidPrimaryIsSkippedAndAttackerCannotSuppressIncludedPrimary() {
        var original = new Candidate(8, 0);
        var nearby = new Candidate(3, 4);
        var invalidBudget = new com.elementalphase.combat.ReactionExecutionBudget(1, 20);
        assertEquals(java.util.List.of(nearby), ReactionActionExecutor.selectCandidates(original, null,
                java.util.List.of(original, nearby), candidate -> candidate != original,
                Candidate::distanceSquared, Candidate::id, 9, 10, true, false, invalidBudget));
        assertEquals(18, invalidBudget.targetOperationsRemaining());

        assertEquals(java.util.List.of(original), ReactionActionExecutor.selectCandidates(original, original,
                java.util.List.of(original), ignored -> true, Candidate::distanceSquared, Candidate::id,
                9, 10, true, false, new com.elementalphase.combat.ReactionExecutionBudget(1, 20)));
    }
}
