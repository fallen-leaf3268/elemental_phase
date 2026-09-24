package com.elementalphase.data;

import com.elementalphase.data.model.ElementDefinition;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElementDefinitionParsingTest {
    @Test
    void parsesDefaultGroupedDefinition() {
        ElementDefinition value = parse("{}");

        assertTrue(value.application().fromAttack());
        assertTrue(value.application().fromReaction());
        assertTrue(value.attachment().retainAfterAttack());
        assertEquals(2, value.attachment().cooldownTicks());
        assertEquals(100, value.attachment().durationTicks());
        assertEquals(1_000_000.0D, value.attachment().maxAmount());
        assertEquals("element.test.sample", value.display().translationKey());
        assertEquals(0xFFFFFF, value.display().color());
        assertTrue(value.display().visibleInJade());
        assertEquals(0, value.display().order());
        assertTrue(value.display().icon().isEmpty());
    }

    @Test
    void parsesCompleteGroupedDefinition() {
        ElementDefinition value = parse("""
                {
                  "application":{"from_attack":false,"from_reaction":false},
                  "attachment":{"retain_after_attack":false,"cooldown_ticks":0,
                    "duration_ticks":2147483647,"max_amount":25.5},
                  "display":{"translation_key":"element.example.steam","color":"#4fafff",
                    "visible_in_jade":false,"order":-20,
                    "icon":"example:textures/gui/elements/steam.png"}
                }
                """);

        assertFalse(value.application().fromAttack());
        assertFalse(value.application().fromReaction());
        assertFalse(value.attachment().retainAfterAttack());
        assertEquals(0, value.attachment().cooldownTicks());
        assertEquals(Integer.MAX_VALUE, value.attachment().durationTicks());
        assertEquals(25.5D, value.attachment().maxAmount());
        assertEquals("element.example.steam", value.display().translationKey());
        assertEquals(0x4FAFFF, value.display().color());
        assertFalse(value.display().visibleInJade());
        assertEquals(-20, value.display().order());
        assertEquals(ResourceLocation.fromNamespaceAndPath("example", "textures/gui/elements/steam.png"),
                value.display().icon().orElseThrow());
    }

    @Test
    void disablesElementWithoutError() {
        var report = parseReport("{\"enabled\":false}");

        assertTrue(report.errors().isEmpty(), report.errors().toString());
        assertTrue(report.snapshot().elements().isEmpty());
    }

    @Test
    void rejectsLegacyAndUnknownFields() {
        assertRejected("{\"attachable\":true}");
        assertRejected("{\"mount_cooldown_ticks\":2}");
        assertRejected("{\"temporary_duration_ticks\":100}");
        assertRejected("{\"translation_key\":\"element.test.sample\"}");
        assertRejected("{\"jade_visible\":true}");
        assertRejected("{\"application\":{\"attack\":true}}");
        assertRejected("{\"application\":{\"reaction\":true}}");
        assertRejected("{\"attachment\":{\"unknown\":true}}");
        assertRejected("{\"display\":{\"unknown\":true}}");
    }

    @Test
    void rejectsInvalidValues() {
        assertRejected("{\"application\":{\"from_attack\":1}}");
        assertRejected("{\"attachment\":{\"cooldown_ticks\":-1}}");
        assertRejected("{\"attachment\":{\"duration_ticks\":0}}");
        assertRejected("{\"attachment\":{\"max_amount\":0.09}}");
        assertRejected("{\"attachment\":{\"max_amount\":1000000.1}}");
        assertRejected("{\"display\":{\"translation_key\":\"\"}}");
        assertRejected("{\"display\":{\"color\":\"blue\"}}");
        assertRejected("{\"display\":{\"color\":\"#12345\"}}");
        assertRejected("{\"display\":{\"icon\":\"Invalid ID\"}}");
    }

    private static ElementDefinition parse(String json) {
        var report = parseReport(json);
        assertTrue(report.errors().isEmpty(), report.errors().toString());
        return report.snapshot().elements().get(id("sample"));
    }

    private static void assertRejected(String json) {
        var report = parseReport(json);
        assertFalse(report.errors().isEmpty(), json);
        assertTrue(report.snapshot().elements().isEmpty(), json);
    }

    private static ElementDataParser.ParseReport parseReport(String json) {
        return new ElementDataParser().parseLenient(Map.of(
                ResourceLocation.fromNamespaceAndPath("test", "elemental_phase/elements/sample.json"),
                JsonParser.parseString(json)));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }
}
