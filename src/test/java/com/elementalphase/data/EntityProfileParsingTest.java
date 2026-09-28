package com.elementalphase.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityProfileParsingTest {
    private static final String SELECTOR = "\"selector\":{\"type\":\"entity_id\",\"id\":\"minecraft:zombie\"}";

    @Test
    void rejectsBothLegacyEnabledValues() {
        assertRejected("enabled", "true");
        assertRejected("enabled", "false");
    }

    @Test
    void rejectsElementStrengthInEntityProfiles() {
        for (String value : new String[]{"0", "1", "2.5", "null"}) {
            assertRejected("element_strength", value);
        }
    }

    @Test
    void intrinsicBaseAmountRemainsConfigurableWithoutElementStrength() {
        var defaults = report("intrinsic_attack", "{\"element\":\"test:fire\"}");
        var customized = report("intrinsic_attack", "{\"element\":\"test:fire\",\"base_amount\":2.5}");

        assertTrue(defaults.errors().isEmpty());
        assertTrue(customized.errors().isEmpty());
        assertEquals(1.0D, defaults.snapshot().entityProfiles().get(0).intrinsicAttack().orElseThrow().baseAmount());
        assertEquals(2.5D, customized.snapshot().entityProfiles().get(0).intrinsicAttack().orElseThrow().baseAmount());
    }

    @Test
    void parsesMultiplePermanentEntriesWithIndependentAmountsAndRestoreDelays() {
        Map<ResourceLocation, JsonElement> resources = resources();
        resources.put(id("test:elemental_phase/elements/water.json"), JsonParser.parseString("{}"));
        resources.put(id("test:elemental_phase/entity_profiles/zombie.json"), JsonParser.parseString("""
                {"selector":{"type":"entity_id","id":"minecraft:zombie"},
                 "permanent_elements":[{"element":"test:fire","amount":10.5},
                                       {"element":"test:water","amount":4,"restore_delay_ticks":7}]}
                """));

        var result = new ElementDataParser().parseLenient(resources);

        assertTrue(result.errors().isEmpty(), result.errors().toString());
        var entries = result.snapshot().entityProfiles().get(0).permanentElements();
        assertEquals(2, entries.size());
        assertEquals(10.5D, entries.get(id("test:fire")).amount());
        assertEquals(200, entries.get(id("test:fire")).restoreDelayTicks());
        assertEquals(4.0D, entries.get(id("test:water")).amount());
        assertEquals(7, entries.get(id("test:water")).restoreDelayTicks());
    }

    @Test
    void emptyPermanentListProvidesNoPermanentElements() {
        var result = report("permanent_elements", "[]");

        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertTrue(result.snapshot().entityProfiles().get(0).permanentElements().isEmpty());
    }

    @Test
    void rejectsLegacyAndInvalidPermanentEntryShapes() {
        for (String value : new String[]{"{}", "null", "{\"test:fire\":{\"amount\":2}}", "{\"test:fire\":null}",
                "[null]", "[1]", "[[]]", "[{}]", "[{\"element\":\"test:fire\"}]",
                "[{\"amount\":2}]", "[{\"element\":\"test:missing\",\"amount\":2}]",
                "[{\"element\":null,\"amount\":2}]",
                "[{\"element\":\"test:fire\",\"amount\":2,\"enabled\":true}]"}) {
            assertRejected("permanent_elements", value);
        }
    }

    @Test
    void rejectsDuplicatePermanentElementEntries() {
        var result = report("permanent_elements", """
                [{"element":"test:fire","amount":2},
                 {"element":"test:fire","amount":4,"restore_delay_ticks":20}]
                """);

        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).message().contains("Duplicate permanent element"));
        assertTrue(result.snapshot().entityProfiles().isEmpty());
    }

    @Test
    void permanentEntryAmountAndRestoreBoundsRemainUnchanged() {
        for (String amount : new String[]{"0.000001", "1000000"}) {
            for (String delay : new String[]{"1", "2147483647"}) {
                var result = report("permanent_elements", "[{\"element\":\"test:fire\",\"amount\":" + amount
                        + ",\"restore_delay_ticks\":" + delay + "}]");
                assertTrue(result.errors().isEmpty(), result.errors().toString());
                var entry = result.snapshot().entityProfiles().get(0).permanentElements().get(id("test:fire"));
                assertEquals(Double.parseDouble(amount), entry.amount());
                assertEquals(Integer.parseInt(delay), entry.restoreDelayTicks());
            }
        }
        for (String amount : new String[]{"0", "-1", "1000001", "null", "\"2\"", "1e400"}) {
            assertRejected("permanent_elements", "[{\"element\":\"test:fire\",\"amount\":" + amount + "}]");
        }
        for (String delay : new String[]{"0", "-1", "2147483648", "1.5", "null", "\"20\""}) {
            assertRejected("permanent_elements", "[{\"element\":\"test:fire\",\"amount\":2,\"restore_delay_ticks\":" + delay + "}]");
        }
    }

    @Test
    void acceptsExpandedElementAndReactionResistanceRanges() {
        for (String value : new String[]{"-2147483647", "1", "-10.25", "0.5"}) {
            var result = report("resistances", "[{\"element\":\"test:fire\",\"value\":" + value
                    + "},{\"reaction\":\"kubejs:future_reaction\",\"value\":" + value + "}]");
            assertTrue(result.errors().isEmpty(), result.errors().toString());
            var profile = result.snapshot().entityProfiles().get(0);
            assertEquals(Double.parseDouble(value), profile.resistances().get(id("test:fire")));
            assertEquals(Double.parseDouble(value), profile.reactionResistances().get(id("kubejs:future_reaction")));
        }
    }

    @Test
    void rejectsOutOfRangeAndNullResistanceValues() {
        for (String kind : new String[]{"element", "reaction"}) {
            for (String value : new String[]{"-2147483648", "1.01", "null", "\"0.5\"", "1e400"}) {
                assertRejected("resistances", "[{\"" + kind + "\":\"test:fire\",\"value\":" + value + "}]");
            }
        }
        assertRejected("resistances", "null");
    }

    @Test
    void rejectsIncompleteAndInvalidReactionIds() {
        for (String key : new String[]{"", "reaction", ":reaction", "test:", "test:Bad", "test:a b", "test:a:b"}) {
            assertRejected("resistances", "[{\"reaction\":\"" + key + "\",\"value\":0.25}]");
        }
    }

    @Test
    void mixedResistanceEntriesKeepElementAndReactionIdsIndependent() {
        var result = report("resistances", """
                [{"reaction":"test:fire","value":-0.5},
                 {"element":"test:fire","value":0.25},
                 {"reaction":"kubejs:future","value":1}]
                """);

        assertTrue(result.errors().isEmpty(), result.errors().toString());
        var profile = result.snapshot().entityProfiles().get(0);
        assertEquals(Map.of(id("test:fire"), 0.25D), profile.resistances());
        assertEquals(Map.of(id("test:fire"), -0.5D, id("kubejs:future"), 1.0D), profile.reactionResistances());
    }

    @Test
    void emptyResistanceListUsesZeroResistanceDefaults() {
        var result = report("resistances", "[]");

        assertTrue(result.errors().isEmpty(), result.errors().toString());
        var profile = result.snapshot().entityProfiles().get(0);
        assertTrue(profile.resistances().isEmpty());
        assertTrue(profile.reactionResistances().isEmpty());
    }

    @Test
    void rejectsLegacyResistanceObjectsAndSeparateReactionField() {
        assertRejected("resistances", "{}");
        assertRejected("resistances", "{\"test:fire\":0.5}");
        assertRejected("reaction_resistances", "{}");
        assertRejected("reaction_resistances", "{\"test:fire\":0.5}");
        assertRejected("reaction_resistances", "[]");
    }

    @Test
    void rejectsInvalidResistanceEntryShapesAndUnknownElements() {
        for (String entry : new String[]{"null", "1", "[]", "{}", "{\"value\":0.5}",
                "{\"element\":\"test:fire\"}", "{\"reaction\":\"test:future\"}",
                "{\"element\":\"test:fire\",\"reaction\":\"test:future\",\"value\":0.5}",
                "{\"element\":\"test:missing\",\"value\":0.5}",
                "{\"element\":null,\"value\":0.5}", "{\"reaction\":1,\"value\":0.5}",
                "{\"element\":\"test:fire\",\"value\":0.5,\"enabled\":true}"}) {
            assertRejected("resistances", "[" + entry + "]");
        }
    }

    @Test
    void rejectsDuplicateResistanceTargetsWithinEachKind() {
        for (String kind : new String[]{"element", "reaction"}) {
            var result = report("resistances", "[{\"" + kind + "\":\"test:fire\",\"value\":0.25},"
                    + "{\"" + kind + "\":\"test:fire\",\"value\":0.5}]");

            assertEquals(1, result.errors().size());
            assertTrue(result.errors().get(0).message().contains("Duplicate " + kind + " resistance"));
            assertTrue(result.snapshot().entityProfiles().isEmpty());
        }
    }

    @Test
    void invalidResistanceEntrySkipsOnlyItsProfileWithoutPartialApplication() {
        Map<ResourceLocation, JsonElement> resources = resources();
        resources.put(id("test:elemental_phase/entity_profiles/invalid.json"), JsonParser.parseString(
                "{" + SELECTOR + ",\"resistances\":[{\"element\":\"test:fire\",\"value\":0.5},"
                        + "{\"reaction\":\"test:future\",\"value\":2}]}"));
        resources.put(id("test:elemental_phase/entity_profiles/valid.json"), JsonParser.parseString(
                "{" + SELECTOR + ",\"resistances\":[{\"reaction\":\"test:future\",\"value\":0.25}]}"));

        var result = new ElementDataParser().parseLenient(resources);

        assertEquals(1, result.errors().size());
        assertEquals(1, result.snapshot().entityProfiles().size());
        var profile = result.snapshot().entityProfiles().get(0);
        assertEquals(id("test:valid"), profile.id());
        assertTrue(profile.resistances().isEmpty());
        assertEquals(Map.of(id("test:future"), 0.25D), profile.reactionResistances());
    }

    @Test
    void blankOverrideSkipsSameResourceWithoutFallingBack() {
        Map<ResourceLocation, JsonElement> resources = resources();
        ResourceLocation path = id("test:elemental_phase/entity_profiles/zombie.json");
        resources.put(path, JsonParser.parseString("{" + SELECTOR + "}"));
        resources.put(path, JsonParser.parseString(" \n\t "));

        var result = new ElementDataParser().parseLenient(resources);

        assertEquals(1, result.errors().size());
        assertTrue(result.snapshot().entityProfiles().isEmpty());
    }

    @Test
    void blankOverrideLeavesOtherValidProfilesAvailable() {
        Map<ResourceLocation, JsonElement> resources = resources();
        resources.put(id("test:elemental_phase/entity_profiles/blank.json"), JsonParser.parseString(""));
        resources.put(id("test:elemental_phase/entity_profiles/other.json"), JsonParser.parseString("{" + SELECTOR + "}"));

        var result = new ElementDataParser().parseLenient(resources);

        assertEquals(1, result.errors().size());
        assertEquals(1, result.snapshot().entityProfiles().size());
        assertEquals(id("test:other"), result.snapshot().entityProfiles().get(0).id());
    }

    private static void assertRejected(String field, String value) {
        var report = report(field, value);
        assertEquals(1, report.errors().size(), field + "=" + value);
        assertTrue(report.snapshot().entityProfiles().isEmpty());
    }

    private static ElementDataParser.ParseReport report(String field, String value) {
        Map<ResourceLocation, JsonElement> resources = resources();
        resources.put(id("test:elemental_phase/entity_profiles/zombie.json"),
                JsonParser.parseString("{" + SELECTOR + ",\"" + field + "\":" + value + "}"));
        return new ElementDataParser().parseLenient(resources);
    }

    private static Map<ResourceLocation, JsonElement> resources() {
        Map<ResourceLocation, JsonElement> resources = new LinkedHashMap<>();
        resources.put(id("test:elemental_phase/elements/fire.json"), JsonParser.parseString("{}"));
        return resources;
    }

    private static ResourceLocation id(String value) {
        return ResourceLocation.parse(value);
    }
}
