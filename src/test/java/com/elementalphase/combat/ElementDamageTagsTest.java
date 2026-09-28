package com.elementalphase.combat;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElementDamageTagsTest {
    @Test
    void mapsNestedElementIdsIntoTheirOwnNamespace() {
        assertEquals(id("custom:elements/magic/acid"), ElementDamageTags.tagId(id("custom:magic/acid")));
    }

    @Test
    void resolvesTheSingleMatchingActiveElement() {
        var result = ElementDamageTags.select(List.of(id("test:fire"), id("test:water")),
                tag -> tag.equals(id("test:elements/water")));

        assertEquals(id("test:water"), result.element().orElseThrow());
        assertFalse(result.conflicted());
    }

    @Test
    void rejectsAmbiguousDamageInsteadOfSelectingAnElement() {
        var tags = Set.of(id("test:elements/fire"), id("test:elements/water"));
        var result = ElementDamageTags.select(List.of(id("test:water"), id("test:fire")), tags::contains);

        assertTrue(result.conflicted());
        assertTrue(result.element().isEmpty());
        assertEquals(List.of(id("test:fire"), id("test:water")), result.matches());
    }

    @Test
    void ignoresTagsForElementsThatAreNotLoaded() {
        var result = ElementDamageTags.select(List.of(id("test:fire")),
                tag -> tag.equals(id("test:elements/removed")));

        assertFalse(result.conflicted());
        assertTrue(result.element().isEmpty());
    }

    private static ResourceLocation id(String value) {
        return ResourceLocation.parse(value);
    }

    @Test
    void scalesFixedSourceAmountAndClampsToElementMaximum() {
        var element = id("test:fire");
        var snapshot = new com.elementalphase.data.ElementDataParser().parseLenient(java.util.Map.of(
                id("test:elemental_phase/elements/fire.json"),
                com.google.gson.JsonParser.parseString("{\"attachment\":{\"max_amount\":2}}"))).snapshot();
        var candidate = new ProjectileElementSnapshot.Candidate(element, 1, id("test:source"), java.util.Optional.empty());
        assertEquals(1.5, AttackElementResolver.context(java.util.Optional.of(candidate), 1.5, snapshot,
                ElementAttackContext.SourceKind.DAMAGE_TYPE_TAG).orElseThrow().mountAmount());
        assertEquals(2, AttackElementResolver.context(java.util.Optional.of(candidate), 3, snapshot,
                ElementAttackContext.SourceKind.ENCHANTMENT).orElseThrow().mountAmount());
        assertTrue(AttackElementResolver.context(java.util.Optional.of(candidate), 0, snapshot,
                ElementAttackContext.SourceKind.ENCHANTMENT).isEmpty());
        assertTrue(AttackElementResolver.context(java.util.Optional.of(candidate), 1,
                com.elementalphase.data.ElementDataSnapshot.empty(), ElementAttackContext.SourceKind.ENCHANTMENT).isEmpty());
    }

    @Test
    void projectilePersistsOriginalElementStrengthAndRejectsOldFormat() {
        var candidate = new ProjectileElementSnapshot.Candidate(id("test:fire"), 1, id("test:source"),
                java.util.Optional.of(com.elementalphase.registry.ModEnchantments.enchantmentId(id("test:fire"))));
        var snapshot = new ProjectileElementSnapshot(true, 2.5, java.util.Optional.of(candidate), java.util.Optional.empty());
        var tag = new net.minecraft.nbt.CompoundTag();
        snapshot.writeTo(tag);
        assertEquals(snapshot, ProjectileElementSnapshot.readFrom(tag).orElseThrow());
        var value = tag.getCompound(ProjectileElementSnapshot.DATA_KEY);
        assertFalse(value.getCompound("enchantment").contains("application"));
        value.putInt("version", 2);
        assertTrue(ProjectileElementSnapshot.readFrom(tag).isEmpty());
    }
}
