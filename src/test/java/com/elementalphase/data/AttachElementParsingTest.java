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
    void parsesReactionProductAndValidatesPermission() {
        var report = new ElementDataParser().parseLenient(resources(true));
        assertTrue(report.errors().isEmpty(), report.errors().toString());
        var reaction = report.snapshot().reactions().get(id("freeze"));
        var action = assertInstanceOf(ReactionAction.AttachElement.class,
                reaction.directions().get(0).actions().get(0));
        assertEquals(id("frozen"), action.element());
        assertEquals(100, action.durationTicks().orElseThrow());
        assertTrue(ElementDataRuntimeValidator.validate(report).errors().isEmpty());

        var denied = ElementDataRuntimeValidator.validate(new ElementDataParser().parseLenient(resources(false)));
        assertFalse(denied.errors().isEmpty());
        assertTrue(denied.snapshot().reactions().isEmpty());
    }

    private static Map<ResourceLocation, com.google.gson.JsonElement> resources(boolean allowed) {
        Map<ResourceLocation, com.google.gson.JsonElement> values = new LinkedHashMap<>();
        values.put(resource("elements/fire.json"), JsonParser.parseString("{}"));
        values.put(resource("elements/frozen.json"), JsonParser.parseString(
                "{\"application\":{\"from_attack\":false,\"from_reaction\":" + allowed + "}," +
                        "\"attachment\":{\"retain_after_attack\":false}}"));
        values.put(resource("reactions/freeze.json"), JsonParser.parseString("""
                {"elements":["test:fire","test:frozen"],"directions":[{
                "trigger":"test:fire","aura":"test:frozen","consumption":{"trigger":1,"aura":1},
                "display":{},"actions":[{"type":"attach_element","element":"test:frozen",
                "amount":"scale","duration_ticks":100}]}]}
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
