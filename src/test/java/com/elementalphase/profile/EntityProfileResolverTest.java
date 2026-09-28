package com.elementalphase.profile;

import com.elementalphase.data.ElementDataParser;
import com.elementalphase.data.model.EntityProfileDefinition;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityProfileResolverTest {
    private static final ResourceLocation ENTITY = id("minecraft:zombie");
    private static final ResourceLocation TAG = id("test:undead");
    private final EntityProfileResolver resolver = new EntityProfileResolver();

    @Test
    void higherPriorityEmptyProfileDoesNotInheritAnyLowerPriorityField() {
        var profiles = profiles(Map.of(
                "low", """
                        {"selector":{"type":"entity_id","id":"minecraft:zombie"},"priority":1,
                         "permanent_elements":[{"element":"test:fire","amount":2}],
                         "resistances":[{"element":"test:fire","value":0.5},{"reaction":"test:future","value":0.25}],
                         "intrinsic_attack":{"element":"test:fire","base_amount":3}}
                        """,
                "high", """
                        {"selector":{"type":"entity_tag","id":"test:undead"},"priority":2}
                        """));

        var result = resolver.resolve(ENTITY, Set.of(TAG), profiles);

        assertTrue(result.permanentElements().isEmpty());
        assertTrue(result.resistances().isEmpty());
        assertTrue(result.reactionResistances().isEmpty());
        assertTrue(result.intrinsicAttack().isEmpty());
        assertEquals(id("test:high"), result.profileId().orElseThrow());
        assertEquals(2, result.priority());
    }

    @Test
    void exactSelectorWinsTieWithoutInheritingTagFields() {
        var result = resolver.resolve(ENTITY, Set.of(TAG), profiles(Map.of(
                "z_tag", """
                        {"selector":{"type":"entity_tag","id":"test:undead"},"priority":5,
                         "permanent_elements":[{"element":"test:fire","amount":2}]}
                        """,
                "a_exact", """
                        {"selector":{"type":"entity_id","id":"minecraft:zombie"},"priority":5}
                        """)));

        assertTrue(result.permanentElements().isEmpty());
        assertEquals(id("test:a_exact"), result.profileId().orElseThrow());
        assertEquals(5, result.priority());
    }

    @Test
    void lastLexicalProfileIdWinsTieRegardlessOfInputOrder() {
        var definitions = profiles(Map.of(
                "a", """
                        {"selector":{"type":"entity_id","id":"minecraft:zombie"},"priority":3,
                         "resistances":[{"element":"test:fire","value":0.5}],
                         "intrinsic_attack":{"element":"test:fire","base_amount":4}}
                        """,
                "z", """
                        {"selector":{"type":"entity_id","id":"minecraft:zombie"},"priority":3,
                         "intrinsic_attack":{"element":"test:fire","base_amount":2}}
                        """));

        var result = resolver.resolve(ENTITY, Set.of(), List.of(definitions.get(1), definitions.get(0)));

        assertTrue(result.resistances().isEmpty());
        assertEquals(2.0D, result.intrinsicAttack().orElseThrow().baseAmount());
        assertEquals(id("test:z"), result.profileId().orElseThrow());
        assertEquals(3, result.priority());
    }

    @Test
    void unmatchedProfilesUseSystemDefaults() {
        var result = resolver.resolve(id("minecraft:cow"), Set.of(), profiles(Map.of("zombie", """
                {"selector":{"type":"entity_id","id":"minecraft:zombie"},
                 "intrinsic_attack":{"element":"test:fire","base_amount":8}}
                """)));

        assertTrue(result.permanentElements().isEmpty());
        assertTrue(result.resistances().isEmpty());
        assertTrue(result.reactionResistances().isEmpty());
        assertTrue(result.intrinsicAttack().isEmpty());
        assertTrue(result.profileId().isEmpty());
        assertEquals(0, result.priority());
    }

    @Test
    void selectedProfileCarriesItsReactionResistancesOnly() {
        var result = resolver.resolve(ENTITY, Set.of(), profiles(Map.of(
                "low", """
                        {"selector":{"type":"entity_id","id":"minecraft:zombie"},"priority":1,
                         "resistances":[{"reaction":"test:old","value":0.5},{"reaction":"test:shared","value":0.75}]}
                        """,
                "high", """
                        {"selector":{"type":"entity_id","id":"minecraft:zombie"},"priority":2,
                         "resistances":[{"reaction":"test:shared","value":-10.25},{"reaction":"kubejs:future","value":1}]}
                        """)));

        assertEquals(Map.of(id("test:shared"), -10.25D, id("kubejs:future"), 1.0D), result.reactionResistances());
    }

    @Test
    void emptyPermanentListAndNullIntrinsicAttackDoNotInheritLowerPriorityFields() {
        var result = resolver.resolve(ENTITY, Set.of(), profiles(Map.of(
                "low", """
                        {"selector":{"type":"entity_id","id":"minecraft:zombie"},"priority":1,
                         "permanent_elements":[{"element":"test:fire","amount":2}],
                         "intrinsic_attack":{"element":"test:fire"}}
                        """,
                "high", """
                        {"selector":{"type":"entity_id","id":"minecraft:zombie"},"priority":2,
                         "permanent_elements":[],"intrinsic_attack":null}
                        """)));

        assertTrue(result.permanentElements().isEmpty());
        assertTrue(result.intrinsicAttack().isEmpty());
    }

    private static List<EntityProfileDefinition> profiles(Map<String, String> json) {
        Map<ResourceLocation, JsonElement> resources = new LinkedHashMap<>();
        resources.put(id("test:elemental_phase/elements/fire.json"), JsonParser.parseString("{}"));
        json.forEach((name, value) -> resources.put(id("test:elemental_phase/entity_profiles/" + name + ".json"),
                JsonParser.parseString(value)));
        return new ElementDataParser().parse(resources).entityProfiles();
    }

    private static ResourceLocation id(String value) {
        return ResourceLocation.parse(value);
    }
}
