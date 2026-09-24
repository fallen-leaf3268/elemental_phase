package com.elementalphase.data;

import com.elementalphase.data.model.AttackSourceDefinition;
import com.elementalphase.data.model.ElementApplicationPolicy;
import com.elementalphase.data.model.ElementAttachmentPolicy;
import com.elementalphase.data.model.ElementDefinition;
import com.elementalphase.data.model.ElementDisplayDefinition;
import com.elementalphase.data.model.EntityProfileDefinition;
import com.elementalphase.data.model.ReactionDefinition;
import com.elementalphase.data.model.ReactionDamageDefinition;
import com.elementalphase.data.model.ReactionAreaDefinition;
import com.elementalphase.data.model.ReactionEffectDefinition;
import com.elementalphase.data.model.ReactionSpec;
import com.elementalphase.reaction.formula.DamageFormulaParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

public final class ElementDataParser {
    private static final double MIN_REACTION_SCALE = 0.1D;
    private static final String ROOT = "elemental_phase/";
    private static final int DEFAULT_COOLDOWN = 2;
    private static final int DEFAULT_DURATION = 100;
    private static final int DEFAULT_RESTORE_DELAY = 200;
    private static final double EPSILON = 0.000001D;
    private static final double MAX_AMOUNT = 1_000_000.0D;

    public ParseReport parseLenient(Map<ResourceLocation, JsonElement> resources) {
        List<FileError> errors = new ArrayList<>();
        Map<ResourceLocation, ElementDefinition> elements = new LinkedHashMap<>();
        Map<ResourceLocation, ReactionSpec> reactions = new LinkedHashMap<>();
        List<EntityProfileDefinition> profiles = new ArrayList<>();
        List<AttackSourceDefinition> attackSources = new ArrayList<>();
        List<Map.Entry<ResourceLocation, JsonElement>> ordered = resources.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
                .toList();

        for (Map.Entry<ResourceLocation, JsonElement> entry : ordered) {
            if (!belongsTo(entry.getKey(), "elements")) {
                continue;
            }
            try {
                ResourceLocation id = definitionId(entry.getKey(), "elements");
                ElementDefinition definition = parseElement(id, object(entry.getValue()));
                if (definition != null) {
                    elements.put(id, definition);
                }
            } catch (RuntimeException exception) {
                errors.add(error(entry.getKey(), exception));
            }
        }

        for (Map.Entry<ResourceLocation, JsonElement> entry : ordered) {
            if (!belongsTo(entry.getKey(), "reactions")) {
                continue;
            }
            try {
                ResourceLocation id = definitionId(entry.getKey(), "reactions");
                ReactionDataParser.ParseResult parsed = new ReactionDataParser().parse(id, object(entry.getValue()), elements);
                if (!parsed.errors().isEmpty()) {
                    throw new DataValidationException(String.join("; ", parsed.errors()));
                }
                if (parsed.definition() != null) {
                    reactions.put(id, parsed.definition());
                }
            } catch (RuntimeException exception) {
                errors.add(error(entry.getKey(), exception));
            }
        }

        for (Map.Entry<ResourceLocation, JsonElement> entry : ordered) {
            if (!belongsTo(entry.getKey(), "entity_profiles")) {
                continue;
            }
            try {
                EntityProfileDefinition definition = parseProfile(definitionId(entry.getKey(), "entity_profiles"),
                        object(entry.getValue()), elements);
                if (definition != null) {
                    profiles.add(definition);
                }
            } catch (RuntimeException exception) {
                errors.add(error(entry.getKey(), exception));
            }
        }

        for (Map.Entry<ResourceLocation, JsonElement> entry : ordered) {
            if (!belongsTo(entry.getKey(), "attack_sources")) {
                continue;
            }
            try {
                AttackSourceDefinition definition = parseAttackSource(definitionId(entry.getKey(), "attack_sources"),
                        object(entry.getValue()), elements);
                if (definition != null) {
                    attackSources.add(definition);
                }
            } catch (RuntimeException exception) {
                errors.add(error(entry.getKey(), exception));
            }
        }

        return new ParseReport(new ElementDataSnapshot(elements, reactions, ReactionIndex.build(reactions), profiles, attackSources), errors);
    }

    public ElementDataSnapshot parse(Map<ResourceLocation, JsonElement> resources) {
        ParseReport report = parseLenient(resources);
        if (!report.errors().isEmpty()) {
            String message = report.errors().stream()
                    .map(error -> error.resource() + ": " + error.message())
                    .reduce((first, second) -> first + "; " + second)
                    .orElse("Invalid elemental data");
            throw new DataValidationException(message);
        }
        return report.snapshot();
    }

    private static ElementDefinition parseElement(ResourceLocation id, JsonObject object) {
        rejectUnknown(object, Set.of("enabled", "application", "attachment", "display"));
        if (!enabled(object)) {
            return null;
        }
        ElementApplicationPolicy application = parseElementApplication(object);
        ElementAttachmentPolicy attachment = parseElementAttachment(object);
        ElementDisplayDefinition display = parseElementDisplay(id, object);
        return new ElementDefinition(id, true, application, attachment, display);
    }

    private static ElementApplicationPolicy parseElementApplication(JsonObject object) {
        if (!object.has("application")) return ElementApplicationPolicy.DEFAULT;
        JsonObject value = requiredObject(object, "application");
        rejectUnknown(value, Set.of("from_attack", "from_reaction"));
        return new ElementApplicationPolicy(optionalBoolean(value, "from_attack", true),
                optionalBoolean(value, "from_reaction", true));
    }

    private static ElementAttachmentPolicy parseElementAttachment(JsonObject object) {
        if (!object.has("attachment")) return ElementAttachmentPolicy.DEFAULT;
        JsonObject value = requiredObject(object, "attachment");
        rejectUnknown(value, Set.of("retain_after_attack", "cooldown_ticks", "duration_ticks", "max_amount"));
        return new ElementAttachmentPolicy(optionalBoolean(value, "retain_after_attack", true),
                optionalInt(value, "cooldown_ticks", DEFAULT_COOLDOWN, 0, Integer.MAX_VALUE),
                optionalInt(value, "duration_ticks", DEFAULT_DURATION, 1, Integer.MAX_VALUE),
                optionalDouble(value, "max_amount", MAX_AMOUNT, MIN_REACTION_SCALE, MAX_AMOUNT));
    }

    private static ElementDisplayDefinition parseElementDisplay(ResourceLocation id, JsonObject object) {
        String defaultTranslation = "element." + id.getNamespace() + "." + id.getPath().replace('/', '.');
        if (!object.has("display")) {
            return new ElementDisplayDefinition(defaultTranslation, 0xFFFFFF, true, 0, Optional.empty());
        }
        JsonObject value = requiredObject(object, "display");
        rejectUnknown(value, Set.of("translation_key", "color", "visible_in_jade", "order", "icon"));
        String translation = optionalString(value, "translation_key", defaultTranslation);
        if (translation.isBlank()) {
            throw new DataValidationException("translation_key must not be blank");
        }
        Optional<ResourceLocation> icon = value.has("icon")
                ? Optional.of(parseId(requiredString(value, "icon"), "icon"))
                : Optional.empty();
        return new ElementDisplayDefinition(translation, optionalColor(value, "color", 0xFFFFFF),
                optionalBoolean(value, "visible_in_jade", true),
                optionalInt(value, "order", 0, Integer.MIN_VALUE, Integer.MAX_VALUE), icon);
    }

    private static ReactionDefinition parseReaction(ResourceLocation id, JsonObject object,
                                                    Map<ResourceLocation, ElementDefinition> elements) {
        rejectUnknown(object, Set.of("enabled", "element_a", "element_b", "ratio_a", "ratio_b", "minimum_scale",
                "damage", "display_color", "effects"));
        if (!enabled(object)) {
            return null;
        }
        ResourceLocation elementA = requiredId(object, "element_a");
        ResourceLocation elementB = requiredId(object, "element_b");
        if (elementA.equals(elementB)) {
            throw new DataValidationException("A reaction cannot use the same element twice");
        }
        requireEnabledElement(elements, elementA);
        requireEnabledElement(elements, elementB);
        double ratioA = optionalDouble(object, "ratio_a", 1.0D, EPSILON, MAX_AMOUNT);
        double ratioB = optionalDouble(object, "ratio_b", 1.0D, EPSILON, MAX_AMOUNT);
        double minimumScale = optionalDouble(object, "minimum_scale", MIN_REACTION_SCALE,
                MIN_REACTION_SCALE, MAX_AMOUNT);
        ReactionDamageDefinition damage = parseReactionDamage(requiredObject(object, "damage"), elementA, elementB,
                elements);
        int displayColor = optionalColor(object, "display_color", 0xFFFFFF);
        return new ReactionDefinition(id, elementA, elementB, ratioA, ratioB, minimumScale, damage, displayColor,
                parseEffects(optionalArray(object, "effects"), elements));
    }

    private static ReactionDamageDefinition parseReactionDamage(JsonObject object, ResourceLocation elementA,
                                                                ResourceLocation elementB,
                                                                Map<ResourceLocation, ElementDefinition> elements) {
        rejectUnknown(object, Set.of("mode", "formula", "damage_type", "area"));
        ReactionDamageDefinition.Mode mode = switch (requiredString(object, "mode")) {
            case "amplify" -> ReactionDamageDefinition.Mode.AMPLIFY;
            case "additional" -> ReactionDamageDefinition.Mode.ADDITIONAL;
            default -> throw new DataValidationException("Unknown reaction damage mode");
        };
        String formulaSource = requiredString(object, "formula");
        Optional<ResourceLocation> damageType = object.has("damage_type")
                ? Optional.of(requiredId(object, "damage_type"))
                : Optional.empty();
        if (mode == ReactionDamageDefinition.Mode.AMPLIFY && damageType.isPresent()) {
            throw new DataValidationException("amplify damage must not specify damage_type");
        }
        if (mode == ReactionDamageDefinition.Mode.ADDITIONAL && damageType.isEmpty()) {
            damageType = Optional.of(ReactionDamageDefinition.DEFAULT_DAMAGE_TYPE);
        }
        Optional<ReactionAreaDefinition> area = object.has("area")
                ? Optional.of(parseReactionArea(requiredObject(object, "area"), mode, elementA, elementB, elements))
                : Optional.empty();
        try {
            return new ReactionDamageDefinition(mode, formulaSource, DamageFormulaParser.parse(formulaSource),
                    damageType, area);
        } catch (DamageFormulaParser.FormulaParseException exception) {
            throw new DataValidationException(exception.getMessage());
        }
    }

    private static ReactionAreaDefinition parseReactionArea(JsonObject object, ReactionDamageDefinition.Mode mode,
                                                             ResourceLocation elementA, ResourceLocation elementB,
                                                             Map<ResourceLocation, ElementDefinition> elements) {
        rejectUnknown(object, Set.of("radius", "spread_element", "attachment_ratio", "attach_attacker"));
        if (mode != ReactionDamageDefinition.Mode.ADDITIONAL) {
            throw new DataValidationException("area is only supported for additional damage");
        }
        double radius = optionalDouble(object, "radius", 2.5D, 0.1D, 64.0D);
        ResourceLocation spreadElement = requiredId(object, "spread_element");
        requireEnabledElement(elements, spreadElement);
        requireAttachableElement(elements, spreadElement);
        if (!spreadElement.equals(elementA) && !spreadElement.equals(elementB)) {
            throw new DataValidationException("spread_element must be one of the reaction elements");
        }
        double attachmentRatio = optionalDouble(object, "attachment_ratio", 0.5D, 0.0D, 1.0D);
        boolean attachAttacker = optionalBoolean(object, "attach_attacker", false);
        return new ReactionAreaDefinition(radius, spreadElement, attachmentRatio, attachAttacker);
    }

    private static EntityProfileDefinition parseProfile(ResourceLocation id, JsonObject object,
                                                        Map<ResourceLocation, ElementDefinition> elements) {
        rejectUnknown(object, Set.of("enabled", "selector", "priority", "permanent_elements", "resistances",
                "intrinsic_attack", "element_strength"));
        if (!enabled(object)) {
            return null;
        }
        JsonObject selectorObject = requiredObject(object, "selector");
        rejectUnknown(selectorObject, Set.of("type", "id"));
        String selectorType = requiredString(selectorObject, "type");
        EntityProfileDefinition.SelectorKind selectorKind = switch (selectorType) {
            case "entity_id" -> EntityProfileDefinition.SelectorKind.ENTITY_ID;
            case "entity_tag" -> EntityProfileDefinition.SelectorKind.ENTITY_TAG;
            default -> throw new DataValidationException("Unknown entity selector type " + selectorType);
        };
        EntityProfileDefinition.Selector selector = new EntityProfileDefinition.Selector(selectorKind,
                requiredId(selectorObject, "id"));
        Map<ResourceLocation, EntityProfileDefinition.PermanentElement> permanent = new HashMap<>();
        Set<ResourceLocation> removed = new HashSet<>();
        if (object.has("permanent_elements")) {
            JsonObject values = requiredObject(object, "permanent_elements");
            for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
                ResourceLocation element = parseId(entry.getKey(), "permanent element key");
                requireEnabledElement(elements, element);
                if (entry.getValue().isJsonNull()) {
                    removed.add(element);
                    continue;
                }
                JsonObject value = object(entry.getValue());
                rejectUnknown(value, Set.of("amount", "restore_delay_ticks"));
                permanent.put(element, new EntityProfileDefinition.PermanentElement(
                        limitedAmount(value, "amount", element, elements),
                        optionalInt(value, "restore_delay_ticks", DEFAULT_RESTORE_DELAY, 1, Integer.MAX_VALUE)));
            }
        }
        Map<ResourceLocation, Double> resistances = new HashMap<>();
        if (object.has("resistances")) {
            JsonObject values = requiredObject(object, "resistances");
            for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
                ResourceLocation element = parseId(entry.getKey(), "resistance key");
                requireEnabledElement(elements, element);
                resistances.put(element, number(entry.getValue(), "resistance", -1.0D, 1.0D));
            }
        }
        Optional<EntityProfileDefinition.IntrinsicAttack> intrinsic = Optional.empty();
        boolean clearIntrinsic = object.has("intrinsic_attack") && object.get("intrinsic_attack") instanceof JsonNull;
        if (object.has("intrinsic_attack") && !clearIntrinsic) {
            JsonObject value = requiredObject(object, "intrinsic_attack");
            rejectUnknown(value, Set.of("element", "base_amount"));
            ResourceLocation element = requiredId(value, "element");
            requireEnabledElement(elements, element);
            requireAttackElement(elements, element);
            intrinsic = Optional.of(new EntityProfileDefinition.IntrinsicAttack(element,
                    limitedOptionalAmount(value, "base_amount", 1.0D, element, elements)));
        }
        OptionalDouble strength = object.has("element_strength")
                ? OptionalDouble.of(number(object.get("element_strength"), "element_strength", 0.0D, 1024.0D))
                : OptionalDouble.empty();
        return new EntityProfileDefinition(id, selector, optionalInt(object, "priority", 0, Integer.MIN_VALUE, Integer.MAX_VALUE),
                permanent, removed, resistances, intrinsic, clearIntrinsic, strength);
    }

    private static AttackSourceDefinition parseAttackSource(ResourceLocation id, JsonObject object,
                                                            Map<ResourceLocation, ElementDefinition> elements) {
        rejectUnknown(object, Set.of("enabled", "kind", "selector", "element", "base_amount", "priority", "application"));
        if (!enabled(object)) {
            return null;
        }
        AttackSourceDefinition.SourceKind kind = switch (requiredString(object, "kind")) {
            case "enchantment_id" -> AttackSourceDefinition.SourceKind.ENCHANTMENT_ID;
            case "enchantment_tag" -> AttackSourceDefinition.SourceKind.ENCHANTMENT_TAG;
            case "damage_type_id" -> AttackSourceDefinition.SourceKind.DAMAGE_TYPE_ID;
            case "damage_type_tag" -> AttackSourceDefinition.SourceKind.DAMAGE_TYPE_TAG;
            default -> throw new DataValidationException("Unknown attack source kind");
        };
        String selector = requiredString(object, "selector");
        if (selector.startsWith("#")) {
            throw new DataValidationException("selector must not start with #");
        }
        parseId(selector, "selector");
        ResourceLocation element = requiredId(object, "element");
        requireEnabledElement(elements, element);
        requireAttackElement(elements, element);
        Optional<AttackSourceDefinition.Application> application = Optional.empty();
        if (object.has("application")) {
            JsonObject value = object(object.get("application"));
            rejectUnknown(value, Set.of("cooldown_group", "cooldown_ticks"));
            if (!value.has("cooldown_group") || !value.has("cooldown_ticks")) {
                throw new DataValidationException("application requires cooldown_group and cooldown_ticks");
            }
            application = Optional.of(new AttackSourceDefinition.Application(requiredId(value, "cooldown_group"),
                    requiredInt(value, "cooldown_ticks", 0, Integer.MAX_VALUE)));
        }
        return new AttackSourceDefinition(id, kind, selector, element,
                limitedOptionalAmount(object, "base_amount", 1.0D, element, elements),
                optionalInt(object, "priority", 0, Integer.MIN_VALUE, Integer.MAX_VALUE), application);
    }

    private static List<ReactionEffectDefinition> parseEffects(JsonArray values,
                                                                Map<ResourceLocation, ElementDefinition> elements) {
        List<ReactionEffectDefinition> effects = new ArrayList<>();
        for (JsonElement value : values) {
            JsonObject object = object(value);
            String typeName = requiredString(object, "type");
            ReactionEffectDefinition.EffectType type = switch (typeName) {
                case "mob_effect" -> ReactionEffectDefinition.EffectType.MOB_EFFECT;
                case "ignite" -> ReactionEffectDefinition.EffectType.IGNITE;
                case "freeze" -> ReactionEffectDefinition.EffectType.FREEZE;
                case "knockback" -> ReactionEffectDefinition.EffectType.KNOCKBACK;
                case "element_change" -> ReactionEffectDefinition.EffectType.ELEMENT_CHANGE;
                default -> throw new DataValidationException("Unknown effect type " + typeName);
            };
            ReactionEffectDefinition.EffectTarget target = switch (optionalString(object, "target", "victim")) {
                case "victim" -> ReactionEffectDefinition.EffectTarget.TARGET;
                case "self" -> ReactionEffectDefinition.EffectTarget.ATTACKER;
                default -> throw new DataValidationException("Unknown effect target");
            };
            effects.add(switch (type) {
                case MOB_EFFECT -> {
                    rejectUnknown(object, Set.of("type", "target", "effect", "duration_ticks", "amplifier"));
                    yield new ReactionEffectDefinition(type, target, requiredId(object, "effect"),
                            requiredInt(object, "duration_ticks", 1, Integer.MAX_VALUE),
                            optionalInt(object, "amplifier", 0, 0, 255), 0.0D, null, null, 0.0D);
                }
                case IGNITE, FREEZE -> {
                    rejectUnknown(object, Set.of("type", "target", "ticks"));
                    yield new ReactionEffectDefinition(type, target, null,
                            requiredInt(object, "ticks", 1, Integer.MAX_VALUE), 0, 0.0D, null, null, 0.0D);
                }
                case KNOCKBACK -> {
                    if (target != ReactionEffectDefinition.EffectTarget.TARGET) {
                        throw new DataValidationException("knockback target must be victim");
                    }
                    rejectUnknown(object, Set.of("type", "target", "strength"));
                    yield new ReactionEffectDefinition(type, target, null, 0, 0,
                            requiredDouble(object, "strength", 0.0D, 16.0D), null, null, 0.0D);
                }
                case ELEMENT_CHANGE -> parseElementChange(object, type, target, elements);
            });
        }
        return List.copyOf(effects);
    }

    private static ReactionEffectDefinition parseElementChange(JsonObject object,
                                                                ReactionEffectDefinition.EffectType type,
                                                                ReactionEffectDefinition.EffectTarget target,
                                                                Map<ResourceLocation, ElementDefinition> elements) {
        String operationName = requiredString(object, "operation");
        ReactionEffectDefinition.ElementOperation operation = switch (operationName) {
            case "add" -> ReactionEffectDefinition.ElementOperation.ADD;
            case "set" -> ReactionEffectDefinition.ElementOperation.SET;
            case "remove" -> ReactionEffectDefinition.ElementOperation.REMOVE;
            default -> throw new DataValidationException("Unknown element operation " + operationName);
        };
        Set<String> fields = operation == ReactionEffectDefinition.ElementOperation.REMOVE
                ? Set.of("type", "target", "operation", "element")
                : Set.of("type", "target", "operation", "element", "amount");
        rejectUnknown(object, fields);
        ResourceLocation element = requiredId(object, "element");
        requireEnabledElement(elements, element);
        if (operation != ReactionEffectDefinition.ElementOperation.REMOVE) {
            requireAttachableElement(elements, element);
        }
        return new ReactionEffectDefinition(type, target, null, 0, 0, 0.0D,
                element, operation,
                operation == ReactionEffectDefinition.ElementOperation.REMOVE ? 0.0D
                        : limitedAmount(object, "amount", element, elements));
    }

    private static boolean belongsTo(ResourceLocation resource, String category) {
        return resource.getPath().startsWith(ROOT + category + "/") && resource.getPath().endsWith(".json");
    }

    private static ResourceLocation definitionId(ResourceLocation resource, String category) {
        String prefix = ROOT + category + "/";
        String path = resource.getPath();
        if (!belongsTo(resource, category)) {
            throw new DataValidationException("Invalid resource path");
        }
        String idPath = path.substring(prefix.length(), path.length() - ".json".length());
        if (idPath.isBlank()) {
            throw new DataValidationException("Empty definition path");
        }
        return ResourceLocation.fromNamespaceAndPath(resource.getNamespace(), idPath);
    }

    private static void requireEnabledElement(Map<ResourceLocation, ElementDefinition> elements, ResourceLocation id) {
        if (!elements.containsKey(id)) {
            throw new DataValidationException("Unknown or disabled element " + id);
        }
    }

    private static void requireAttachableElement(Map<ResourceLocation, ElementDefinition> elements, ResourceLocation id) {
        ElementDefinition definition = elements.get(id);
        if (definition == null) {
            throw new DataValidationException("Unknown or disabled element " + id);
        }
        if (!definition.application().fromReaction()) {
            throw new DataValidationException("Element cannot be created by a reaction " + id);
        }
    }

    private static void requireAttackElement(Map<ResourceLocation, ElementDefinition> elements, ResourceLocation id) {
        ElementDefinition definition = elements.get(id);
        if (definition == null || !definition.application().fromAttack()) {
            throw new DataValidationException("Element cannot be used as an attack source: " + id);
        }
    }

    private static double limitedAmount(JsonObject object, String field, ResourceLocation element,
                                        Map<ResourceLocation, ElementDefinition> elements) {
        double amount = requiredDouble(object, field, EPSILON, MAX_AMOUNT);
        return validateElementLimit(field, amount, element, elements);
    }

    private static double limitedOptionalAmount(JsonObject object, String field, double fallback,
                                                ResourceLocation element,
                                                Map<ResourceLocation, ElementDefinition> elements) {
        double amount = optionalDouble(object, field, fallback, EPSILON, MAX_AMOUNT);
        return validateElementLimit(field, amount, element, elements);
    }

    private static double validateElementLimit(String field, double amount, ResourceLocation element,
                                               Map<ResourceLocation, ElementDefinition> elements) {
        ElementDefinition definition = elements.get(element);
        if (definition != null && amount > definition.attachment().maxAmount()) {
            throw new DataValidationException(field + " exceeds max_amount for element " + element);
        }
        return amount;
    }

    private static boolean enabled(JsonObject object) {
        return optionalBoolean(object, "enabled", true);
    }

    private static boolean optionalBoolean(JsonObject object, String field, boolean fallback) {
        if (!object.has(field)) {
            return fallback;
        }
        JsonElement value = object.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new DataValidationException(field + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static JsonObject object(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            throw new DataValidationException("Expected JSON object");
        }
        return value.getAsJsonObject();
    }

    private static JsonObject requiredObject(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            throw new DataValidationException("Missing object field " + field);
        }
        return object(object.get(field));
    }

    private static JsonArray optionalArray(JsonObject object, String field) {
        if (!object.has(field)) {
            return new JsonArray();
        }
        JsonElement value = object.get(field);
        if (!value.isJsonArray()) {
            throw new DataValidationException(field + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static JsonArray requiredArray(JsonObject object, String field) {
        if (!object.has(field)) {
            throw new DataValidationException("Missing array field " + field);
        }
        JsonElement value = object.get(field);
        if (!value.isJsonArray()) {
            throw new DataValidationException(field + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static String requiredString(JsonObject object, String field) {
        if (!object.has(field)) {
            throw new DataValidationException("Missing string field " + field);
        }
        return string(object.get(field), field);
    }

    private static String optionalString(JsonObject object, String field, String fallback) {
        return object.has(field) ? string(object.get(field), field) : fallback;
    }

    private static int optionalColor(JsonObject object, String field, int fallback) {
        if (!object.has(field)) {
            return fallback;
        }
        String value = string(object.get(field), field);
        if (!value.matches("#[0-9A-Fa-f]{6}")) {
            throw new DataValidationException(field + " must use #RRGGBB");
        }
        return Integer.parseInt(value.substring(1), 16);
    }

    private static String string(JsonElement value, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new DataValidationException(field + " must be a string");
        }
        return value.getAsString();
    }

    private static ResourceLocation requiredId(JsonObject object, String field) {
        return parseId(requiredString(object, field), field);
    }

    private static ResourceLocation parseId(String value, String field) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) {
            throw new DataValidationException(field + " must be a valid resource location");
        }
        return id;
    }

    private static int requiredInt(JsonObject object, String field, int min, int max) {
        if (!object.has(field)) {
            throw new DataValidationException("Missing integer field " + field);
        }
        return integer(object.get(field), field, min, max);
    }

    private static int optionalInt(JsonObject object, String field, int fallback, int min, int max) {
        return object.has(field) ? integer(object.get(field), field, min, max) : fallback;
    }

    private static int integer(JsonElement value, String field, int min, int max) {
        double number = number(value, field, min, max);
        if (number != Math.rint(number)) {
            throw new DataValidationException(field + " must be an integer");
        }
        return (int) number;
    }

    private static double requiredDouble(JsonObject object, String field, double min, double max) {
        if (!object.has(field)) {
            throw new DataValidationException("Missing number field " + field);
        }
        return number(object.get(field), field, min, max);
    }

    private static double optionalDouble(JsonObject object, String field, double fallback, double min, double max) {
        return object.has(field) ? number(object.get(field), field, min, max) : fallback;
    }

    private static double number(JsonElement value, String field, double min, double max) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new DataValidationException(field + " must be a number");
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || number < min || number > max) {
            throw new DataValidationException(field + " is outside its allowed range");
        }
        return number;
    }

    private static void rejectUnknown(JsonObject object, Set<String> fields) {
        for (String field : object.keySet()) {
            if (!fields.contains(field)) {
                throw new DataValidationException("Unknown field " + field);
            }
        }
    }

    private static FileError error(ResourceLocation resource, RuntimeException exception) {
        String message = exception.getMessage();
        return new FileError(resource, message == null || message.isBlank() ? exception.getClass().getSimpleName() : message);
    }

    public record FileError(ResourceLocation resource, String message) {
    }

    public record ParseReport(ElementDataSnapshot snapshot, List<FileError> errors) {
        public ParseReport {
            errors = List.copyOf(errors);
        }
    }

    public static final class DataValidationException extends RuntimeException {
        public DataValidationException(String message) {
            super(message);
        }
    }
}
