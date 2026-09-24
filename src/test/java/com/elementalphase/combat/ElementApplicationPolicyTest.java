package com.elementalphase.combat;

import com.elementalphase.data.ReactionIndex;
import com.elementalphase.data.model.ElementApplicationPolicy;
import com.elementalphase.data.model.ElementAttachmentPolicy;
import com.elementalphase.data.model.ElementDefinition;
import com.elementalphase.data.model.ElementDisplayDefinition;
import com.elementalphase.data.model.ReactionConsumption;
import com.elementalphase.data.model.ReactionDirection;
import com.elementalphase.data.model.ReactionDisplay;
import com.elementalphase.data.model.ReactionSpec;
import com.elementalphase.state.ElementRuntimeState;
import com.elementalphase.state.ElementalState;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

class ElementApplicationPolicyTest {
    private static final ResourceLocation FIRE = ResourceLocation.fromNamespaceAndPath("test", "fire");
    private static final ResourceLocation WATER = ResourceLocation.fromNamespaceAndPath("test", "water");

    @Test
    void sharesCooldownAcrossSourcesForTheSameTargetElement() {
        ElementalState state = new ElementalState();

        assertEquals(ElementRuntimeState.ApplyResult.APPLIED,
                state.applyTemporary(FIRE, 5.0D, 0L, 100, 10));
        assertFalse(state.elementApplicationReady(FIRE, 1L));
        assertEquals(ElementRuntimeState.ApplyResult.BLOCKED_COOLDOWN,
                state.applyTemporary(FIRE, 8.0D, 1L, 100, 10));
        assertTrue(state.elementApplicationReady(WATER, 1L));
    }

    @Test
    void ignoredLowerAmountDoesNotStartCooldownButEqualRefreshDoes() {
        ElementalState state = new ElementalState();
        state.applyTemporaryIgnoringCooldown(FIRE, 5.0D, 0L, 100, null);

        assertEquals(ElementRuntimeState.ApplyResult.IGNORED_LOWER,
                state.applyTemporary(FIRE, 4.0D, 1L, 100, 10));
        assertTrue(state.elementApplicationReady(FIRE, 1L));
        assertEquals(ElementRuntimeState.ApplyResult.REFRESHED,
                state.applyTemporary(FIRE, 5.0D, 1L, 100, 10));
        assertFalse(state.elementApplicationReady(FIRE, 2L));
    }

    @Test
    void instantAttackElementReactsButDoesNotKeepItsRemainder() {
        ElementalState state = new ElementalState();
        state.putPermanent(FIRE, 2.0D, 100);
        ElementDefinition water = definition(WATER, false, 10.0D);

        resolve(state, water, 5.0D, reactionIndex());

        assertEquals(null, state.state(WATER));
        assertEquals(0.0D, state.state(FIRE).effectiveAmount(0L));
    }

    @Test
    void retainedAttackElementStoresOnlyTheCappedRemainder() {
        ElementalState state = new ElementalState();
        ElementDefinition water = definition(WATER, true, 3.0D);

        resolve(state, water, 8.0D, ReactionIndex.build(Map.of()));

        assertEquals(3.0D, state.state(WATER).effectiveAmount(0L));
        assertFalse(state.elementApplicationReady(WATER, 1L));
    }

    @Test
    void lowerAttackAmountDoesNotStartElementCooldown() {
        ElementalState state = new ElementalState();
        state.applyTemporaryIgnoringCooldown(WATER, 5.0D, 0L, 100, null);

        resolve(state, definition(WATER, true, 10.0D), 4.0D, ReactionIndex.build(Map.of()));

        assertEquals(5.0D, state.state(WATER).effectiveAmount(0L));
        assertTrue(state.elementApplicationReady(WATER, 1L));
    }

    @Test
    void rejectsNonFiniteAttackAmountBeforeElementSpecificClamping() {
        assertThrows(IllegalArgumentException.class, () -> new ElementAttackContext(WATER,
                Double.POSITIVE_INFINITY, ElementAttackContext.SourceKind.INTRINSIC,
                ResourceLocation.fromNamespaceAndPath("test", "source")));
    }

    private static void resolve(ElementalState state, ElementDefinition definition, double amount,
                                ReactionIndex index) {
        var attack = new ElementAttackContext(WATER, amount, ElementAttackContext.SourceKind.INTRINSIC,
                ResourceLocation.fromNamespaceAndPath("test", "source"));
        new CombatPipeline().resolve(new CombatPipeline.CombatInput(1.0D, 0.0D, attack, state, 0L,
                definition, index, Map.of(FIRE, definition(FIRE, true, 10.0D), WATER, definition),
                null, null, 1.0D, 10.0D, 10.0D, ignored -> true, null));
    }

    private static ReactionIndex reactionIndex() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("test", "reaction");
        ReactionDirection direction = new ReactionDirection(WATER, FIRE, 0, 0.1D,
                new ReactionConsumption(1.0D, 1.0D), List.of(), new ReactionDisplay(0xFFFFFF, true), List.of());
        ReactionSpec reaction = new ReactionSpec(id, 0, Set.of(FIRE, WATER), List.of(direction));
        return ReactionIndex.build(Map.of(id, reaction));
    }

    private static ElementDefinition definition(ResourceLocation id, boolean retain, double maximum) {
        return new ElementDefinition(id, true, ElementApplicationPolicy.DEFAULT,
                new ElementAttachmentPolicy(retain, 2, 100, maximum),
                new ElementDisplayDefinition("element.test." + id.getPath(), 0xFFFFFF, true, 0, Optional.empty()));
    }
}
