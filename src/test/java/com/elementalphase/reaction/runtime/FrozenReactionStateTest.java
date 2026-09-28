package com.elementalphase.reaction.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrozenReactionStateTest {
    @Test
    void refreshComparesRemainingDurationAndRestartsAcceptedState() {
        var state = FrozenReactionState.create(0, 100);
        org.junit.jupiter.api.Assertions.assertSame(state, state.refresh(80, 10));
        assertEquals(80, state.refresh(80, 20).startedAt());
        assertEquals(100, state.refresh(80, 20).expiresAt());
        assertEquals(110, state.refresh(80, 30).expiresAt());
        assertEquals(2147483727L, state.refresh(80, Integer.MAX_VALUE).expiresAt());
    }

    @Test
    void usesInclusiveExpirationAndRefreshesFromCurrentTick() {
        var state = FrozenReactionState.create(100L, 20);

        assertTrue(state.active(100L));
        assertTrue(state.active(120L));
        assertFalse(state.active(121L));
        assertEquals(20, state.remainingTicks(100L));
        assertEquals(0, state.remainingTicks(120L));
        assertEquals(0, state.remainingTicks(121L));

        var refreshed = state.refresh(110L, 30);
        assertEquals(110L, refreshed.startedAt());
        assertEquals(140L, refreshed.expiresAt());
    }

    @Test
    void saturatesDeadlineOnOverflow() {
        var state = FrozenReactionState.create(Long.MAX_VALUE - 5L, 20);

        assertEquals(Long.MAX_VALUE, state.expiresAt());
        assertTrue(state.active(Long.MAX_VALUE));
    }
}
