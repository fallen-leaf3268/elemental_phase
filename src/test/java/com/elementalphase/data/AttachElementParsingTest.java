package com.elementalphase.data;

import com.elementalphase.data.model.ReactionAction;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AttachElementParsingTest {
    @Test
    void parsesVirtualAttachmentAndRejectsUnknownElements() {
        var report = new ElementDataParser().parseLenient(resources(true));
        assertTrue(report.errors().isEmpty(), report.errors().toString());
        var reaction = report.snapshot().reactions().get(id("freeze"));
        var action = assertInstanceOf(ReactionAction.AttachElement.class,
                reaction.directions().get(0).actions().get(0));
        assertEquals(id("frozen"), action.element());
        assertEquals("scale", action.amount().source());
        assertTrue(ElementDataRuntimeValidator.validate(report).errors().isEmpty());

        var denied = ElementDataRuntimeValidator.validate(new ElementDataParser().parseLenient(resources(false)));
        assertFalse(denied.errors().isEmpty());
        assertTrue(denied.snapshot().reactions().isEmpty());
    }

    private static Map<ResourceLocation, com.google.gson.JsonElement> resources(boolean allowed) {
        Map<ResourceLocation, com.google.gson.JsonElement> values = new LinkedHashMap<>();
        values.put(resource("elements/fire.json"), JsonParser.parseString("{}"));
        if (allowed) {
            values.put(resource("elements/frozen.json"), JsonParser.parseString(
                    "{\"attachment\":{\"mode\":\"virtual\"}}"));
        }
        values.put(resource("reactions/freeze.json"), JsonParser.parseString("""
                {"reactions":[{"unidirectional":{"trigger":{"element":"test:fire","ratio":1},
                "aura":{"element":"test:frozen","ratio":1}},
                "elements":[{"type":"attach_element","element":"test:frozen",
                "amount":"scale"}]}]}
                """));
        return values;
    }

    private static ResourceLocation resource(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", "elemental_phase/" + path);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }
}
