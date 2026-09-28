package com.elementalphase.state;

import com.elementalphase.data.model.ElementAttachmentPolicy;
import com.elementalphase.data.model.ElementDefinition;
import com.elementalphase.data.model.ElementDisplayDefinition;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ElementAmountLimitTest {
    private static final ResourceLocation FIRE = ResourceLocation.fromNamespaceAndPath("test", "fire");

    @Test
    void clampsPermanentAndTemporaryLayersWithoutChangingExpiry() {
        ElementalState state = new ElementalState();
        state.putPermanent(FIRE, 10.0D, 200);
        state.applyTemporaryIgnoringCooldown(FIRE, 20.0D, 100L, 100, null);
        long expiry = state.state(FIRE).temporaryExpiresAt();

        state.reconcileElementLimits(Map.of(FIRE, definition(6.0D)), 100L);

        assertEquals(6.0D, state.state(FIRE).permanentPreset());
        assertEquals(6.0D, state.state(FIRE).permanentCurrent());
        assertEquals(6.0D, state.state(FIRE).temporaryAmount());
        assertEquals(6.0D, state.state(FIRE).effectiveAmount(100L));
        assertEquals(expiry, state.state(FIRE).temporaryExpiresAt());
    }

    @Test
    void leavesStateForUnknownElementsUntouched() {
        ElementalState state = new ElementalState();
        state.putPermanent(FIRE, 10.0D, 200);

        state.reconcileElementLimits(Map.of(), 100L);

        assertEquals(10.0D, state.state(FIRE).effectiveAmount(100L));
    }

    private static ElementDefinition definition(double maximum) {
        return new ElementDefinition(FIRE,
                new ElementAttachmentPolicy(ElementAttachmentPolicy.Mode.NORMAL, 2, 100, maximum),
                new ElementDisplayDefinition("element.test.fire", 0xFFFFFF, true, 0, Optional.empty()));
    }

    @Test
    void virtualDurationIsBoundedAndZeroCooldownAllowsImmediateEqualRefresh() {
        ElementalState state = new ElementalState();
        var virtual = virtualDefinition(0);
        assertEquals(ElementRuntimeState.ApplyResult.APPLIED,
                state.applyElement(virtual, 8.0D, 0L, 100, true, null));
        assertEquals(10L, state.state(FIRE).temporaryExpiresAt());
        assertTrue(state.elementApplicationReady(FIRE, 0L));
        assertEquals(ElementRuntimeState.ApplyResult.IGNORED_LOWER,
                state.applyElement(virtual, 4.0D, 1L, 100, true, null));
        assertEquals(10L, state.state(FIRE).temporaryExpiresAt());
        assertEquals(ElementRuntimeState.ApplyResult.REFRESHED,
                state.applyElement(virtual, 8.0D, 1L, 100, true, null));
        assertEquals(11L, state.state(FIRE).temporaryExpiresAt());
    }

    @Test
    void virtualAmountsAreNotSavedAndClearingDoesNotResetCooldown() {
        ResourceLocation water = ResourceLocation.fromNamespaceAndPath("test", "water");
        ElementalState state = new ElementalState();
        state.applyElement(virtualDefinition(20), 8.0D, 0L, 100, true, null);
        state.putPermanent(water, 5.0D, 100);
        ElementalState restored = new ElementalState();
        restored.deserializeNBT(state.serializeNBT());

        assertEquals(null, restored.state(FIRE));
        assertEquals(5.0D, restored.state(water).effectiveAmount(0L));
        state.clearVirtualElements();
        assertEquals(null, state.state(FIRE));
        assertFalse(state.elementApplicationReady(FIRE, 1L));
        assertEquals(5.0D, state.state(water).effectiveAmount(0L));
    }

    @Test
    void reloadingNormalElementAsVirtualRemovesSavedAmounts() {
        ElementalState state = new ElementalState();
        state.putPermanent(FIRE, 5.0D, 100);
        state.reconcileElementLimits(Map.of(FIRE, virtualDefinition(0)), 0L);

        assertEquals(null, state.state(FIRE));
    }

    @Test
    void propagatedNormalElementKeepsItsOwnDurationBesideVirtualElement() {
        ResourceLocation water = ResourceLocation.fromNamespaceAndPath("test", "water");
        ElementalState state = new ElementalState();
        state.applyElement(virtualDefinition(0), 2.0D, 0L, 100, true, null);
        var normal = new ElementDefinition(water, ElementAttachmentPolicy.DEFAULT,
                new ElementDisplayDefinition("element.test.water", 0xFFFFFF, true, 0, Optional.empty()));
        state.applyElement(normal, 2.0D, 0L, normal.attachment().durationTicks(), true, null);

        assertFalse(state.state(water).virtual());
        assertEquals(100L, state.state(water).temporaryExpiresAt());
        assertEquals(2.0D, state.state(water).effectiveAmount(10L));
        assertEquals(0.0D, state.state(FIRE).effectiveAmount(10L));
    }

    private static ElementDefinition virtualDefinition(int cooldown) {
        return new ElementDefinition(FIRE,
                new ElementAttachmentPolicy(ElementAttachmentPolicy.Mode.VIRTUAL, cooldown, 10, 10.0D),
                new ElementDisplayDefinition("element.test.fire", 0xFFFFFF, true, 0, Optional.empty()));
    }
}
