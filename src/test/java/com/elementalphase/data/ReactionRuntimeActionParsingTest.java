package com.elementalphase.data;

import com.elementalphase.data.model.ReactionAction;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ReactionRuntimeActionParsingTest {
    @Test
    void readmeReactionExamplesUseTheSameParser() throws IOException {
        var values = new LinkedHashMap<ResourceLocation, JsonElement>();
        for (String name : List.of("fire", "water", "ice", "lightning", "wind")) {
            var path = builtInResource("elements/" + name + ".json");
            values.put(path, load(path));
        }
        var elements = new ElementDataParser().parse(values).elements();
        String readme = java.nio.file.Files.readString(java.nio.file.Path.of("README.md"));
        var matches = java.util.regex.Pattern.compile("```json\\s*([\\s\\S]*?)```").matcher(readme);
        int reactions = 0;
        while (matches.find()) {
            var json = JsonParser.parseString(matches.group(1));
            if (!json.isJsonObject()) continue;
            var root = json.getAsJsonObject();
            if (!root.has("reactions")) continue;
            var report = new ReactionDataParser().parse(id("readme_" + reactions++), root, elements);
            assertTrue(report.errors().isEmpty(), report.errors().toString());
        }
        assertEquals(4, reactions);
    }

    @Test
    void matchingRejectsAmbiguousGroupsAndCapsExpandedDirections() {
        for (String group : List.of(
                "{}", "{\"elements\":[\"test:water\",\"test:lightning\"],\"directions\":[]}",
                "{\"bidirectional\":{},\"unidirectional\":{}}",
                "{\"bidirectional\":{\"elements\":[{\"element\":[],\"ratio\":1},{\"element\":\"test:water\",\"ratio\":1}]}}",
                "{\"bidirectional\":{\"elements\":[{\"element\":[\"test:water\",\"test:water\"],\"ratio\":1},{\"element\":\"test:lightning\",\"ratio\":1}]}}",
                "{\"bidirectional\":{\"elements\":[{\"element\":\"test:water\",\"ratio\":1},{\"element\":\"test:water\",\"ratio\":1}]}}")) {
            var values = resources("[]");
            values.put(resource("reactions/runtime.json"), JsonParser.parseString("{\"reactions\":[" + group + "]}"));
            var report = new ElementDataParser().parseLenient(values);
            assertFalse(report.errors().isEmpty(), group);
        }
        var values = resources("[]");
        var ids = new com.google.gson.JsonArray();
        for (int i = 0; i < 33; i++) {
            values.put(resource("elements/e" + i + ".json"), JsonParser.parseString("{}"));
            ids.add("test:e" + i);
        }
        values.put(resource("reactions/runtime.json"), JsonParser.parseString("""
                {"reactions":[{"bidirectional":{"elements":[{"element":%s,"ratio":1},{"element":"test:water","ratio":1}]}}]}
                """.formatted(ids)));
        var report = new ElementDataParser().parseLenient(values);
        assertTrue(report.errors().get(0).message().contains("64"));
    }

    @Test
    void directionsShareNameAndConditionsButKeepIndependentColorsAndActions() {
        var values = resources("[]");
        var root = JsonParser.parseString("""
                {"minimum_scale":0.25,"conditions":[{"type":"attacker_present","value":true}],
                 "display":{"translation_key":"reaction.test.shared_name","show_reaction":false},
                 "reactions":[
                   {"unidirectional":{"trigger":{"element":"test:lightning","ratio":1},
                     "aura":{"element":"test:water","ratio":1}},"color":"#aBcDeF",
                     "damage":[{"type":"additional_damage","formula":"scale"}]},
                   {"unidirectional":{"trigger":{"element":"test:water","ratio":2},
                     "aura":{"element":"test:lightning","ratio":1}},"color":"$trigger"}
                 ]}
                """).getAsJsonObject();
        values.put(resource("reactions/runtime.json"), root);
        values.put(resource("elements/water.json"), JsonParser.parseString("{\"display\":{\"color\":\"#4FAFFF\"}}"));
        var report = new ElementDataParser().parseLenient(values);
        assertTrue(report.errors().isEmpty(), report.errors().toString());
        var spec = report.snapshot().reactions().get(id("runtime"));
        assertEquals("reaction.test.shared_name", spec.translationKey());
        assertEquals(0xABCDEF, spec.directions().get(0).display().color().fixed());
        var reverse = spec.directions().get(1);
        assertEquals(0x4FAFFF, reverse.display().color().resolve(reverse.trigger(), reverse.aura(), report.snapshot().elements()));
        assertEquals(1, spec.directions().get(0).actions().size());
        assertTrue(reverse.actions().isEmpty());
        spec.directions().forEach(direction -> {
            assertEquals(0.25, direction.minimumScale());
            assertEquals(1, direction.conditions().size());
            assertFalse(direction.display().showReaction());
        });
        for (String display : List.of("{}", "{\"translation_key\":\"reaction.test.custom\"}", "{\"show_reaction\":false}")) {
            root.add("display", JsonParser.parseString(display));
            assertTrue(new ElementDataParser().parseLenient(values).errors().isEmpty(), display);
        }
        for (String display : List.of("{\"color\":\"#FFFFFF\"}", "{\"translation_key\":\" \"}",
                "{\"translation_key\":1}", "{\"translation_key\":null}", "{\"show_reaction\":0}", "{\"extra\":1}")) {
            root.add("display", JsonParser.parseString(display));
            assertFalse(new ElementDataParser().parseLenient(values).errors().isEmpty(), display);
        }
        root.remove("display");
        var item = root.getAsJsonArray("reactions").get(0).getAsJsonObject();
        for (String color : List.of("\"#FFF\"", "\"#FFFFFFFF\"", "null", "1")) {
            item.add("color", JsonParser.parseString(color));
            assertFalse(new ElementDataParser().parseLenient(values).errors().isEmpty(), color);
        }
        item.remove("color");
        var defaults = new ElementDataParser().parse(values).reactions().get(id("runtime"));
        assertEquals("reaction.test.runtime", defaults.translationKey());
        assertEquals(0xFFFFFF, defaults.directions().get(0).display().color().fixed());
    }

    @Test
    void actualMultiMatchElementControlsColorAndExpandedAreaActions() {
        var values = resources("[]");
        values.put(resource("elements/ice.json"), JsonParser.parseString("{\"display\":{\"color\":\"#9DEBFF\"}}"));
        values.put(resource("elements/water.json"), JsonParser.parseString("{\"display\":{\"color\":\"#4FAFFF\"}}"));
        values.put(resource("reactions/runtime.json"), JsonParser.parseString("""
                {"reactions":[{"unidirectional":{"trigger":{"element":"test:lightning","ratio":1},
                  "aura":{"element":["test:water","test:ice"],"ratio":0.5}},"color":"$aura",
                  "area":[{"radius":2.5,"damage":{"formula":"scale * 0.5"},
                  "attachment":{"element":"$aura","amount":"consumed_aura * 0.5"}}]}]}
                """));
        var report = new ElementDataParser().parseLenient(values);
        assertTrue(report.errors().isEmpty(), report.errors().toString());
        var spec = report.snapshot().reactions().get(id("runtime"));
        assertEquals(2, spec.directions().size());
        for (String aura : List.of("water", "ice")) {
            var state = new com.elementalphase.state.ElementalState();
            state.putPermanent(id(aura), 2, 200);
            var plan = new com.elementalphase.reaction.ReactionEngine().react(new com.elementalphase.reaction.ReactionRequest(
                    state, id("lightning"), 2, 0, report.snapshot().elements().get(id("lightning")),
                    report.snapshot().reactionIndex(), report.snapshot().elements(), 0, 0, 1, 100, 100, 0,
                    java.util.Optional.empty(), ignored -> true, null, false));
            assertEquals(aura.equals("water") ? 0x4FAFFF : 0x9DEBFF, plan.labels().get(0).color());
            var action = assertInstanceOf(ReactionAction.Area.class, plan.actions().get(0).action());
            assertTrue(action.damage().isPresent());
            assertTrue(action.attachment().isPresent());
        }
    }

    @Test
    void groupedComponentsUseFixedOrderAndPreserveOrderWithinEachGroup() {
        var values = resources("[]");
        var item = values.get(resource("reactions/runtime.json")).getAsJsonObject()
                .getAsJsonArray("reactions").get(0).getAsJsonObject();
        item.add("elements", JsonParser.parseString("""
                [{"type":"attach_element","element":"test:water","amount":"scale"},
                 {"type":"modify_element","target":"target","element":"$aura","operation":"clear"}]
                """));
        item.add("special", JsonParser.parseString("""
                [{"entries":[{"type":"ignite","duration_ticks":60},{"type":"freeze","duration_ticks":100}]}]
                """));
        item.add("mob_effects", JsonParser.parseString("""
                [{"effect":"minecraft:weakness","duration_ticks":100,"level":1}]
                """));
        item.add("area", JsonParser.parseString("""
                [{"radius":2.5,"damage":{"formula":"scale"},"attachment":{"element":"$aura","amount":"consumed_aura * 0.5"}}]
                """));
        item.add("damage", JsonParser.parseString("""
                [{"type":"main_damage_bonus","formula":"original_damage"},
                 {"type":"additional_damage","formula":"scale"},
                 {"type":"schedule_damage","id":"test:mark","duration_ticks":100,"interval_ticks":20,"damage":{"formula":"scale"}}]
                """));
        var report = new ElementDataParser().parseLenient(values);
        assertTrue(report.errors().isEmpty(), report.errors().toString());
        var directions = report.snapshot().reactions().get(id("runtime")).directions();
        for (var direction : directions) {
            assertEquals(List.of(ReactionAction.MainDamageBonus.class, ReactionAction.AdditionalDamage.class,
                    ReactionAction.ScheduleDamage.class, ReactionAction.Area.class, ReactionAction.MobEffect.class,
                    ReactionAction.Special.class, ReactionAction.AttachElement.class, ReactionAction.ModifyElement.class),
                    direction.actions().stream().map(Object::getClass).toList());
        }
        assertEquals(directions.get(0).actions(), directions.get(1).actions());
        for (String group : List.of("area", "mob_effects", "special")) {
            assertFalse(item.getAsJsonArray(group).get(0).getAsJsonObject().has("type"));
        }
    }

    @Test
    void rejectsOldStructureAndSharedSettingsInsideReactionEntries() {
        for (String legacy : List.of(
                "{\"bidirectional\":{\"elements\":[{\"element\":\"test:water\",\"ratio\":1},{\"element\":\"test:lightning\",\"ratio\":1}]}}",
                "{\"unidirectional\":{\"trigger\":{\"element\":\"test:water\",\"ratio\":1},\"aura\":{\"element\":\"test:lightning\",\"ratio\":1}},\"actions\":[]}")) {
            var values = resources("[]");
            values.put(resource("reactions/runtime.json"), JsonParser.parseString(legacy));
            assertFalse(new ElementDataParser().parseLenient(values).errors().isEmpty(), legacy);
        }
        for (String field : List.of("priority", "minimum_scale", "conditions", "display", "actions")) {
            var values = resources("[]");
            var item = values.get(resource("reactions/runtime.json")).getAsJsonObject()
                    .getAsJsonArray("reactions").get(0).getAsJsonObject();
            item.add(field, JsonParser.parseString("{}"));
            var report = new ElementDataParser().parseLenient(values);
            assertTrue(report.errors().get(0).message().contains("$.reactions[0]." + field));
        }
        var values = resources("[]");
        var item = values.get(resource("reactions/runtime.json")).getAsJsonObject()
                .getAsJsonArray("reactions").get(0).getAsJsonObject();
        item.remove("bidirectional");
        item.add("unidirectional", JsonParser.parseString("""
                {"trigger":{"element":"test:water","ratio":1},"aura":{"element":"test:lightning","ratio":1},"reverse":{}}
                """));
        assertTrue(new ElementDataParser().parseLenient(values).errors().get(0).message().contains("unidirectional.reverse"));
    }

    @Test
    void rejectsWrongComponentGroupsAndUnexpectedImplicitTypes() {
        Map<String, List<String>> invalid = Map.of(
                "damage", List.of("[{\"type\":\"area\",\"radius\":2,\"damage\":{\"formula\":\"scale\"}}]", "[{\"formula\":\"scale\"}]"),
                "elements", List.of("[{\"type\":\"additional_damage\",\"formula\":\"scale\"}]"),
                "area", List.of("[{\"type\":\"area\",\"radius\":2,\"damage\":{\"formula\":\"scale\"}}]"),
                "mob_effects", List.of("[{\"type\":\"mob_effect\",\"effect\":\"minecraft:weakness\",\"duration_ticks\":20}]"),
                "special", List.of("[{\"type\":\"special\",\"entries\":[{\"type\":\"freeze\",\"duration_ticks\":20}]}]"));
        invalid.forEach((group, definitions) -> {
            for (String definition : definitions) {
                var values = resources("[]");
                values.get(resource("reactions/runtime.json")).getAsJsonObject()
                        .getAsJsonArray("reactions").get(0).getAsJsonObject().add(group, JsonParser.parseString(definition));
                var report = new ElementDataParser().parseLenient(values);
                assertTrue(report.errors().get(0).message().contains("$.reactions[0]." + group), definition);
                assertFalse(report.snapshot().reactions().containsKey(id("runtime")));
            }
        });
    }

    @Test
    void rejectsDuplicateDirectionsWithoutDiscardingOtherReactionFiles() {
        var values = resources("[]");
        var definition = values.get(resource("reactions/runtime.json")).getAsJsonObject();
        values.put(resource("reactions/other.json"), definition.deepCopy());
        definition.getAsJsonArray("reactions").add(JsonParser.parseString("""
                {"unidirectional":{"trigger":{"element":"test:water","ratio":2},"aura":{"element":"test:lightning","ratio":1}},"color":"#FF0000"}
                """));
        var report = new ElementDataParser().parseLenient(values);
        assertEquals(Set.of(id("other")), report.snapshot().reactions().keySet());
        assertEquals(1, report.errors().size());
        assertTrue(report.errors().get(0).message().contains("$.reactions[1]"));
        assertTrue(report.errors().get(0).message().contains("重复"));
        assertTrue(report.errors().get(0).message().contains("test:water -> test:lightning"));
    }

    @Test
    void validatesReactionArraysAndCombinedComponentCount() {
        for (String value : List.of("[]", "null", "{}", "[null]", "[1]")) {
            var values = resources("[]");
            values.get(resource("reactions/runtime.json")).getAsJsonObject().add("reactions", JsonParser.parseString(value));
            assertFalse(new ElementDataParser().parseLenient(values).errors().isEmpty(), value);
        }
        var missing = resources("[]");
        missing.get(resource("reactions/runtime.json")).getAsJsonObject().remove("reactions");
        assertFalse(new ElementDataParser().parseLenient(missing).errors().isEmpty());
        var values = resources("[]");
        var item = values.get(resource("reactions/runtime.json")).getAsJsonObject()
                .getAsJsonArray("reactions").get(0).getAsJsonObject();
        for (String group : List.of("damage", "area", "mob_effects", "special", "elements")) {
            item.add(group, new JsonArray());
        }
        assertTrue(new ElementDataParser().parseLenient(values).errors().isEmpty());
        for (String group : List.of("damage", "area", "mob_effects", "special", "elements")) {
            for (String value : List.of("null", "{}", "[null]")) {
                item.add(group, JsonParser.parseString(value));
                assertFalse(new ElementDataParser().parseLenient(values).errors().isEmpty(), group + "=" + value);
            }
            item.add(group, new JsonArray());
        }
        for (int i = 0; i < 16; i++) {
            item.getAsJsonArray("damage").add(JsonParser.parseString("{\"type\":\"additional_damage\",\"formula\":\"scale\"}"));
            item.getAsJsonArray("elements").add(JsonParser.parseString("{\"type\":\"attach_element\",\"element\":\"test:water\",\"amount\":\"scale\"}"));
        }
        assertTrue(new ElementDataParser().parseLenient(values).errors().isEmpty());
        item.getAsJsonArray("elements").add(item.getAsJsonArray("elements").get(0).deepCopy());
        var report = new ElementDataParser().parseLenient(values);
        assertTrue(report.errors().get(0).message().contains("32"));
    }

    @Test
    void validatesAllNestedDamageTypesWithoutDroppingUnrelatedFiles() {
        for (String actions : List.of(
                "[{\"type\":\"additional_damage\",\"formula\":\"scale\",\"damage_type\":\"test:missing\"}]",
                "[{\"type\":\"area\",\"radius\":2,\"damage\":{\"formula\":\"scale\",\"damage_type\":\"test:missing\"}}]",
                "[{\"type\":\"schedule_damage\",\"id\":\"test:dot\",\"duration_ticks\":1,\"interval_ticks\":1,\"damage\":{\"formula\":\"scale\",\"damage_type\":\"test:missing\"}}]")) {
            var values = resources(actions);
            values.put(resource("reactions/valid.json"), resources("[]").get(resource("reactions/runtime.json")));
            var report = ElementDataRuntimeValidator.validateDamageTypes(new ElementDataParser().parseLenient(values),
                    Set.of(ReactionAction.DEFAULT_DAMAGE_TYPE, ReactionAction.DEFAULT_DOT_DAMAGE_TYPE)::contains);
            assertEquals(Set.of(id("valid")), report.snapshot().reactions().keySet());
            assertEquals(1, report.errors().size());
        }
    }

    @Test
    void dotUsesDedicatedDefaultTypeAndOnlyReactionTarget() {
        var report = new ElementDataParser().parseLenient(resources("""
                [{"type":"schedule_damage","id":"test:mark","duration_ticks":2147483647,"interval_ticks":2147483647,
                  "damage":{"formula":"target_health * 0.1 + scale","resistance_element":"$aura"}}]
                """));
        assertTrue(report.errors().isEmpty(), report.errors().toString());
        var action = assertInstanceOf(ReactionAction.ScheduleDamage.class,
                report.snapshot().reactions().get(id("runtime")).directions().get(0).actions().get(0));
        assertEquals(ResourceLocation.fromNamespaceAndPath("elemental_phase", "reaction_dot"), action.damage().damageType());
        assertTrue(ElementDataRuntimeValidator.validate(report).errors().isEmpty());
        assertReactionRejected("""
                [{"type":"schedule_damage","id":"test:mark","target":"target","duration_ticks":100,"interval_ticks":20,
                  "damage":{"formula":"scale"}}]
                """);
        assertReactionRejected("""
                [{"type":"schedule_damage","id":"test:mark","duration_ticks":100,"interval_ticks":20,
                  "damage":{"formula":"scale","color":"#FFFFFF"}}]
                """);
    }

    @Test
    void areaSupportsIndependentDamageAndAttachmentWithBasicFormulas() {
        for (String parts : List.of(
                "\"damage\":{\"formula\":\"scale\"}",
                "\"attachment\":{\"element\":\"$aura\",\"amount\":\"consumed_aura * 0.5\"}",
                "\"damage\":{\"formula\":\"scale * 0.5\"},\"attachment\":{\"element\":\"$aura\",\"amount\":\"consumed_aura * 0.5\"}")) {
            var report = new ElementDataParser().parseLenient(resources(
                    "[{\"type\":\"area\",\"radius\":\"clamp(scale + 2, 1, 96)\",\"max_targets\":2147483647," + parts + "}]"));
            assertTrue(report.errors().isEmpty(), report.errors().toString());
        }
        for (String action : List.of(
                "{\"type\":\"area\",\"radius\":2}", "{\"type\":\"area\",\"radius\":2,\"damage\":{}}",
                "{\"type\":\"area\",\"damage\":{\"formula\":\"scale\"}}",
                "{\"type\":\"area\",\"radius\":\"abs(scale)\",\"damage\":{\"formula\":\"scale\"}}",
                "{\"type\":\"area\",\"radius\":\"scale > 1\",\"damage\":{\"formula\":\"scale\"}}",
                "{\"type\":\"area\",\"radius\":2,\"damage\":{\"formula\":\"scale\",\"when\":\"1\"}}",
                "{\"type\":\"area\",\"radius\":2,\"attachment\":{\"element\":\"$aura\",\"amount\":\"target_health\"}}",
                "{\"type\":\"area_damage\",\"radius\":2,\"formula\":\"scale\"}",
                "{\"type\":\"spread_element\",\"radius\":2,\"element\":\"$aura\",\"amount\":\"1\"}")) {
            assertReactionRejected("[" + action + "]");
        }
    }

    @Test
    void attachmentUsesElementDefaultsAndRejectsTargetAndDurationOverrides() {
        var report = new ElementDataParser().parseLenient(resources(
                "[{\"type\":\"attach_element\",\"element\":\"test:water\",\"amount\":\"scale\"}]"));
        assertTrue(report.errors().isEmpty(), report.errors().toString());
        for (String field : List.of("\"target\":\"target\"", "\"duration_ticks\":100")) {
            assertReactionRejected("[{\"type\":\"attach_element\",\"element\":\"test:water\",\"amount\":\"scale\"," + field + "}]");
        }
    }

    @Test
    void specialStatesCoexistAndUseFullIntegerDurations() {
        for (int duration : List.of(1, 72001, Integer.MAX_VALUE)) {
            var report = new ElementDataParser().parseLenient(resources("""
                    [{"type":"special","entries":[{"type":"ignite","duration_ticks":%d},
                      {"type":"freeze","duration_ticks":%d}]}]
                    """.formatted(duration, duration)));
            assertTrue(report.errors().isEmpty(), report.errors().toString());
        }
        assertReactionRejected("[{\"type\":\"special\",\"entries\":[]}]");
        assertReactionRejected("[{\"type\":\"special\",\"entries\":[{\"type\":\"freeze\",\"duration_ticks\":0}]}]");
        assertReactionRejected("[{\"type\":\"ignite\",\"duration_ticks\":100}]");
        assertReactionRejected("[{\"type\":\"knockback\",\"strength\":\"1\"}]");
    }

    @Test
    void mobEffectLevelAcceptsFullIntegerRangeAndRejectsRemovedFields() {
        for (String field : List.of("", ",\"level\":1", ",\"level\":2147483647")) {
            var report = new ElementDataParser().parseLenient(resources(
                    "[{\"type\":\"mob_effect\",\"effect\":\"minecraft:weakness\",\"duration_ticks\":100" + field + "}]"));
            assertTrue(report.errors().isEmpty(), report.errors().toString());
        }
        for (String field : List.of("\"level\":-1", "\"level\":0.5", "\"level\":2147483648",
                "\"amplifier\":0", "\"target\":\"target\"", "\"ambient\":false", "\"visible\":true")) {
            assertReactionRejected("[{\"type\":\"mob_effect\",\"effect\":\"minecraft:weakness\",\"duration_ticks\":100," + field + "}]");
        }
    }

    @Test
    void formulaCapabilitiesAreValidatedDuringParsing() {
        var functions = Set.of("min", "max", "clamp");
        var variables = Set.of("scale");
        assertThrows(IllegalArgumentException.class, () -> com.elementalphase.reaction.formula.DamageFormulaParser
                .parseReaction("scale > 1", variables, functions, false));
        assertThrows(IllegalArgumentException.class, () -> com.elementalphase.reaction.formula.DamageFormulaParser
                .parseReaction("abs(scale)", variables, functions, false));
        assertThrows(IllegalArgumentException.class, () -> com.elementalphase.reaction.formula.DamageFormulaParser
                .parseReaction("target_health + scale", variables, functions, false));
        assertDoesNotThrow(() -> com.elementalphase.reaction.formula.DamageFormulaParser
                .parseReaction("clamp(2 + scale * 0.5, 1, 8)", variables, functions, false));
    }

    @Test
    void supportsSixSimpleConditionsAndRejectsRemovedShapes() {
        var values = resources("[]");
        var root = values.get(resource("reactions/runtime.json")).getAsJsonObject();
        root.add("conditions", JsonParser.parseString("""
                [{"type":"attacker_present","value":true},
                 {"type":"attacker_entity","entity":"minecraft:player"},
                 {"type":"target_entity","tag":"test:targets","inverted":true},
                 {"type":"damage_type","damage_type":"minecraft:magic"},
                 {"type":"minimum_damage","value":1},
                 {"type":"target_state","state":"frozen","value":false}]
                """));
        var report = new ElementDataParser().parseLenient(values);
        assertTrue(report.errors().isEmpty(), report.errors().toString());
        for (String condition : List.of(
                "{\"type\":\"source_kind\",\"value\":\"melee\"}",
                "{\"type\":\"target_is_boss\"}", "{\"type\":\"target_on_fire\"}",
                "{\"type\":\"target_in_water\"}", "{\"type\":\"attacker_present\",\"inverted\":false}",
                "{\"type\":\"target_state\",\"state\":\"on_fire\",\"inverted\":true}")) {
            root.add("conditions", JsonParser.parseString("[" + condition + "]"));
            assertFalse(new ElementDataParser().parseLenient(values).errors().isEmpty(), condition);
        }
    }

    @Test
    void rejectsDotRangeVariablesAndDamageSwitches() {
        for (String formula : List.of("distance", "radius", "scale + min(radius, 2)")) {
            assertReactionRejected("""
                    [{"type":"schedule_damage","id":"test:dot","duration_ticks":100,"interval_ticks":20,
                     "damage":{"formula":"%s"}}]
                    """.formatted(formula));
        }
        for (String field : List.of("bypass_armor", "bypass_invulnerability", "allow_element_application", "allow_reactions")) {
            assertReactionRejected("[{\"type\":\"additional_damage\",\"formula\":\"scale\",\"" + field + "\":false}]");
        }
    }

    @Test
    void normalizesMatchingGroupsAndKeepsAllDirectionNamesUnderRootControl() {
        var values = resources("[]");
        values.put(resource("reactions/runtime.json"), JsonParser.parseString("""
                {"priority":17,"reactions":[{"bidirectional":{"elements":[
                  {"element":"test:water","ratio":1},
                  {"element":"test:lightning","ratio":2}]}}]}
                """));
        var report = new ElementDataParser().parseLenient(values);
        assertTrue(report.errors().isEmpty(), report.errors().toString());
        var reaction = report.snapshot().reactions().get(id("runtime"));
        assertEquals(2, reaction.directions().size());
        assertEquals(17, reaction.priority());
        assertEquals(1, reaction.directions().get(0).consumption().trigger());
        assertEquals(2, reaction.directions().get(1).consumption().trigger());

        values.put(resource("reactions/runtime.json"), JsonParser.parseString("""
                {"display":{"show_reaction":false},"reactions":[
                  {"unidirectional":{"trigger":{"element":"test:water","ratio":1},
                    "aura":{"element":"test:lightning","ratio":2}},"color":"$trigger",
                    "damage":[{"type":"main_damage_bonus","formula":"original_damage"}]},
                  {"unidirectional":{"trigger":{"element":"test:lightning","ratio":1},
                    "aura":{"element":"test:water","ratio":0.5}},"color":"#ff5a36"}
                ]}
                """));
        report = new ElementDataParser().parseLenient(values);
        assertTrue(report.errors().isEmpty(), report.errors().toString());
        reaction = report.snapshot().reactions().get(id("runtime"));
        assertEquals(1, reaction.directions().get(0).actions().size());
        assertTrue(reaction.directions().get(1).actions().isEmpty());
        assertTrue(reaction.directions().stream().noneMatch(direction -> direction.display().showReaction()));
        values.get(resource("reactions/runtime.json")).getAsJsonObject()
                .getAsJsonArray("reactions").get(1).getAsJsonObject()
                .add("display", JsonParser.parseString("{\"show_reaction\":true}"));
        assertFalse(new ElementDataParser().parseLenient(values).errors().isEmpty());
    }

    @Test
    void rejectsEnabledForDatapacksAndSharedScriptParserWithoutDiscardingOtherReactions() {
        for (String value : List.of("true", "false", "null", "1")) {
            var values = resources("[]");
            var definition = values.get(resource("reactions/runtime.json")).getAsJsonObject();
            values.put(resource("reactions/other.json"), definition.deepCopy());
            definition.add("enabled", JsonParser.parseString(value));

            var report = new ElementDataParser().parseLenient(values);

            assertEquals(1, report.errors().size(), "enabled=" + value);
            assertTrue(report.errors().get(0).message().contains("enabled"));
            assertEquals(Set.of(id("other")), report.snapshot().reactions().keySet());
            assertEquals(2, report.snapshot().elements().size());
            var direct = new ReactionDataParser().parse(id("script"), definition, report.snapshot().elements());
            assertEquals(1, direct.errors().size());
            assertTrue(direct.errors().get(0).contains("enabled"));
            assertNull(direct.definition());
        }
    }

    @Test
    void blankReactionOverrideDoesNotFallBackOrDisableOtherReactions() {
        var values = resources("[]");
        var overridden = resource("reactions/runtime.json");
        values.put(resource("reactions/other.json"), values.get(overridden).deepCopy());
        values.put(overridden, JsonParser.parseString(" \n\t "));

        var report = new ElementDataParser().parseLenient(values);

        assertEquals(1, report.errors().size());
        assertEquals(overridden, report.errors().get(0).resource());
        assertEquals(Set.of(id("other")), report.snapshot().reactions().keySet());
    }

    @Test
    void reactionPriorityDefaultsAndRejectsDirectionOverrides() {
        var defaults = new ElementDataParser().parse(resources("[]")).reactions().get(id("runtime"));
        assertEquals(0, defaults.priority());

        for (int priority : new int[]{Integer.MIN_VALUE, 100, Integer.MAX_VALUE}) {
            var values = resources("[]");
            var definition = values.get(resource("reactions/runtime.json")).getAsJsonObject();
            definition.addProperty("priority", priority);
            var inherited = new ElementDataParser().parse(values).reactions().get(id("runtime"));
            assertEquals(priority, inherited.priority());
            assertEquals(Set.of(id("water"), id("lightning")), inherited.elements());

            definition.getAsJsonArray("reactions").get(0).getAsJsonObject()
                    .getAsJsonObject("bidirectional").addProperty("priority", 17);
            var rejected = new ElementDataParser().parseLenient(values);
            assertFalse(rejected.errors().isEmpty());
            assertFalse(rejected.snapshot().reactions().containsKey(id("runtime")));
        }
    }

    @Test
    void areaDamageCanIncludeReactionTarget() {
        var report = new ElementDataParser().parseLenient(resources("""
                [{"type":"area","radius":2.5,"damage":{"formula":"scale * 0.5","include_target":true},
                  "attachment":{"element":"$aura","amount":"consumed_aura * 0.5"}}]
                """));

        assertTrue(report.errors().isEmpty(), report.errors().toString());
        assertTrue(report.snapshot().reactions().containsKey(id("runtime")));
        var area = assertInstanceOf(ReactionAction.Area.class,
                report.snapshot().reactions().get(id("runtime")).directions().get(0).actions().get(0));
        assertTrue(area.damage().orElseThrow().includeTarget());
    }

    @Test
    void areaDamageIncludesTargetOnlyForExplicitTrue() {
        for (String field : List.of("", ",\"include_target\":false", ",\"include_target\":true")) {
            var snapshot = new ElementDataParser().parse(resources(
                    "[{\"type\":\"area\",\"radius\":2,\"damage\":{\"formula\":\"scale\"" + field + "}}]"));
            var area = assertInstanceOf(ReactionAction.Area.class,
                    snapshot.reactions().get(id("runtime")).directions().get(0).actions().get(0));
            assertEquals(field.endsWith("true"), area.damage().orElseThrow().includeTarget());
        }
    }

    @Test
    void includeTargetRequiresBooleanAndOnlyAppliesToAreaDamage() {
        for (String value : List.of("0", "\"true\"", "null", "{}", "[]")) {
            assertReactionRejected("[{\"type\":\"area\",\"radius\":2,\"damage\":{\"formula\":\"scale\",\"include_target\":" + value + "}}]");
        }
        assertReactionRejected("[{\"type\":\"additional_damage\",\"formula\":\"scale\",\"include_target\":true}]");
        assertReactionRejected("[{\"type\":\"area\",\"radius\":2,\"damage\":{\"formula\":\"scale\"},\"include_target\":true}]");
        assertReactionRejected("[{\"type\":\"area\",\"radius\":2,\"attachment\":{\"element\":\"$aura\",\"amount\":1,\"include_target\":true}}]");
    }

    @Test
    void builtInSwirlExplicitlyIncludesReactionTarget() {
        var definition = load(builtInResource("reactions/swirl.json")).getAsJsonObject();
        var damage = definition.getAsJsonArray("reactions").get(0).getAsJsonObject()
                .getAsJsonArray("area").get(0).getAsJsonObject().getAsJsonObject("damage");
        assertTrue(damage.has("include_target"));
        assertTrue(damage.get("include_target").getAsBoolean());
    }

    @Test
    void loadsSimplifiedBuiltInRuntimeActions() {
        Map<ResourceLocation, JsonElement> values = new LinkedHashMap<>();
        for (String element : List.of("fire", "water", "ice", "lightning", "wind")) {
            ResourceLocation resource = builtInResource("elements/" + element + ".json");
            values.put(resource, load(resource));
        }
        for (String reaction : List.of("vaporize", "melt", "frozen", "electro_charged", "overload",
                "superconduct", "swirl")) {
            ResourceLocation resource = builtInResource("reactions/" + reaction + ".json");
            values.put(resource, load(resource));
        }

        var parsed = new ElementDataParser().parseLenient(values);
        var report = ElementDataRuntimeValidator.validateDamageTypes(parsed,
                damageType -> Set.of(ReactionAction.DEFAULT_DAMAGE_TYPE, ReactionAction.DEFAULT_DOT_DAMAGE_TYPE).contains(damageType));
        assertTrue(report.errors().isEmpty(), report.errors().toString());
        assertEquals(5, report.snapshot().elements().size());
        assertEquals(7, report.snapshot().reactions().size());
        var expectedPriorities = Map.of("vaporize", 100, "melt", 100, "frozen", 90,
                "electro_charged", 70, "overload", 80, "superconduct", 80, "swirl", 60);
        expectedPriorities.forEach((name, priority) -> {
            var spec = report.snapshot().reactions().get(ResourceLocation.fromNamespaceAndPath("elemental_phase", name));
            assertEquals(priority.intValue(), spec.priority());
            assertEquals(name.equals("swirl") ? 4 : 2, spec.directions().size());
            assertTrue(spec.directions().stream().allMatch(direction -> direction.minimumScale() == 0.1));
        });
        for (String name : List.of("vaporize", "melt")) {
            var spec = report.snapshot().reactions().get(ResourceLocation.fromNamespaceAndPath("elemental_phase", name));
            assertEquals(new com.elementalphase.data.model.ReactionConsumption(1, 2), spec.directions().get(0).consumption());
            assertEquals(new com.elementalphase.data.model.ReactionConsumption(1, 0.5), spec.directions().get(1).consumption());
            assertEquals("original_damage * 1.0", assertInstanceOf(ReactionAction.MainDamageBonus.class,
                    spec.directions().get(0).actions().get(0)).formula().source());
            assertEquals("original_damage * 0.5", assertInstanceOf(ReactionAction.MainDamageBonus.class,
                    spec.directions().get(1).actions().get(0)).formula().source());
        }
        var overload = report.snapshot().reactions().get(ResourceLocation.fromNamespaceAndPath("elemental_phase", "overload"));
        assertEquals(0xB44CFF, overload.directions().get(0).display().color().fixed());
        assertEquals(0xFF5A36, overload.directions().get(1).display().color().fixed());
        overload.directions().forEach(direction -> {
            assertEquals(1, direction.actions().size());
            var area = assertInstanceOf(ReactionAction.Area.class, direction.actions().get(0));
            assertEquals("scale * 1.5", area.damage().orElseThrow().formula().source());
            assertFalse(area.damage().orElseThrow().includeTarget());
            assertTrue(area.attachment().isEmpty());
        });
        var swirl = report.snapshot().reactions().get(ResourceLocation.fromNamespaceAndPath("elemental_phase", "swirl"));
        swirl.directions().forEach(direction -> {
            var area = assertInstanceOf(ReactionAction.Area.class, direction.actions().get(0));
            assertEquals("scale * 0.5", area.damage().orElseThrow().formula().source());
            assertTrue(area.damage().orElseThrow().includeTarget());
            assertEquals("consumed_aura * 0.5", area.attachment().orElseThrow().amount().source());
            assertEquals(2.5, area.radius().evaluate(com.elementalphase.reaction.formula.ReactionFormulaContext.legacy(0, 1)));
        });
        var superconduct = report.snapshot().reactions().get(ResourceLocation.fromNamespaceAndPath("elemental_phase", "superconduct"));
        superconduct.directions().forEach(direction -> {
            assertEquals("scale", assertInstanceOf(ReactionAction.AdditionalDamage.class, direction.actions().get(0)).formula().source());
            var effect = assertInstanceOf(ReactionAction.MobEffect.class, direction.actions().get(1));
            assertEquals(100, effect.durationTicks());
            assertEquals(0, effect.level());
        });

        var frozen = report.snapshot().reactions().get(ResourceLocation.fromNamespaceAndPath("elemental_phase", "frozen"));
        frozen.directions().forEach(direction -> {
            var action = assertInstanceOf(ReactionAction.Special.class, direction.actions().get(0));
            assertEquals(100, action.entries().get(0).durationTicks());
            assertEquals(ReactionAction.SpecialEntry.Kind.FREEZE, action.entries().get(0).type());
        });
        var electro = report.snapshot().reactions().get(
                ResourceLocation.fromNamespaceAndPath("elemental_phase", "electro_charged"));
        electro.directions().forEach(direction -> {
            var action = assertInstanceOf(ReactionAction.ScheduleDamage.class, direction.actions().get(0));
            assertEquals(ResourceLocation.fromNamespaceAndPath("elemental_phase", "electro_charged"), action.id());
            assertEquals(100, action.durationTicks());
            assertEquals(20, action.intervalTicks());
            assertEquals("scale * 0.75", action.damage().formula().source());
            assertEquals(ResourceLocation.fromNamespaceAndPath("elemental_phase", "lightning"),
                    action.damage().resistanceElement().orElseThrow().fixed());
            assertEquals(0xB388FF, direction.display().color().fixed());
            assertEquals(ReactionAction.DEFAULT_DOT_DAMAGE_TYPE, action.damage().damageType());
        });
        var melt = report.snapshot().reactions().get(ResourceLocation.fromNamespaceAndPath("elemental_phase", "melt"));
        assertEquals(2, melt.elements().size());
        assertEquals(2, melt.directions().size());
        assertFalse(melt.elements().contains(ResourceLocation.fromNamespaceAndPath("elemental_phase", "frozen")));
    }

    @Test
    void acceptsOnlySimplifiedElementFields() {
        Map<ResourceLocation, JsonElement> values = new LinkedHashMap<>();
        values.put(resource("elements/base.json"), JsonParser.parseString("""
                {"attachment":{"mode":"virtual","cooldown_ticks":4,"duration_ticks":80},
                 "display":{"translation_key":"element.test.base","visible_in_jade":false}}
                """));
        var accepted = new ElementDataParser().parseLenient(values);
        assertTrue(accepted.errors().isEmpty(), accepted.errors().toString());
        assertTrue(accepted.snapshot().elements().get(id("base")).attachment().virtual());
        assertFalse(accepted.snapshot().elements().get(id("base")).display().visibleInJade());

        assertElementRejected("behaviors", "{\"behaviors\":[]}");
        assertElementRejected("reaction_consumption", "{\"reaction_consumption\":\"consume_requested\"}");
    }

    @Test
    void parsesFreezeAndScheduledDamageActions() {
        var report = new ElementDataParser().parseLenient(resources(actions()));

        assertTrue(report.errors().isEmpty(), report.errors().toString());
        var actions = report.snapshot().reactions().get(id("runtime")).directions().get(0).actions();
        var freeze = assertInstanceOf(ReactionAction.Special.class, actions.get(1));
        assertEquals(100, freeze.entries().get(0).durationTicks());

        var scheduled = assertInstanceOf(ReactionAction.ScheduleDamage.class, actions.get(0));
        assertEquals(id("electro_charged"), scheduled.id());
        assertEquals(100, scheduled.durationTicks());
        assertEquals(20, scheduled.intervalTicks());
        assertEquals("scale * 0.75", scheduled.damage().formula().source());
        assertEquals(id("reaction"), scheduled.damage().damageType());
        assertEquals(id("lightning"), scheduled.damage().resistanceElement().orElseThrow().fixed());
    }

    @Test
    void acceptsZeroDurationScheduledDamage() {
        var report = new ElementDataParser().parseLenient(resources("""
                [{"type":"schedule_damage","id":"test:instant","duration_ticks":0,"interval_ticks":20,
                  "damage":{"formula":"scale"}}]
                """));

        assertTrue(report.errors().isEmpty(), report.errors().toString());
        var action = assertInstanceOf(ReactionAction.ScheduleDamage.class,
                report.snapshot().reactions().get(id("runtime")).directions().get(0).actions().get(0));
        assertEquals(0, action.durationTicks());
    }

    @Test
    void rejectsInvalidRuntimeActionShapes() {
        assertReactionRejected("[{\"type\":\"apply_freeze\",\"duration_ticks\":0}]");
        assertReactionRejected("[{\"type\":\"apply_freeze\",\"duration_ticks\":72001}]");
        assertReactionRejected("[{\"type\":\"freeze\",\"duration_ticks\":100}]");
        assertReactionRejected("[{\"type\":\"schedule_damage\",\"id\":\"test:x\","
                + "\"duration_ticks\":-1,\"interval_ticks\":20,\"damage\":{\"formula\":\"scale\"}}]");
        assertReactionRejected("[{\"type\":\"schedule_damage\",\"id\":\"test:x\","
                + "\"duration_ticks\":10,\"interval_ticks\":0,\"damage\":{\"formula\":\"scale\"}}]");
        assertReactionRejected("[{\"type\":\"apply_freeze\",\"duration_ticks\":100,\"extra\":true}]");
        assertReactionRejected("[{\"type\":\"schedule_damage\",\"id\":\"test:x\","
                + "\"duration_ticks\":10,\"interval_ticks\":20,"
                + "\"damage\":{\"formula\":\"scale\",\"extra\":true}}]");
        assertReactionRejected("[{\"type\":\"schedule_damage\",\"id\":\"test:x\","
                + "\"duration_ticks\":10,\"interval_ticks\":20,"
                + "\"damage\":{\"formula\":\"scale\",\"resistance_element\":\"test:missing\"}}]");
    }

    @Test
    void runtimeValidationRejectsUnknownScheduledDamageType() {
        var parsed = new ElementDataParser().parseLenient(resources("""
                [{"type":"schedule_damage","id":"test:x","duration_ticks":10,"interval_ticks":20,
                  "damage":{"formula":"scale","damage_type":"test:missing"}}]
                """));
        var validated = ElementDataRuntimeValidator.validateDamageTypes(parsed, id("reaction")::equals);

        assertFalse(validated.snapshot().reactions().containsKey(id("runtime")));
        assertTrue(validated.errors().stream().anyMatch(error -> error.resource().equals(
                resource("reactions/runtime.json"))), validated.errors().toString());
    }

    private static String actions() {
        return """
                [
                  {"type":"special","entries":[{"type":"freeze","duration_ticks":100}]},
                  {
                    "type":"schedule_damage",
                    "id":"test:electro_charged",
                    "duration_ticks":100,
                    "interval_ticks":20,
                    "damage":{
                      "formula":"scale * 0.75",
                      "damage_type":"test:reaction",
                      "resistance_element":"test:lightning"
                    }
                  }
                ]
                """;
    }

    private static Map<ResourceLocation, JsonElement> resources(String actions) {
        Map<ResourceLocation, JsonElement> values = new LinkedHashMap<>();
        values.put(resource("elements/water.json"), JsonParser.parseString("{}"));
        values.put(resource("elements/lightning.json"), JsonParser.parseString("{}"));
        values.put(resource("reactions/runtime.json"), JsonParser.parseString("""
                {
                  "reactions":[{"bidirectional":{
                    "elements":[{"element":"test:lightning","ratio":1},
                                {"element":"test:water","ratio":1}]
                  }}]
                }
                """));
        var item = values.get(resource("reactions/runtime.json")).getAsJsonObject()
                .getAsJsonArray("reactions").get(0).getAsJsonObject();
        for (var value : JsonParser.parseString(actions).getAsJsonArray()) {
            var component = value.getAsJsonObject().deepCopy();
            String type = component.has("type") ? component.get("type").getAsString() : "";
            String group = switch (type) {
                case "area" -> "area";
                case "mob_effect" -> "mob_effects";
                case "special" -> "special";
                case "attach_element", "modify_element" -> "elements";
                default -> "damage";
            };
            if (Set.of("area", "mob_effect", "special").contains(type)) component.remove("type");
            if (!item.has(group)) item.add(group, new JsonArray());
            item.getAsJsonArray(group).add(component);
        }
        return values;
    }

    private static void assertElementRejected(String path, String json) {
        Map<ResourceLocation, JsonElement> values = new LinkedHashMap<>();
        ResourceLocation resource = resource("elements/" + path + ".json");
        values.put(resource, JsonParser.parseString(json));
        var report = new ElementDataParser().parseLenient(values);
        assertFalse(report.snapshot().elements().containsKey(id(path)));
        assertTrue(report.errors().stream().anyMatch(error -> error.resource().equals(resource)),
                report.errors().toString());
    }

    private static void assertReactionRejected(String actions) {
        ResourceLocation resource = resource("reactions/runtime.json");
        var report = new ElementDataParser().parseLenient(resources(actions));
        assertFalse(report.snapshot().reactions().containsKey(id("runtime")));
        assertTrue(report.errors().stream().anyMatch(error -> error.resource().equals(resource)),
                report.errors().toString());
    }

    private static ResourceLocation resource(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", "elemental_phase/" + path);
    }

    private static ResourceLocation builtInResource(String path) {
        return ResourceLocation.fromNamespaceAndPath("elemental_phase", "elemental_phase/" + path);
    }

    private static JsonElement load(ResourceLocation resource) {
        String path = "data/" + resource.getNamespace() + "/" + resource.getPath();
        try (var stream = ReactionRuntimeActionParsingTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, () -> "Missing built-in resource " + path);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }
}
