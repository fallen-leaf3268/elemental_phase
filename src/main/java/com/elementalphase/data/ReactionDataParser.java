package com.elementalphase.data;

import com.elementalphase.data.model.ElementDefinition;
import com.elementalphase.data.model.ReactionAction;
import com.elementalphase.data.model.ReactionCondition;
import com.elementalphase.data.model.ReactionConsumption;
import com.elementalphase.data.model.ReactionDirection;
import com.elementalphase.data.model.ReactionDisplay;
import com.elementalphase.data.model.ReactionSpec;
import com.elementalphase.reaction.formula.DamageFormulaParser;
import com.elementalphase.reaction.formula.ReactionFormula;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ReactionDataParser {
    public static final double MINIMUM_SCALE = 0.1D;
    public static final int MAX_DIRECTIONS = 64;
    public static final int MAX_CONDITIONS = 32;
    public static final int MAX_ACTIONS = 32;
    private static final double MAX_NUMBER = 1_000_000.0D;
    private static final ReactionFormula ALWAYS = DamageFormulaParser.parseReaction("1");
    private static final Set<String> DOT_VARIABLES = Set.of("original_damage", "current_damage", "scale",
            "trigger_amount", "aura_amount", "consumed_trigger", "consumed_aura", "remaining_trigger",
            "remaining_aura", "attacker_level", "element_strength", "target_health", "target_max_health",
            "target_health_ratio", "target_resistance");
    private static final Set<String> FUNCTIONS = Set.of("min", "max", "clamp", "abs", "floor", "ceil", "round");
    private static final Set<String> BASIC_FUNCTIONS = Set.of("min", "max", "clamp");

    public ParseResult parse(ResourceLocation id, JsonObject json,
                             Map<ResourceLocation, ElementDefinition> elements) {
        try {
            return new ParseResult(parseChecked(id, json, elements), List.of());
        } catch (InvalidData exception) {
            return new ParseResult(null, List.of(exception.getMessage()));
        }
    }

    private ReactionSpec parseChecked(ResourceLocation id, JsonObject json,
                                      Map<ResourceLocation, ElementDefinition> elements) {
        rejectUnknown(json, Set.of("priority", "minimum_scale", "conditions", "display", "reactions"), "$");
        int priority = integer(json, "priority", 0, Integer.MIN_VALUE, Integer.MAX_VALUE, "$", false);
        double minimum = number(json, "minimum_scale", MINIMUM_SCALE, MAX_NUMBER, "$", false);
        JsonArray conditionValues = array(json, "conditions", "$", 0, MAX_CONDITIONS, false);
        List<ReactionCondition> conditions = new ArrayList<>();
        for (int index = 0; index < conditionValues.size(); index++) {
            String path = "$.conditions[" + index + "]";
            conditions.add(condition(object(conditionValues.get(index), path), path));
        }
        JsonObject sharedDisplay = json.has("display") ? requiredObject(json, "display", "$") : new JsonObject();
        rejectUnknown(sharedDisplay, Set.of("translation_key", "show_reaction"), "$.display");
        String translationKey = string(sharedDisplay, "translation_key", "$.display", false,
                ReactionSpec.defaultTranslationKey(id));
        boolean showReaction = bool(sharedDisplay, "show_reaction", true, "$.display");
        JsonArray entries = requiredArray(json, "reactions", "$", 1, MAX_DIRECTIONS);
        List<ReactionDirection> parsed = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            String path = "$.reactions[" + index + "]";
            JsonObject entry = object(entries.get(index), path);
            rejectUnknown(entry, Set.of("bidirectional", "unidirectional", "color", "damage", "area",
                    "mob_effects", "special", "elements"), path);
            if (entry.has("bidirectional") == entry.has("unidirectional")) {
                throw invalid(path, "必须且只能指定 bidirectional 或 unidirectional");
            }
            ReactionDisplay display = new ReactionDisplay(displayColor(entry, new ReactionDisplay.Color(0xFFFFFF,
                    ReactionDisplay.Reference.FIXED), path), showReaction);
            List<ReactionAction> actions = groupedActions(entry, path, elements);
            if (entry.has("bidirectional")) {
                String groupPath = path + ".bidirectional";
                JsonObject group = requiredObject(entry, "bidirectional", path);
                rejectUnknown(group, Set.of("elements"), groupPath);
                JsonArray positions = requiredArray(group, "elements", groupPath, 2, 2);
                Position first = position(object(positions.get(0), groupPath + ".elements[0]"),
                        groupPath + ".elements[0]", elements);
                Position second = position(object(positions.get(1), groupPath + ".elements[1]"),
                        groupPath + ".elements[1]", elements);
                expand(parsed, first, second, minimum, conditions, display, actions, groupPath);
                expand(parsed, second, first, minimum, conditions, display, actions, groupPath);
            } else {
                String groupPath = path + ".unidirectional";
                JsonObject group = requiredObject(entry, "unidirectional", path);
                rejectUnknown(group, Set.of("trigger", "aura"), groupPath);
                Position trigger = position(requiredObject(group, "trigger", groupPath), groupPath + ".trigger", elements);
                Position aura = position(requiredObject(group, "aura", groupPath), groupPath + ".aura", elements);
                expand(parsed, trigger, aura, minimum, conditions, display, actions, groupPath);
            }
        }
        LinkedHashSet<ResourceLocation> participants = new LinkedHashSet<>();
        parsed.forEach(direction -> { participants.add(direction.trigger()); participants.add(direction.aura()); });
        return new ReactionSpec(id, priority, participants, parsed, translationKey);
    }

    private Position position(JsonObject json, String path, Map<ResourceLocation, ElementDefinition> elements) {
        rejectUnknown(json, Set.of("element", "ratio"), path);
        JsonElement value = json.get("element");
        if (value == null) throw invalid(path + ".element", "缺少字段");
        List<JsonElement> values = value.isJsonArray() ? value.getAsJsonArray().asList() : List.of(value);
        if (values.isEmpty()) throw invalid(path + ".element", "数组不能为空");
        LinkedHashSet<ResourceLocation> result = new LinkedHashSet<>();
        for (int index = 0; index < values.size(); index++) {
            ResourceLocation element = id(values.get(index), path + ".element[" + index + "]");
            requireElement(element, elements, path + ".element[" + index + "]");
            if (!result.add(element)) throw invalid(path + ".element", "重复元素 " + element);
        }
        return new Position(result, number(json, "ratio", 0, MAX_NUMBER, path, true));
    }

    private void expand(List<ReactionDirection> parsed, Position trigger, Position aura, double minimum,
                        List<ReactionCondition> conditions, ReactionDisplay display, List<ReactionAction> actions,
                        String path) {
        if (trigger.elements().stream().anyMatch(aura.elements()::contains)) throw invalid(path, "两个匹配位置不能重叠");
        for (ResourceLocation triggerId : trigger.elements()) {
            for (ResourceLocation auraId : aura.elements()) {
                if (parsed.size() >= MAX_DIRECTIONS) throw invalid(path, "展开方向数量超过 " + MAX_DIRECTIONS);
                if (parsed.stream().anyMatch(direction -> direction.trigger().equals(triggerId) && direction.aura().equals(auraId))) {
                    throw invalid(path, "重复展开方向 " + triggerId + " -> " + auraId);
                }
                parsed.add(new ReactionDirection(triggerId, auraId, minimum,
                        new ReactionConsumption(trigger.ratio(), aura.ratio()), conditions, display, actions));
            }
        }
    }

    private List<ReactionAction> groupedActions(JsonObject json, String path,
                                                Map<ResourceLocation, ElementDefinition> elements) {
        List<ReactionAction> actions = new ArrayList<>();
        for (String group : List.of("damage", "area", "mob_effects", "special", "elements")) {
            JsonArray values = array(json, group, path, 0, MAX_ACTIONS, false);
            Set<String> allowedTypes = switch (group) {
                case "damage" -> Set.of("main_damage_bonus", "additional_damage", "schedule_damage");
                case "elements" -> Set.of("attach_element", "modify_element");
                default -> Set.of();
            };
            for (int index = 0; index < values.size(); index++) {
                String actionPath = path + "." + group + "[" + index + "]";
                if (actions.size() >= MAX_ACTIONS) throw invalid(actionPath, "组件总数超过 " + MAX_ACTIONS);
                JsonObject component = object(values.get(index), actionPath);
                if (allowedTypes.isEmpty()) {
                    if (component.has("type")) throw invalid(actionPath + ".type", "类型由组件分组确定，不接受 type");
                    component = component.deepCopy();
                    component.addProperty("type", group.equals("mob_effects") ? "mob_effect" : group);
                } else {
                    String type = string(component, "type", actionPath, true, null);
                    if (!allowedTypes.contains(type)) throw invalid(actionPath + ".type", "该分组不接受类型 " + type);
                }
                actions.add(action(component, actionPath, elements));
            }
        }
        return List.copyOf(actions);
    }

    private ReactionDisplay.Color displayColor(JsonObject json, ReactionDisplay.Color fallback, String path) {
        if (!json.has("color")) return fallback;
        String value = string(json, "color", path, true, null);
        if (value.equals("$trigger")) return new ReactionDisplay.Color(0, ReactionDisplay.Reference.TRIGGER);
        if (value.equals("$aura")) return new ReactionDisplay.Color(0, ReactionDisplay.Reference.AURA);
        return new ReactionDisplay.Color(color(json, "color", 0xFFFFFF, path), ReactionDisplay.Reference.FIXED);
    }

    private ReactionCondition condition(JsonObject json, String path) {
        String type = string(json, "type", path, true, null);
        boolean inverted = bool(json, "inverted", false, path);
        return switch (type) {
            case "attacker_present" -> {
                rejectUnknown(json, Set.of("type", "value"), path);
                yield new ReactionCondition.AttackerPresent(bool(json, "value", true, path));
            }
            case "attacker_entity" -> entityCondition(json, path, inverted, true);
            case "target_entity" -> entityCondition(json, path, inverted, false);
            case "damage_type" -> {
                rejectUnknown(json, Set.of("type", "inverted", "damage_type", "tag"), path);
                Selector selector = selector(json, "damage_type", path);
                yield new ReactionCondition.DamageType(selector.value(), selector.tag(), inverted);
            }
            case "minimum_damage" -> {
                rejectUnknown(json, Set.of("type", "inverted", "value"), path);
                yield new ReactionCondition.MinimumDamage(number(json, "value", 0.0D, MAX_NUMBER, path, true), inverted);
            }
            case "target_state" -> {
                rejectUnknown(json, Set.of("type", "state", "value"), path);
                var state = switch (string(json, "state", path, true, null)) {
                    case "on_fire" -> ReactionCondition.TargetState.State.ON_FIRE;
                    case "in_water" -> ReactionCondition.TargetState.State.IN_WATER;
                    case "frozen" -> ReactionCondition.TargetState.State.FROZEN;
                    default -> throw invalid(path + ".state", "未知特殊状态");
                };
                yield new ReactionCondition.TargetState(state, bool(json, "value", true, path));
            }
            default -> throw invalid(path + ".type", "未知条件类型 " + type);
        };
    }

    private ReactionCondition entityCondition(JsonObject json, String path, boolean inverted, boolean attacker) {
        rejectUnknown(json, Set.of("type", "inverted", "entity", "tag"), path);
        Selector selector = selector(json, "entity", path);
        return attacker
                ? new ReactionCondition.AttackerEntity(selector.value(), selector.tag(), inverted)
                : new ReactionCondition.TargetEntity(selector.value(), selector.tag(), inverted);
    }

    private ReactionAction action(JsonObject json, String path, Map<ResourceLocation, ElementDefinition> elements) {
        String type = string(json, "type", path, true, null);
        ReactionFormula when = formula(json, "when", "1", path);
        return switch (type) {
            case "main_damage_bonus" -> {
                rejectUnknown(json, Set.of("type", "when", "formula"), path);
                yield new ReactionAction.MainDamageBonus(formulaValue(json, "formula", path), when);
            }
            case "additional_damage" -> {
                rejectUnknown(json, damageFields(Set.of("type", "when", "formula")), path);
                yield new ReactionAction.AdditionalDamage(formulaValue(json, "formula", path), damageSettings(json, path, elements), when);
            }
            case "area" -> {
                rejectUnknown(json, Set.of("type", "when", "radius", "damage", "attachment", "include_attacker", "max_targets"), path);
                if (!json.has("damage") && !json.has("attachment")) throw invalid(path, "damage 和 attachment 至少指定一项");
                Optional<ReactionAction.AreaDamageValue> damage = Optional.empty();
                if (json.has("damage")) {
                    JsonObject part = requiredObject(json, "damage", path);
                    rejectUnknown(part, damageFields(Set.of("formula", "include_target")), path + ".damage");
                    damage = Optional.of(new ReactionAction.AreaDamageValue(formulaValue(part, "formula", path + ".damage"),
                            damageSettings(part, path + ".damage", elements), bool(part, "include_target", false, path + ".damage")));
                }
                Optional<ReactionAction.AreaAttachment> attachment = Optional.empty();
                if (json.has("attachment")) {
                    JsonObject part = requiredObject(json, "attachment", path);
                    rejectUnknown(part, Set.of("element", "amount"), path + ".attachment");
                    attachment = Optional.of(new ReactionAction.AreaAttachment(elementReference(part, "element", path + ".attachment", elements),
                            restrictedFormulaValue(part, "amount", path + ".attachment",
                                    Set.of("scale", "consumed_trigger", "consumed_aura", "distance", "radius"), BASIC_FUNCTIONS, false)));
                }
                yield new ReactionAction.Area(radius(json, path), damage, attachment,
                        bool(json, "include_attacker", false, path), json.has("max_targets")
                        ? Optional.of(integer(json, "max_targets", 0, 1, Integer.MAX_VALUE, path, true)) : Optional.empty(), when);
            }
            case "mob_effect" -> {
                rejectUnknown(json, Set.of("type", "when", "effect", "duration_ticks", "level"), path);
                yield new ReactionAction.MobEffect(requiredId(json, "effect", path),
                        integer(json, "duration_ticks", 0, 1, Integer.MAX_VALUE, path, true),
                        integer(json, "level", 0, 0, Integer.MAX_VALUE, path, false), when);
            }
            case "special" -> {
                rejectUnknown(json, Set.of("type", "when", "entries"), path);
                JsonArray entries = requiredArray(json, "entries", path, 1, MAX_ACTIONS);
                List<ReactionAction.SpecialEntry> parsed = new ArrayList<>();
                for (int index = 0; index < entries.size(); index++) {
                    String entryPath = path + ".entries[" + index + "]";
                    JsonObject entry = object(entries.get(index), entryPath);
                    rejectUnknown(entry, Set.of("type", "duration_ticks"), entryPath);
                    var kind = switch (string(entry, "type", entryPath, true, null)) {
                        case "ignite" -> ReactionAction.SpecialEntry.Kind.IGNITE;
                        case "freeze" -> ReactionAction.SpecialEntry.Kind.FREEZE;
                        default -> throw invalid(entryPath + ".type", "未知特殊状态");
                    };
                    parsed.add(new ReactionAction.SpecialEntry(kind,
                            integer(entry, "duration_ticks", 0, 1, Integer.MAX_VALUE, entryPath, true)));
                }
                yield new ReactionAction.Special(parsed, when);
            }
            case "schedule_damage" -> {
                rejectUnknown(json, Set.of("type", "when", "id", "duration_ticks",
                        "interval_ticks", "damage"), path);
                yield new ReactionAction.ScheduleDamage(requiredId(json, "id", path),
                        integer(json, "duration_ticks", 0, 0, Integer.MAX_VALUE, path, true),
                        integer(json, "interval_ticks", 0, 1, Integer.MAX_VALUE, path, true),
                        stateDamage(requiredObject(json, "damage", path), path + ".damage", elements), when);
            }
            case "modify_element" -> {
                rejectUnknown(json, Set.of("type", "when", "target", "element", "operation", "amount"), path);
                ReactionAction.ElementOperation operation = switch (string(json, "operation", path, true, null)) {
                    case "add" -> ReactionAction.ElementOperation.ADD;
                    case "set" -> ReactionAction.ElementOperation.SET;
                    case "remove" -> ReactionAction.ElementOperation.REMOVE;
                    case "clear" -> ReactionAction.ElementOperation.CLEAR;
                    default -> throw invalid(path + ".operation", "未知元素操作");
                };
                Optional<ReactionAction.Formula> amount = operation == ReactionAction.ElementOperation.CLEAR
                        ? Optional.empty() : Optional.of(formulaValue(json, "amount", path));
                if (operation == ReactionAction.ElementOperation.CLEAR && json.has("amount")) {
                    throw invalid(path + ".amount", "clear 不接受 amount");
                }
                yield new ReactionAction.ModifyElement(target(json, path), elementReference(json, "element", path, elements),
                        operation, amount, when);
            }
            case "attach_element" -> {
                rejectUnknown(json, Set.of("type", "when", "element", "amount"), path);
                ResourceLocation element = requiredId(json, "element", path);
                requireElement(element, elements, path + ".element");
                yield new ReactionAction.AttachElement(element, formulaValue(json, "amount", path), when);
            }
            default -> throw invalid(path + ".type", "未知动作类型 " + type);
        };
    }

    private ReactionAction.DamageSettings damageSettings(JsonObject json, String path,
                                                          Map<ResourceLocation, ElementDefinition> elements) {
        return new ReactionAction.DamageSettings(
                json.has("damage_type") ? requiredId(json, "damage_type", path) : ReactionAction.DEFAULT_DAMAGE_TYPE,
                json.has("resistance_element") ? Optional.of(elementReference(json, "resistance_element", path, elements)) : Optional.empty());
    }

    private ReactionAction.StateDamage stateDamage(JsonObject json, String path,
                                                    Map<ResourceLocation, ElementDefinition> elements) {
        rejectUnknown(json, Set.of("formula", "damage_type", "resistance_element"), path);
        return new ReactionAction.StateDamage(
                restrictedFormulaValue(json, "formula", path, DOT_VARIABLES, FUNCTIONS, true),
                json.has("damage_type") ? requiredId(json, "damage_type", path) : ReactionAction.DEFAULT_DOT_DAMAGE_TYPE,
                json.has("resistance_element")
                        ? Optional.of(elementReference(json, "resistance_element", path, elements))
                        : Optional.empty());
    }

    private static Set<String> damageFields(Set<String> base) {
        Set<String> fields = new HashSet<>(base);
        fields.addAll(Set.of("damage_type", "resistance_element"));
        return fields;
    }

    private ReactionAction.ElementReference elementReference(JsonObject json, String field, String path,
                                                              Map<ResourceLocation, ElementDefinition> elements) {
        String value = string(json, field, path, true, null);
        if (value.equals("$trigger")) return ReactionAction.ElementReference.trigger();
        if (value.equals("$aura")) return ReactionAction.ElementReference.aura();
        ResourceLocation id = parseId(value, path + "." + field);
        if (!elements.isEmpty()) requireElement(id, elements, path + "." + field);
        return ReactionAction.ElementReference.fixed(id);
    }

    private ReactionAction.Target target(JsonObject json, String path) {
        return switch (string(json, "target", path, false, "target")) {
            case "target" -> ReactionAction.Target.TARGET;
            case "attacker" -> ReactionAction.Target.ATTACKER;
            default -> throw invalid(path + ".target", "未知目标");
        };
    }

    private ReactionAction.Formula radius(JsonObject json, String path) {
        JsonElement value = json.get("radius");
        if (value == null) throw invalid(path + ".radius", "缺少字段");
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            double number = value.getAsDouble();
            if (!Double.isFinite(number)) throw invalid(path + ".radius", "必须为有限数字");
            return new ReactionAction.Formula(value.getAsString(), ignored -> number);
        }
        return restrictedFormulaValue(json, "radius", path, Set.of("scale"), BASIC_FUNCTIONS, false);
    }

    private ReactionAction.Formula formulaValue(JsonObject json, String field, String path) {
        return formulaValue(json, field, null, path);
    }

    private ReactionAction.Formula restrictedFormulaValue(JsonObject json, String field, String path,
                                                           Set<String> variables, Set<String> functions, boolean comparisons) {
        String source = string(json, field, path, true, null);
        try {
            return new ReactionAction.Formula(source, DamageFormulaParser.parseReaction(source, variables, functions, comparisons));
        } catch (DamageFormulaParser.FormulaParseException exception) {
            throw invalid(path + "." + field, exception.getMessage());
        }
    }

    private ReactionAction.Formula formulaValue(JsonObject json, String field, String fallback, String path) {
        String source = string(json, field, path, fallback == null, fallback);
        return new ReactionAction.Formula(source, compile(source, path + "." + field));
    }

    private ReactionFormula formula(JsonObject json, String field, String fallback, String path) {
        if (!json.has(field)) return ALWAYS;
        return compile(string(json, field, path, true, fallback), path + "." + field);
    }

    private ReactionFormula compile(String source, String path) {
        try {
            return DamageFormulaParser.parseReaction(source);
        } catch (DamageFormulaParser.FormulaParseException exception) {
            throw invalid(path, exception.getMessage());
        }
    }

    private Selector selector(JsonObject json, String valueField, String path) {
        boolean hasValue = json.has(valueField);
        boolean hasTag = json.has("tag");
        if (hasValue == hasTag) throw invalid(path, "必须且只能指定 " + valueField + " 或 tag");
        return new Selector(hasValue ? Optional.of(requiredId(json, valueField, path)) : Optional.empty(),
                hasTag ? Optional.of(requiredId(json, "tag", path)) : Optional.empty());
    }

    private static void requireElement(ResourceLocation id, Map<ResourceLocation, ElementDefinition> elements, String path) {
        ElementDefinition element = elements.get(id);
        if (element == null) throw invalid(path, "引用不存在的元素 " + id);
    }

    private static JsonObject object(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) throw invalid(path, "必须是对象");
        return value.getAsJsonObject();
    }

    private static JsonObject requiredObject(JsonObject json, String field, String path) {
        if (!json.has(field)) throw invalid(path + "." + field, "缺少字段");
        return object(json.get(field), path + "." + field);
    }

    private static JsonArray requiredArray(JsonObject json, String field, String path, int min, int max) {
        return array(json, field, path, min, max, true);
    }

    private static JsonArray array(JsonObject json, String field, String path, int min, int max, boolean required) {
        if (!json.has(field)) {
            if (required) throw invalid(path + "." + field, "缺少字段");
            return new JsonArray();
        }
        JsonElement value = json.get(field);
        if (!value.isJsonArray()) throw invalid(path + "." + field, "必须是数组");
        JsonArray result = value.getAsJsonArray();
        if (result.size() < min || result.size() > max) throw invalid(path + "." + field, "数量必须在 " + min + ".." + max);
        return result;
    }

    private static ResourceLocation requiredId(JsonObject json, String field, String path) {
        return parseId(string(json, field, path, true, null), path + "." + field);
    }

    private static ResourceLocation id(JsonElement value, String path) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid(path, "必须是资源地址字符串");
        return parseId(value.getAsString(), path);
    }

    private static ResourceLocation parseId(String value, String path) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) throw invalid(path, "无效资源地址 " + value);
        return id;
    }

    private static String string(JsonObject json, String field, String path, boolean required, String fallback) {
        if (!json.has(field)) {
            if (required) throw invalid(path + "." + field, "缺少字段");
            return fallback;
        }
        JsonElement value = json.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid(path + "." + field, "必须是字符串");
        String result = value.getAsString();
        if (result.isBlank()) throw invalid(path + "." + field, "不能为空");
        return result;
    }

    private static boolean bool(JsonObject json, String field, boolean fallback, String path) {
        if (!json.has(field)) return fallback;
        JsonElement value = json.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw invalid(path + "." + field, "必须是布尔值");
        return value.getAsBoolean();
    }

    private static int color(JsonObject json, String field, int fallback, String path) {
        if (!json.has(field)) return fallback;
        String value = string(json, field, path, true, null);
        if (!value.matches("#[0-9A-Fa-f]{6}")) throw invalid(path + "." + field, "颜色必须为 #RRGGBB");
        return Integer.parseInt(value.substring(1), 16);
    }

    private static int integer(JsonObject json, String field, int fallback, int min, int max, String path, boolean required) {
        if (!json.has(field)) {
            if (required) throw invalid(path + "." + field, "缺少字段");
            return fallback;
        }
        JsonElement value = json.get(field);
        try {
            int result = value.getAsInt();
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                    || value.getAsDouble() != result || result < min || result > max) throw new RuntimeException();
            return result;
        } catch (RuntimeException exception) {
            throw invalid(path + "." + field, "整数必须在 " + min + ".." + max);
        }
    }

    private static double number(JsonObject json, String field, double min, double max, String path, boolean required) {
        if (!json.has(field)) {
            if (required) throw invalid(path + "." + field, "缺少字段");
            return min;
        }
        JsonElement value = json.get(field);
        try {
            double result = value.getAsDouble();
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                    || !Double.isFinite(result) || result < min || result > max
                    || required && result <= min) throw new RuntimeException();
            return result;
        } catch (RuntimeException exception) {
            throw invalid(path + "." + field, "数值必须在 " + (required ? "(" : "[") + min + "," + max + "]");
        }
    }

    private static void rejectUnknown(JsonObject json, Set<String> allowed, String path) {
        for (String field : json.keySet()) {
            if (!allowed.contains(field)) throw invalid(path.equals("$") ? field : path + "." + field, "未知字段");
        }
    }

    private static InvalidData invalid(String path, String message) {
        return new InvalidData(path + ": " + message);
    }

    public record ParseResult(ReactionSpec definition, List<String> errors) {
        public ParseResult { errors = List.copyOf(errors); }
    }

    private record Selector(Optional<ResourceLocation> value, Optional<ResourceLocation> tag) {}
    private record Position(Set<ResourceLocation> elements, double ratio) {}
    private static final class InvalidData extends RuntimeException {
        private InvalidData(String message) { super(message); }
    }
}
