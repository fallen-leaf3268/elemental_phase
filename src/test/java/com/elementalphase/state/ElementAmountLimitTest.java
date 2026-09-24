package com.elementalphase.state;

import com.elementalphase.data.model.ElementApplicationPolicy;
import com.elementalphase.data.model.ElementAttachmentPolicy;
import com.elementalphase.data.model.ElementDefinition;
import com.elementalphase.data.model.ElementDisplayDefinition;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        return new ElementDefinition(FIRE, true, ElementApplicationPolicy.DEFAULT,
                new ElementAttachmentPolicy(true, 2, 100, maximum),
                new ElementDisplayDefinition("element.test.fire", 0xFFFFFF, true, 0, Optional.empty()));
    }
}
