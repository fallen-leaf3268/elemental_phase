package com.elementalphase.reaction.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrozenReactionStateTest {
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
