package com.elementalphase.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElementDefinitionConstraintTest {
    @Test
    void clampsFixedPermanentAmountToElementMaximum() {
        Map<ResourceLocation, JsonElement> values = elementsWithMaximum(1.0D);
        values.put(resource("entity_profiles/zombie.json"), JsonParser.parseString("""
                {"selector":{"type":"entity_id","id":"minecraft:zombie"},
                 "permanent_elements":[{"element":"test:fire","amount":2.0}]}
                """));

        var report = new ElementDataParser().parseLenient(values);

        assertTrue(report.errors().isEmpty(), report.errors().toString());
        assertEquals(1.0D, report.snapshot().entityProfiles().get(0).permanentElements()
                .get(ResourceLocation.fromNamespaceAndPath("test", "fire")).amount());
    }


    @Test
    void skipsOnlyVirtualPermanentEntryAndClampsIntrinsicAttack() {
        Map<ResourceLocation, JsonElement> values = elementsWithMaximum(1.0D);
        values.put(resource("elements/wind.json"), JsonParser.parseString(
                "{\"attachment\":{\"mode\":\"virtual\"}}"));
        values.put(resource("entity_profiles/zombie.json"), JsonParser.parseString("""
                {"selector":{"type":"entity_id","id":"minecraft:zombie"},
                 "permanent_elements":[{"element":"test:fire","amount":2},{"element":"test:wind","amount":3}],
                 "intrinsic_attack":{"element":"test:fire","base_amount":4},
                 "resistances":[{"element":"test:wind","value":0.5}]}
                """));

        var report = new ElementDataParser().parseLenient(values);

        assertTrue(report.errors().isEmpty(), report.errors().toString());
        var profile = report.snapshot().entityProfiles().get(0);
        assertEquals(1, profile.permanentElements().size());
        assertEquals(1.0D, profile.intrinsicAttack().orElseThrow().baseAmount());
        assertEquals(0.5D, profile.resistances().get(ResourceLocation.fromNamespaceAndPath("test", "wind")));
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
