package com.elementalphase.state;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReactionElementApplicationTest {
    private static final ResourceLocation ICE = ResourceLocation.fromNamespaceAndPath("test", "ice");
    private static final ElementSourceSnapshot SOURCE = new ElementSourceSnapshot(Optional.empty(), Optional.empty(),
            ResourceLocation.fromNamespaceAndPath("test", "reaction"),
            ResourceLocation.fromNamespaceAndPath("test", "reaction_damage"), ICE, 1.0D, 0L);

    @Test
    void appliesOrdinaryReactionElementWithNormalOverwriteRules() {
        ElementalState state = new ElementalState();
        assertEquals(ElementRuntimeState.ApplyResult.APPLIED,
                state.applyReactionElement(ICE, 5.0D, 0L, 100, SOURCE));
        long firstExpiry = state.state(ICE).temporaryExpiresAt();
        assertEquals(ElementRuntimeState.ApplyResult.IGNORED_LOWER,
                state.applyReactionElement(ICE, 4.0D, 20L, 100, SOURCE));
        assertEquals(firstExpiry, state.state(ICE).temporaryExpiresAt());
        assertEquals(ElementRuntimeState.ApplyResult.REFRESHED,
                state.applyReactionElement(ICE, 5.0D, 20L, 100, SOURCE));
        assertEquals(120L, state.state(ICE).temporaryExpiresAt());
        assertEquals(ElementRuntimeState.ApplyResult.APPLIED,
                state.applyReactionElement(ICE, 8.0D, 30L, 100, SOURCE));
        assertEquals(SOURCE, state.state(ICE).temporarySource().orElseThrow());
    }
}
