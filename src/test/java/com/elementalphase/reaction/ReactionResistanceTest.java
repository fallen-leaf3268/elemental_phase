package com.elementalphase.reaction;

import com.elementalphase.combat.CombatPipeline;
import com.elementalphase.combat.ElementAttackContext;
import com.elementalphase.data.ReactionIndex;
import com.elementalphase.data.model.*;
import com.elementalphase.state.ElementalState;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReactionResistanceTest {
    private static final ResourceLocation FIRE = id("fire");
    private static final ResourceLocation WATER = id("water");
    private static final ResourceLocation ICE = id("ice");
    private static final ResourceLocation STEAM = id("steam");
    private static final ResourceLocation FREEZE = id("freeze");

    @Test
    void consumptionAmountsAndMinimumScaleAreSeparateFromRatioUnits() {
        for (double multiplier : List.of(1.0, 2.0, 1.0E-310)) {
            var state = new ElementalState();
            state.putPermanent(FIRE, 4, 200);
            var defs = Map.of(FIRE, element(FIRE), WATER, element(WATER));
            var direction = new ReactionDirection(WATER, FIRE, 0.1, new ReactionConsumption(multiplier, 2 * multiplier),
                    List.of(), new ReactionDisplay(0xFFFFFF, true), List.of());
            var spec = new ReactionSpec(STEAM, 0, Set.of(WATER, FIRE), List.of(direction));
            var plan = new ReactionEngine().react(new ReactionRequest(state, WATER, 3, 0, defs.get(WATER),
                    ReactionIndex.build(Map.of(STEAM, spec)), defs, 0, 0, 1, 100, 100, 0,
                    Optional.empty(), ignored -> true, null, false));
            var facts = plan.labels().get(0).facts();
            assertEquals(2, facts.consumedTrigger());
            assertEquals(4, facts.consumedAura());
            assertEquals(2, facts.scale());
            assertEquals(1, state.state(WATER).effectiveAmount(0));
            assertEquals(0, state.state(FIRE).effectiveAmount(0));
        }
    }

    @Test
    void whenZeroAndNonFiniteSkipActionsButNegativeFiniteExecutes() {
        for (String when : List.of("0", "-1", "1 / (scale - scale)")) {
            var state = aura();
            var action = new ReactionAction.MainDamageBonus(bonus(20).formula(),
                    com.elementalphase.reaction.formula.DamageFormulaParser.parseReaction(when));
            var result = resolve(state, 0, 1, List.of(reaction(STEAM, FIRE, List.of(action))));
            assertEquals(when.equals("-1") ? 120 : 100, result.finalPreArmorDamage());
            assertEquals(0, state.state(FIRE).effectiveAmount(0));
            var appearance = com.elementalphase.display.DamageDisplayCoordinator.appearance(result.reactionPlan(), 0x123456);
            assertEquals(when.equals("-1") ? 0xFFFFFF : 0x123456, appearance.color());
            assertEquals(when.equals("-1") ? 1 : 0, appearance.visibleLabels().size());
        }
    }

    @Test
    void independentDamageKeepsOriginalAttackDisplayAndItsOwnReactionMetadata() {
        var damage = new ReactionAction.AdditionalDamage(bonus(20).formula(),
                new ReactionAction.DamageSettings(ReactionAction.DEFAULT_DAMAGE_TYPE, Optional.empty()), ignored -> 1);
        var result = resolve(aura(), 0, 1, List.of(reaction(STEAM, FIRE, 0, 0xB388FF, List.of(damage))));
        var planned = result.reactionPlan().actions().get(0);
        assertEquals(damage, planned.action());
        assertEquals(STEAM, planned.facts().reactionId());
        assertEquals(0xB388FF, result.reactionPlan().labels().get(0).color());
        assertTrue(planned.facts().direction().display().showReaction());
        assertOrdinaryOriginalAttack(result);
    }

    @Test
    void areaDamageDoesNotLabelOriginalAttackEvenWhenTargetIsIncluded() {
        for (boolean includeTarget : List.of(false, true)) {
            var area = new ReactionAction.Area(bonus(3).formula(), Optional.of(new ReactionAction.AreaDamageValue(
                    bonus(20).formula(), new ReactionAction.DamageSettings(ReactionAction.DEFAULT_DAMAGE_TYPE, Optional.empty()), includeTarget)),
                    Optional.empty(), false, Optional.empty(), ignored -> 1);
            var result = resolve(aura(), 0, 1, List.of(reaction(STEAM, FIRE, List.of(area))));
            assertEquals(area, result.reactionPlan().actions().get(0).action());
            assertEquals(STEAM, result.reactionPlan().actions().get(0).facts().reactionId());
            assertOrdinaryOriginalAttack(result);
        }
    }

    @Test
    void dotUsesReactionAppearanceWithoutRelabelingOriginalAttack() {
        var dot = new ReactionAction.ScheduleDamage(id("mark"), 100, 20,
                new ReactionAction.StateDamage(bonus(20).formula(), ReactionAction.DEFAULT_DOT_DAMAGE_TYPE, Optional.empty()),
                ignored -> 1);
        var result = resolve(aura(), 0, 1, List.of(reaction(STEAM, FIRE, 0, 0xB388FF, List.of(dot))));
        var planned = result.reactionPlan().actions().get(0);
        assertEquals(dot, planned.action());
        var saved = planned.stateSnapshot().orElseThrow();
        assertEquals(0xB388FF, saved.color());
        assertTrue(saved.showName());
        assertEquals(100, saved.context().currentDamage());
        assertOrdinaryOriginalAttack(result);
    }

    @Test
    void pureSpecialKeepsItsActionWithoutRelabelingOriginalAttack() {
        var freeze = new ReactionAction.Special(List.of(new ReactionAction.SpecialEntry(
                ReactionAction.SpecialEntry.Kind.FREEZE, 100)), ignored -> 1);
        var result = resolve(aura(), 0, 1, List.of(reaction(STEAM, FIRE, List.of(freeze))));
        assertEquals(freeze, result.reactionPlan().actions().get(0).action());
        assertOrdinaryOriginalAttack(result);
    }

    @Test
    void mixedSeparateAndAmplifyingReactionsLabelOnlyTheActualMainBonus() {
        var state = aura();
        state.putPermanent(ICE, 1, 200);
        var separate = new ReactionAction.AdditionalDamage(bonus(30).formula(),
                new ReactionAction.DamageSettings(ReactionAction.DEFAULT_DAMAGE_TYPE, Optional.empty()), ignored -> 1);
        var result = resolve(state, 0, 2, List.of(reaction(STEAM, FIRE, 100, 0xABCDEF, List.of(separate)),
                reaction(FREEZE, ICE, 0, 0xFEDCBA, List.of(bonus(20)))));
        assertEquals(120, result.finalPreArmorDamage());
        assertEquals(List.of(STEAM, FREEZE), result.reactionPlan().labels().stream().map(ReactionPlan.Label::reactionId).toList());
        assertEquals(separate, result.reactionPlan().actions().get(0).action());
        assertEquals(STEAM, result.reactionPlan().actions().get(0).facts().reactionId());
        var appearance = com.elementalphase.display.DamageDisplayCoordinator.appearance(result.reactionPlan(), 0x123456);
        assertEquals(0xFEDCBA, appearance.color());
        assertEquals(List.of(FREEZE), appearance.visibleLabels().stream()
                .map(com.elementalphase.display.PendingMainDamageTracker.ReactionLabel::id).toList());
        assertEquals(List.of(FREEZE), result.reactionOutcome().reactions().stream()
                .map(ReactionOutcome.TriggeredReaction::id).toList());
    }

    @Test
    void nonPositiveOrNonFiniteMainBonusDoesNotRelabelOriginalAttack() {
        for (String formula : List.of("0", "-20", "scale / (scale - scale)", "0 / (scale - scale)")) {
            var bonus = new ReactionAction.MainDamageBonus(new ReactionAction.Formula(formula,
                    com.elementalphase.reaction.formula.DamageFormulaParser.parseReaction(formula)), ignored -> 1);
            var result = resolve(aura(), 0, 1, List.of(reaction(STEAM, FIRE, List.of(bonus))));
            assertEquals(1, result.reactionPlan().labels().size());
            assertOrdinaryOriginalAttack(result);
        }
    }

    @Test
    void saturatedMainDamageDoesNotAttributeAnIncreaseToLaterReactions() {
        var state = aura();
        state.putPermanent(ICE, 1, 200);
        var result = resolve(state, 0, 2, List.of(reaction(STEAM, FIRE, 100, 0xABCDEF, List.of(bonus(1_000_000))),
                reaction(FREEZE, ICE, 0, 0xFEDCBA, List.of(bonus(20)))));
        assertEquals(1_000_000, result.finalPreArmorDamage());
        assertEquals(2, result.reactionPlan().labels().size());
        var appearance = com.elementalphase.display.DamageDisplayCoordinator.appearance(result.reactionPlan(), 0x123456);
        assertEquals(0xABCDEF, appearance.color());
        assertEquals(List.of(STEAM), appearance.visibleLabels().stream()
                .map(com.elementalphase.display.PendingMainDamageTracker.ReactionLabel::id).toList());
        assertEquals(List.of(STEAM), result.reactionOutcome().reactions().stream()
                .map(ReactionOutcome.TriggeredReaction::id).toList());
    }

    @Test
    void hiddenReactionNamesKeepTheActualReactionColor() {
        var d = reaction(STEAM, FIRE, List.of(bonus(20))).directions().get(0);
        var hidden = new ReactionDirection(d.trigger(), d.aura(), d.minimumScale(), d.consumption(), d.conditions(),
                new ReactionDisplay(0xB388FF, false), d.actions());
        var spec = new ReactionSpec(STEAM, 0, Set.of(WATER, FIRE), List.of(hidden));
        var plan = resolve(aura(), 0, 1, List.of(spec)).reactionPlan();
        assertEquals(0xB388FF, plan.labels().get(0).color());
        assertFalse(plan.labels().get(0).visible());
        var appearance = com.elementalphase.display.DamageDisplayCoordinator.appearance(plan, 0x123456);
        assertEquals(0xB388FF, appearance.color());
        assertTrue(appearance.visibleLabels().isEmpty());
        var nativeTracker = new com.elementalphase.display.PendingMainDamageTracker<Object>();
        var source = new Object();
        nativeTracker.record(source, 7, 100, appearance);
        assertEquals(appearance, nativeTracker.consume(source, 7, 100).orElseThrow());
        assertTrue(nativeTracker.consume(source, 7, 100).isEmpty());
        var compatTracker = new com.elementalphase.integration.damagenumber.DamageNumberReactionTracker<Object>();
        var player = java.util.UUID.randomUUID();
        assertTrue(compatTracker.record(source, player, 100, appearance));
        assertEquals(appearance, compatTracker.consume(source, player, 100).orElseThrow());
        assertTrue(compatTracker.consume(source, player, 100).isEmpty());
    }

    @Test
    void dotCapturesReactionStateAndFinalMainDamageWithoutLiveResistanceReads() {
        var state = aura();
        state.setResistance(FIRE, 0.5);
        state.setReactionResistance(STEAM, 0.5);
        var damage = new ReactionAction.StateDamage(new ReactionAction.Formula("target_health + current_damage + scale",
                com.elementalphase.reaction.formula.DamageFormulaParser.parseReaction("target_health + current_damage + scale")),
                ReactionAction.DEFAULT_DOT_DAMAGE_TYPE, Optional.of(ReactionAction.ElementReference.aura()));
        var dot = new ReactionAction.ScheduleDamage(id("mark"), 100, 20, damage, ignored -> 1);
        var result = resolve(state, 0, 1, List.of(reaction(STEAM, FIRE, List.of(dot, bonus(20)))));
        var saved = result.reactionPlan().actions().get(0).stateSnapshot().orElseThrow();
        assertEquals(100, saved.context().targetHealth());
        assertEquals(100, saved.context().targetMaxHealth());
        assertEquals(1, saved.context().targetHealthRatio());
        assertEquals(110, saved.context().currentDamage());
        assertEquals(1, saved.context().consumedTrigger());
        assertEquals(1, saved.context().consumedAura());
        assertEquals(Optional.of(FIRE), saved.resistanceElement());
        assertEquals(0.5, saved.context().targetResistance());
        state.setResistance(FIRE, 1);
        state.setReactionResistance(STEAM, 1);
        assertEquals(0.5, saved.elementResistance());
        assertEquals(0.5, saved.reactionResistance());
        assertEquals(211, dot.damage().formula().evaluate(saved.context()));
    }

    @Test
    void scaleUsesActualConsumptionAndIsInvariantUnderEquivalentRatios() {
        assertEquals(2, ReactionEngine.scale(3, 4, 1, 2));
        assertEquals(2, ReactionEngine.scale(3, 4, 2, 4));
        assertEquals(2, ReactionEngine.scale(3, 4, 1.0E-310, 2.0E-310));
    }

    @Test
    void pureAttachmentSkipsConstantMainBonusButRealZeroDamageHitDoesNot() {
        var definitions = Map.of(FIRE, element(FIRE), WATER, element(WATER));
        var index = ReactionIndex.build(Map.of(STEAM, reaction(STEAM, FIRE, List.of(bonus(20)))));
        for (boolean realHit : List.of(false, true)) {
            var plan = new ReactionEngine().react(new ReactionRequest(aura(), WATER, 1, 0,
                    definitions.get(WATER), index, definitions, 0, 0, 1, 100, 100, 0,
                    Optional.empty(), ignored -> true, null, realHit));
            assertEquals(realHit ? 20 : 0, plan.finalDamage());
            assertEquals(1, plan.labels().size());
            var appearance = com.elementalphase.display.DamageDisplayCoordinator.appearance(plan, 0x123456);
            assertEquals(realHit ? 0xFFFFFF : 0x123456, appearance.color());
            assertEquals(realHit ? 1 : 0, appearance.visibleLabels().size());
        }
    }

    @Test
    void minimumScaleIsCheckedBeforeConsumption() {
        var definitions = Map.of(FIRE, element(FIRE), WATER, element(WATER));
        var direction = new ReactionDirection(WATER, FIRE, 0.1, new ReactionConsumption(0.2, 1),
                List.of(), new ReactionDisplay(0xFFFFFF, true), List.of());
        var spec = new ReactionSpec(STEAM, 0, Set.of(WATER, FIRE), List.of(direction));
        var state = new ElementalState();
        state.putPermanent(FIRE, 0.2, 200);
        var plan = new ReactionEngine().react(new ReactionRequest(state, WATER, 1, 0,
                definitions.get(WATER), ReactionIndex.build(Map.of(STEAM, spec)), definitions,
                0, 0, 1, 100, 100, 0, Optional.empty(), ignored -> true, null, false));
        assertTrue(plan.labels().isEmpty());
        assertEquals(0.2, state.state(FIRE).effectiveAmount(0));
        assertEquals(1, state.state(WATER).effectiveAmount(0));
    }

    @Test
    void resistanceReducesOnlyReactionBonusNotOriginalAttack() {
        var state = aura();
        state.setReactionResistance(STEAM, 0.5);
        var plan = resolve(state, 0, 1, List.of(reaction(STEAM, FIRE, List.of(bonus(20)))));
        assertEquals(100, plan.baseDamageAfterResistance());
        assertEquals(10, plan.reactionPlan().mainDamageBonus());
        assertEquals(110, plan.finalPreArmorDamage());
    }

    @Test
    void immunityStillConsumesElementsAndSchedulesNonDamageActions() {
        var state = aura();
        state.setReactionResistance(STEAM, 1);
        var freeze = new ReactionAction.Special(List.of(new ReactionAction.SpecialEntry(
                ReactionAction.SpecialEntry.Kind.FREEZE, 100)), ignored -> 1);
        var plan = resolve(state, 0, 1, List.of(reaction(STEAM, FIRE, List.of(bonus(20), freeze))));
        assertEquals(100, plan.finalPreArmorDamage());
        assertEquals(0, state.state(FIRE).effectiveAmount(0));
        assertEquals(1, plan.reactionPlan().labels().size());
        assertEquals(freeze, plan.reactionPlan().actions().get(0).action());
        assertOrdinaryOriginalAttack(plan);
    }

    @Test
    void eachReactionUsesItsOwnResistanceWithinTheSameHit() {
        var state = aura();
        state.putPermanent(ICE, 1, 200);
        state.setReactionResistance(STEAM, 1);
        state.setReactionResistance(FREEZE, 0.5);
        var plan = resolve(state, 0, 2, List.of(reaction(STEAM, FIRE, List.of(bonus(20))),
                reaction(FREEZE, ICE, List.of(bonus(20)))));
        assertEquals(110, plan.finalPreArmorDamage());
        assertEquals(2, plan.reactionPlan().labels().size());
        var appearance = com.elementalphase.display.DamageDisplayCoordinator.appearance(plan.reactionPlan(), 0x123456);
        assertEquals(List.of(FREEZE), appearance.visibleLabels().stream()
                .map(com.elementalphase.display.PendingMainDamageTracker.ReactionLabel::id).toList());
    }

    @Test
    void originalDamageFormulaCompoundsElementAndReactionResistanceOnce() {
        var state = aura();
        state.setReactionResistance(STEAM, 0.5);
        var bonus = new ReactionAction.MainDamageBonus(new ReactionAction.Formula("original_damage",
                context -> context.originalDamage()), ignored -> 1);
        var plan = resolve(state, 0.5, 1, List.of(reaction(STEAM, FIRE, List.of(bonus))));
        assertEquals(50, plan.baseDamageAfterResistance());
        assertEquals(75, plan.finalPreArmorDamage());
    }

    @Test
    void expandedElementVulnerabilityIsNotClampedToTheOldRange() {
        assertEquals(300, resolve(new ElementalState(), -2, 1, List.of()).finalPreArmorDamage());
        assertEquals(120, resolve(aura(), 0, 1,
                List.of(reaction(STEAM, FIRE, List.of(bonus(20))))).finalPreArmorDamage());
    }

    private static CombatPipeline.CombatResult resolve(ElementalState state, double elementResistance,
                                                        double triggerAmount, List<ReactionSpec> reactions) {
        var definitions = Map.of(FIRE, element(FIRE), WATER, element(WATER), ICE, element(ICE));
        var byId = reactions.stream().collect(java.util.stream.Collectors.toMap(ReactionSpec::id, value -> value));
        var attack = new ElementAttackContext(WATER, triggerAmount, ElementAttackContext.SourceKind.ENCHANTMENT, WATER);
        return new CombatPipeline().resolve(new CombatPipeline.CombatInput(100, elementResistance, attack, state, 0,
                definitions.get(WATER), ReactionIndex.build(byId), definitions, null, 0, 100, 100, ignored -> true, null));
    }

    private static ReactionSpec reaction(ResourceLocation id, ResourceLocation aura, List<ReactionAction> actions) {
        return reaction(id, aura, 0, 0xFFFFFF, actions);
    }

    private static ReactionSpec reaction(ResourceLocation id, ResourceLocation aura, int priority, int color,
                                         List<ReactionAction> actions) {
        var direction = new ReactionDirection(WATER, aura, 0.1, new ReactionConsumption(1, 1), List.of(),
                new ReactionDisplay(color, true), actions);
        return new ReactionSpec(id, priority, Set.of(WATER, aura), List.of(direction));
    }

    private static void assertOrdinaryOriginalAttack(CombatPipeline.CombatResult result) {
        assertEquals(100, result.finalPreArmorDamage());
        assertEquals(0, result.reactionPlan().mainDamageBonus());
        var appearance = com.elementalphase.display.DamageDisplayCoordinator.appearance(result.reactionPlan(), 0x123456);
        assertEquals(0x123456, appearance.color());
        assertTrue(appearance.visibleLabels().isEmpty());
        assertTrue(result.reactionOutcome().reactions().isEmpty());
    }

    private static ReactionAction.MainDamageBonus bonus(double amount) {
        return new ReactionAction.MainDamageBonus(new ReactionAction.Formula(Double.toString(amount), ignored -> amount),
                ignored -> 1);
    }

    private static ElementalState aura() {
        var state = new ElementalState();
        state.putPermanent(FIRE, 1, 200);
        return state;
    }

    private static ElementDefinition element(ResourceLocation id) {
        return new ElementDefinition(id, ElementAttachmentPolicy.DEFAULT,
                new ElementDisplayDefinition("element.test." + id.getPath(), 0xFFFFFF, true, 0, Optional.empty()));
    }

    private static ResourceLocation id(String value) {
        return ResourceLocation.fromNamespaceAndPath("test", value);
    }
}
