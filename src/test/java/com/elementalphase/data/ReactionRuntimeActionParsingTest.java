package com.elementalphase.data;

import com.elementalphase.data.model.ReactionAction;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ReactionRuntimeActionParsingTest {
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
                damageType -> damageType.equals(ResourceLocation.fromNamespaceAndPath("elemental_phase", "reaction")));
        assertTrue(report.errors().isEmpty(), report.errors().toString());
        assertEquals(5, report.snapshot().elements().size());
        assertEquals(7, report.snapshot().reactions().size());

        var frozen = report.snapshot().reactions().get(ResourceLocation.fromNamespaceAndPath("elemental_phase", "frozen"));
        frozen.directions().forEach(direction -> {
            var action = assertInstanceOf(ReactionAction.ApplyFreeze.class, direction.actions().get(0));
            assertEquals(100, action.durationTicks());
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
            assertEquals(0xB388FF, action.damage().color().orElseThrow());
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
                {"application":{"from_attack":false,"from_reaction":true},
                 "attachment":{"retain_after_attack":false,"cooldown_ticks":4,"duration_ticks":80},
                 "display":{"translation_key":"element.test.base","visible_in_jade":false}}
                """));
        var accepted = new ElementDataParser().parseLenient(values);
        assertTrue(accepted.errors().isEmpty(), accepted.errors().toString());
        assertFalse(accepted.snapshot().elements().get(id("base")).application().fromAttack());
        assertTrue(accepted.snapshot().elements().get(id("base")).application().fromReaction());
        assertFalse(accepted.snapshot().elements().get(id("base")).display().visibleInJade());

        assertElementRejected("behaviors", "{\"behaviors\":[]}");
        assertElementRejected("reaction_consumption", "{\"reaction_consumption\":\"consume_requested\"}");
    }

    @Test
    void parsesFreezeAndScheduledDamageActions() {
        var report = new ElementDataParser().parseLenient(resources(actions()));

        assertTrue(report.errors().isEmpty(), report.errors().toString());
        var actions = report.snapshot().reactions().get(id("runtime")).directions().get(0).actions();
        var freeze = assertInstanceOf(ReactionAction.ApplyFreeze.class, actions.get(0));
        assertEquals(ReactionAction.Target.TARGET, freeze.target());
        assertEquals(100, freeze.durationTicks());

        var scheduled = assertInstanceOf(ReactionAction.ScheduleDamage.class, actions.get(1));
        assertEquals(id("electro_charged"), scheduled.id());
        assertEquals(ReactionAction.Target.TARGET, scheduled.target());
        assertEquals(100, scheduled.durationTicks());
        assertEquals(20, scheduled.intervalTicks());
        assertEquals("scale * 0.75", scheduled.damage().formula().source());
        assertEquals(id("reaction"), scheduled.damage().damageType());
        assertEquals(id("lightning"), scheduled.damage().resistanceElement().orElseThrow().fixed());
        assertEquals(0xB388FF, scheduled.damage().color().orElseThrow());
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
                  {"type":"apply_freeze","target":"target","duration_ticks":100},
                  {
                    "type":"schedule_damage",
                    "id":"test:electro_charged",
                    "target":"target",
                    "duration_ticks":100,
                    "interval_ticks":20,
                    "damage":{
                      "formula":"scale * 0.75",
                      "damage_type":"test:reaction",
                      "resistance_element":"test:lightning",
                      "color":"#B388FF"
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
                  "elements":["test:water","test:lightning"],
                  "directions":[{
                    "trigger":"test:lightning",
                    "aura":"test:water",
                    "consumption":{"trigger":1,"aura":1},
                    "display":{},
                    "actions":%s
                  }]
                }
                """.formatted(actions)));
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
