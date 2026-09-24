package com.elementalphase.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElementDefinitionConstraintTest {
    @Test
    void rejectsFixedPermanentAmountAboveElementMaximum() {
        Map<ResourceLocation, JsonElement> values = elementsWithMaximum(1.0D);
        values.put(resource("entity_profiles/zombie.json"), JsonParser.parseString("""
                {"selector":{"type":"entity_id","id":"minecraft:zombie"},
                 "permanent_elements":{"test:fire":{"amount":2.0}}}
                """));

        var report = new ElementDataParser().parseLenient(values);

        assertFalse(report.errors().isEmpty());
        assertTrue(report.snapshot().entityProfiles().isEmpty());
    }

    @Test
    void rejectsFixedAttackAmountAboveElementMaximum() {
        Map<ResourceLocation, JsonElement> values = elementsWithMaximum(1.0D);
        values.put(resource("attack_sources/fire.json"), JsonParser.parseString("""
                {"kind":"damage_type_id","selector":"minecraft:generic",
                 "element":"test:fire","base_amount":2.0}
                """));

        var report = new ElementDataParser().parseLenient(values);

        assertFalse(report.errors().isEmpty());
        assertTrue(report.snapshot().attackSources().isEmpty());
    }

    private static Map<ResourceLocation, JsonElement> elementsWithMaximum(double maximum) {
        Map<ResourceLocation, JsonElement> values = new LinkedHashMap<>();
        values.put(resource("elements/fire.json"), JsonParser.parseString(
                "{\"attachment\":{\"max_amount\":" + maximum + "}}"));
        return values;
    }

    private static ResourceLocation resource(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", "elemental_phase/" + path);
    }
}
