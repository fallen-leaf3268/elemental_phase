package com.elementalphase.combat;

import com.elementalphase.data.ReactionIndex;
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

    @Test
    void virtualAttackWithoutReactionPersistsOnlyUntilItsShortExpiry() {
        ElementalState state = new ElementalState();
        resolve(state, definition(WATER, false, 10.0D), 5.0D, ReactionIndex.build(Map.of()));

        assertTrue(state.state(WATER).virtual());
        assertEquals(5.0D, state.state(WATER).effectiveAmount(9L));
        assertEquals(0.0D, state.state(WATER).effectiveAmount(10L));
    }

    @Test
    void virtualAuraClearsAllItsRemainderAfterReaction() {
        ElementalState state = new ElementalState();
        ElementDefinition fire = definition(FIRE, false, 10.0D);
        ElementDefinition water = definition(WATER, true, 10.0D);
        state.applyElement(fire, 8.0D, 0L, 100, true, null);
        var result = resolveAt(state, water, 1.0D, reactionIndex(), Map.of(FIRE, fire, WATER, water), 0L);

        assertEquals(1, result.reactionPlan().labels().size());
        assertEquals(0.0D, state.state(FIRE).effectiveAmount(0L));
        assertEquals(0.0D, result.reactionPlan().labels().get(0).facts().remainingAura());
        assertFalse(state.elementApplicationReady(FIRE, 1L));
    }

    @Test
    void virtualTriggerStopsAfterFirstSuccessfulReaction() {
        ResourceLocation ice = ResourceLocation.fromNamespaceAndPath("test", "ice");
        ResourceLocation second = ResourceLocation.fromNamespaceAndPath("test", "second");
        ElementalState state = new ElementalState();
        state.putPermanent(FIRE, 1.0D, 100);
        state.putPermanent(ice, 1.0D, 100);
        var direction = new ReactionDirection(WATER, ice, 0.1D,
                new ReactionConsumption(1.0D, 1.0D), List.of(), new ReactionDisplay(0xFFFFFF, true), List.of());
        var secondReaction = new ReactionSpec(second, 0, Set.of(WATER, ice), List.of(direction));
        var firstReaction = reactionIndex().candidates(WATER, FIRE).get(0).reaction();
        var reactions = new java.util.HashMap<>(Map.of(firstReaction.id(), firstReaction));
        reactions.put(second, secondReaction);
        ElementDefinition water = definition(WATER, false, 10.0D);
        var result = resolveAt(state, water, 8.0D, ReactionIndex.build(reactions),
                Map.of(FIRE, definition(FIRE, true, 10), WATER, water, ice, definition(ice, true, 10)), 0L);

        assertEquals(1, result.reactionPlan().labels().size());
        assertEquals(1.0D, state.state(ice).effectiveAmount(0L));
        assertEquals(0.0D, result.reactionPlan().labels().get(0).facts().remainingTrigger());
    }

    @Test
    void lowerVirtualAttackDoesNotReactOrRefreshButEqualAttackCanReact() {
        ElementalState state = new ElementalState();
        ElementDefinition water = definition(WATER, false, 10.0D);
        state.applyElement(water, 5.0D, 0L, 10, false, null);
        state.putPermanent(FIRE, 2.0D, 100);
        var definitions = Map.of(FIRE, definition(FIRE, true, 10), WATER, water);

        var lower = resolveAt(state, water, 4.0D, reactionIndex(), definitions, 1L);
        assertEquals(ElementRuntimeState.ApplyResult.IGNORED_LOWER, lower.applyResult());
        assertTrue(lower.reactionPlan().labels().isEmpty());
        assertEquals(10L, state.state(WATER).temporaryExpiresAt());
        assertTrue(state.elementApplicationReady(WATER, 1L));

        var equal = resolveAt(state, water, 5.0D, reactionIndex(), definitions, 1L);
        assertEquals(1, equal.reactionPlan().labels().size());
        assertEquals(0.0D, state.state(WATER).effectiveAmount(1L));
        assertFalse(state.elementApplicationReady(WATER, 2L));
    }

    @Test
    void tooSmallReactionDoesNotClearVirtualAttachment() {
        ElementalState state = new ElementalState();
        state.putPermanent(FIRE, 0.05D, 100);
        ElementDefinition water = definition(WATER, false, 10.0D);
        var result = resolveAt(state, water, 5.0D, reactionIndex(),
                Map.of(FIRE, definition(FIRE, true, 10), WATER, water), 0L);

        assertTrue(result.reactionPlan().labels().isEmpty());
        assertEquals(5.0D, state.state(WATER).effectiveAmount(0L));
        assertEquals(0.05D, state.state(FIRE).effectiveAmount(0L));
    }

    private static void resolve(ElementalState state, ElementDefinition definition, double amount,
                                ReactionIndex index) {
        resolveAt(state, definition, amount, index,
                Map.of(FIRE, definition(FIRE, true, 10.0D), WATER, definition), 0L);
    }

    private static CombatPipeline.CombatResult resolveAt(ElementalState state, ElementDefinition definition,
                                double amount, ReactionIndex index,
                                Map<ResourceLocation, ElementDefinition> definitions, long now) {
        var attack = new ElementAttackContext(WATER, amount, ElementAttackContext.SourceKind.INTRINSIC,
                ResourceLocation.fromNamespaceAndPath("test", "source"));
        return new CombatPipeline().resolve(new CombatPipeline.CombatInput(1.0D, 0.0D, attack, state, now,
                definition, index, definitions,
                null, 1.0D, 10.0D, 10.0D, ignored -> true, null));
    }

    private static ReactionIndex reactionIndex() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("test", "reaction");
        ReactionDirection direction = new ReactionDirection(WATER, FIRE, 0.1D,
                new ReactionConsumption(1.0D, 1.0D), List.of(), new ReactionDisplay(0xFFFFFF, true), List.of());
        ReactionSpec reaction = new ReactionSpec(id, 0, Set.of(FIRE, WATER), List.of(direction));
        return ReactionIndex.build(Map.of(id, reaction));
    }

    private static ElementDefinition definition(ResourceLocation id, boolean retain, double maximum) {
        return new ElementDefinition(id,
                new ElementAttachmentPolicy(retain ? ElementAttachmentPolicy.Mode.NORMAL : ElementAttachmentPolicy.Mode.VIRTUAL,
                        2, retain ? 100 : 10, maximum),
                new ElementDisplayDefinition("element.test." + id.getPath(), 0xFFFFFF, true, 0, Optional.empty()));
    }
}
