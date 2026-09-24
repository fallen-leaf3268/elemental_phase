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
import java.util.regex.Pattern;

public final class ReactionDataParser {
    public static final double MINIMUM_SCALE = 0.1D;
    public static final int MAX_DIRECTIONS = 64;
    public static final int MAX_CONDITIONS = 32;
    public static final int MAX_ACTIONS = 32;
    public static final double MAX_RADIUS = 32.0D;
    public static final int MAX_TARGETS = 64;
    public static final int MAX_FREEZE_TICKS = 72_000;
    private static final double MAX_NUMBER = 1_000_000.0D;
    private static final Pattern TARGET_SET = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final ReactionFormula ALWAYS = DamageFormulaParser.parseReaction("1");

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
        rejectUnknown(json, Set.of("enabled", "priority", "elements", "directions"), "$");
        if (!bool(json, "enabled", true, "$")) {
            return null;
        }
        int priority = integer(json, "priority", 0, Integer.MIN_VALUE, Integer.MAX_VALUE, "$", false);
        JsonArray elementArray = requiredArray(json, "elements", "$", 2, 64);
        LinkedHashSet<ResourceLocation> participants = new LinkedHashSet<>();
        for (int index = 0; index < elementArray.size(); index++) {
            ResourceLocation element = id(elementArray.get(index), "elements[" + index + "]");
            requireElement(element, elements, "elements[" + index + "]");
            if (!participants.add(element)) {
                throw invalid("elements[" + index + "]", "存在重复元素 " + element);
            }
        }
        JsonArray directions = requiredArray(json, "directions", "$", 1, MAX_DIRECTIONS);
        List<ReactionDirection> parsed = new ArrayList<>(directions.size());
        Set<ReactionDirectionKey> keys = new HashSet<>();
        for (int index = 0; index < directions.size(); index++) {
            String path = "directions[" + index + "]";
            ReactionDirection direction = direction(object(directions.get(index), path), path, priority,
                    participants, elements);
            if (!keys.add(new ReactionDirectionKey(direction.trigger(), direction.aura()))) {
                throw invalid(path, "重复定义方向 " + direction.trigger() + " -> " + direction.aura());
            }
            parsed.add(direction);
        }
        return new ReactionSpec(id, priority, participants, parsed);
    }

    private ReactionDirection direction(JsonObject json, String path, int defaultPriority,
                                        Set<ResourceLocation> participants,
                                        Map<ResourceLocation, ElementDefinition> elements) {
        rejectUnknown(json, Set.of("trigger", "aura", "priority", "minimum_scale", "consumption",
                "conditions", "display", "actions"), path);
        ResourceLocation trigger = requiredId(json, "trigger", path);
        ResourceLocation aura = requiredId(json, "aura", path);
        if (trigger.equals(aura)) throw invalid(path, "trigger 与 aura 不能相同");
        if (!participants.contains(trigger)) throw invalid(path + ".trigger", "元素不在顶层 elements 中");
        if (!participants.contains(aura)) throw invalid(path + ".aura", "元素不在顶层 elements 中");
        JsonObject consumption = requiredObject(json, "consumption", path);
        rejectUnknown(consumption, Set.of("trigger", "aura"), path + ".consumption");
        ReactionConsumption ratios = new ReactionConsumption(
                number(consumption, "trigger", 0.0D, MAX_NUMBER, path + ".consumption", true),
                number(consumption, "aura", 0.0D, MAX_NUMBER, path + ".consumption", true));
        double minimum = number(json, "minimum_scale", MINIMUM_SCALE, MAX_NUMBER, path, false);
        JsonArray conditionValues = array(json, "conditions", path, 0, MAX_CONDITIONS, false);
        List<ReactionCondition> conditions = new ArrayList<>(conditionValues.size());
        for (int index = 0; index < conditionValues.size(); index++) {
            String conditionPath = path + ".conditions[" + index + "]";
            conditions.add(condition(object(conditionValues.get(index), conditionPath), conditionPath));
        }
        ReactionDisplay display = display(requiredObject(json, "display", path), path + ".display");
        JsonArray actionValues = array(json, "actions", path, 0, MAX_ACTIONS, false);
        List<ReactionAction> actions = new ArrayList<>(actionValues.size());
        Set<String> targetSets = new HashSet<>();
        for (int index = 0; index < actionValues.size(); index++) {
            String actionPath = path + ".actions[" + index + "]";
            ReactionAction action = action(object(actionValues.get(index), actionPath), actionPath, elements, targetSets);
            if (action instanceof ReactionAction.AreaDamage area && area.targetSet().isPresent()
                    && !targetSets.add(area.targetSet().get())) {
                throw invalid(actionPath + ".target_set", "target_set 重复");
            }
            actions.add(action);
        }
        return new ReactionDirection(trigger, aura,
                integer(json, "priority", defaultPriority, Integer.MIN_VALUE, Integer.MAX_VALUE, path, false),
                minimum, ratios, conditions, display, actions);
    }

    private ReactionDisplay display(JsonObject json, String path) {
        rejectUnknown(json, Set.of("color", "show_reaction"), path);
        return new ReactionDisplay(color(json, "color", 0xFFFFFF, path), bool(json, "show_reaction", true, path));
    }

    private ReactionCondition condition(JsonObject json, String path) {
        String type = string(json, "type", path, true, null);
        boolean inverted = bool(json, "inverted", false, path);
        return switch (type) {
            case "attacker_present" -> {
                rejectUnknown(json, Set.of("type", "inverted", "value"), path);
                yield new ReactionCondition.AttackerPresent(bool(json, "value", true, path), inverted);
            }
            case "attacker_entity" -> entityCondition(json, path, inverted, true);
            case "target_entity" -> entityCondition(json, path, inverted, false);
            case "damage_type" -> {
                rejectUnknown(json, Set.of("type", "inverted", "damage_type", "tag"), path);
                Selector selector = selector(json, "damage_type", path);
                yield new ReactionCondition.DamageType(selector.value(), selector.tag(), inverted);
            }
            case "source_kind" -> {
                rejectUnknown(json, Set.of("type", "inverted", "value"), path);
                ReactionCondition.Source.Kind kind = switch (string(json, "value", path, true, null)) {
                    case "melee" -> ReactionCondition.Source.Kind.MELEE;
                    case "projectile" -> ReactionCondition.Source.Kind.PROJECTILE;
                    case "magic" -> ReactionCondition.Source.Kind.MAGIC;
                    case "environment" -> ReactionCondition.Source.Kind.ENVIRONMENT;
                    default -> throw invalid(path + ".value", "未知来源类型");
                };
                yield new ReactionCondition.Source(kind, inverted);
            }
            case "minimum_damage" -> {
                rejectUnknown(json, Set.of("type", "inverted", "value"), path);
                yield new ReactionCondition.MinimumDamage(number(json, "value", 0.0D, MAX_NUMBER, path, true), inverted);
            }
            case "target_on_fire" -> booleanCondition(json, path, inverted, 0);
            case "target_in_water" -> booleanCondition(json, path, inverted, 1);
            case "target_is_boss" -> booleanCondition(json, path, inverted, 2);
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

    private ReactionCondition booleanCondition(JsonObject json, String path, boolean inverted, int kind) {
        rejectUnknown(json, Set.of("type", "inverted", "value"), path);
        boolean value = bool(json, "value", true, path);
        return switch (kind) {
            case 0 -> new ReactionCondition.TargetOnFire(value, inverted);
            case 1 -> new ReactionCondition.TargetInWater(value, inverted);
            default -> new ReactionCondition.TargetIsBoss(value, inverted);
        };
    }

    private ReactionAction action(JsonObject json, String path, Map<ResourceLocation, ElementDefinition> elements,
                                  Set<String> earlierTargetSets) {
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
            case "area_damage" -> {
                rejectUnknown(json, damageFields(Set.of("type", "when", "formula", "radius", "center",
                        "include_original_target", "include_attacker", "max_targets", "falloff", "target_set")), path);
                Optional<String> targetSet = optionalTargetSet(json, "target_set", path);
                yield new ReactionAction.AreaDamage(number(json, "radius", 0.0D, MAX_RADIUS, path, true),
                        formulaValue(json, "formula", path), damageSettings(json, path, elements),
                        center(json, path), bool(json, "include_original_target", true, path),
                        bool(json, "include_attacker", false, path),
                        integer(json, "max_targets", MAX_TARGETS, 1, MAX_TARGETS, path, false),
                        formulaValue(json, "falloff", "1", path), targetSet, when);
            }
            case "mob_effect" -> {
                rejectUnknown(json, Set.of("type", "when", "target", "effect", "duration_ticks", "amplifier", "ambient", "visible"), path);
                yield new ReactionAction.MobEffect(target(json, path), requiredId(json, "effect", path),
                        integer(json, "duration_ticks", 0, 1, Integer.MAX_VALUE, path, true),
                        integer(json, "amplifier", 0, 0, 255, path, false),
                        bool(json, "ambient", false, path), bool(json, "visible", true, path), when);
            }
            case "ignite" -> {
                rejectUnknown(json, Set.of("type", "when", "target", "duration_ticks"), path);
                int ticks = integer(json, "duration_ticks", 0, 1, Integer.MAX_VALUE, path, true);
                yield new ReactionAction.Ignite(target(json, path), ticks, when);
            }
            case "apply_freeze" -> {
                rejectUnknown(json, Set.of("type", "when", "target", "duration_ticks"), path);
                yield new ReactionAction.ApplyFreeze(target(json, path),
                        integer(json, "duration_ticks", 0, 1, MAX_FREEZE_TICKS, path, true), when);
            }
            case "schedule_damage" -> {
                rejectUnknown(json, Set.of("type", "when", "id", "target", "duration_ticks",
                        "interval_ticks", "damage"), path);
                yield new ReactionAction.ScheduleDamage(requiredId(json, "id", path), target(json, path),
                        integer(json, "duration_ticks", 0, 0, Integer.MAX_VALUE, path, true),
                        integer(json, "interval_ticks", 0, 1, Integer.MAX_VALUE, path, true),
                        stateDamage(requiredObject(json, "damage", path), path + ".damage", elements), when);
            }
            case "knockback" -> {
                rejectUnknown(json, Set.of("type", "when", "target", "strength", "origin"), path);
                ReactionAction.KnockbackOrigin origin = switch (string(json, "origin", path, false, "attacker")) {
                    case "attacker" -> ReactionAction.KnockbackOrigin.ATTACKER;
                    case "target" -> ReactionAction.KnockbackOrigin.TARGET;
                    case "reaction" -> ReactionAction.KnockbackOrigin.REACTION;
                    default -> throw invalid(path + ".origin", "未知击退原点");
                };
                yield new ReactionAction.Knockback(target(json, path), formulaValue(json, "strength", path), origin, when);
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
            case "spread_element" -> {
                rejectUnknown(json, Set.of("type", "when", "center", "radius", "element", "amount",
                        "include_original_target", "include_attacker", "max_targets", "respect_attachment_cooldown",
                        "source_target_set"), path);
                Optional<String> sourceSet = optionalTargetSet(json, "source_target_set", path);
                if (sourceSet.isPresent() && !earlierTargetSets.contains(sourceSet.get())) {
                    throw invalid(path + ".source_target_set", "必须引用更早的 area_damage.target_set");
                }
                yield new ReactionAction.SpreadElement(center(json, path),
                        number(json, "radius", 0.0D, MAX_RADIUS, path, true),
                        elementReference(json, "element", path, elements), formulaValue(json, "amount", path),
                        bool(json, "include_original_target", false, path), bool(json, "include_attacker", false, path),
                        integer(json, "max_targets", MAX_TARGETS, 1, MAX_TARGETS, path, false),
                        bool(json, "respect_attachment_cooldown", true, path), sourceSet, when);
            }
            case "attach_element" -> {
                rejectUnknown(json, Set.of("type", "when", "target", "element", "amount", "duration_ticks"), path);
                ResourceLocation element = requiredId(json, "element", path);
                requireElement(element, elements, path + ".element");
                Optional<Integer> duration = json.has("duration_ticks")
                        ? Optional.of(integer(json, "duration_ticks", 0, 1, Integer.MAX_VALUE, path, true))
                        : Optional.empty();
                yield new ReactionAction.AttachElement(target(json, path), element,
                        formulaValue(json, "amount", path), duration, when);
            }
            default -> throw invalid(path + ".type", "未知动作类型 " + type);
        };
    }

    private ReactionAction.DamageSettings damageSettings(JsonObject json, String path,
                                                          Map<ResourceLocation, ElementDefinition> elements) {
        return new ReactionAction.DamageSettings(
                json.has("damage_type") ? requiredId(json, "damage_type", path) : ReactionAction.DEFAULT_DAMAGE_TYPE,
                json.has("resistance_element") ? Optional.of(elementReference(json, "resistance_element", path, elements)) : Optional.empty(),
                bool(json, "bypass_armor", false, path), bool(json, "bypass_invulnerability", false, path),
                bool(json, "allow_element_application", false, path), bool(json, "allow_reactions", false, path));
    }

    private ReactionAction.StateDamage stateDamage(JsonObject json, String path,
                                                    Map<ResourceLocation, ElementDefinition> elements) {
        rejectUnknown(json, Set.of("formula", "damage_type", "resistance_element", "color"), path);
        return new ReactionAction.StateDamage(
                formulaValue(json, "formula", path),
                json.has("damage_type") ? requiredId(json, "damage_type", path) : ReactionAction.DEFAULT_DAMAGE_TYPE,
                json.has("resistance_element")
                        ? Optional.of(elementReference(json, "resistance_element", path, elements))
                        : Optional.empty(),
                json.has("color") ? Optional.of(color(json, "color", 0xFFFFFF, path)) : Optional.empty());
    }

    private static Set<String> damageFields(Set<String> base) {
        Set<String> fields = new HashSet<>(base);
        fields.addAll(Set.of("damage_type", "resistance_element", "bypass_armor", "bypass_invulnerability",
                "allow_element_application", "allow_reactions"));
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

    private ReactionAction.Center center(JsonObject json, String path) {
        return switch (string(json, "center", path, false, "target")) {
            case "target" -> ReactionAction.Center.TARGET;
            case "attacker" -> ReactionAction.Center.ATTACKER;
            default -> throw invalid(path + ".center", "未知中心");
        };
    }

    private ReactionAction.Formula formulaValue(JsonObject json, String field, String path) {
        return formulaValue(json, field, null, path);
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

    private Optional<String> optionalTargetSet(JsonObject json, String field, String path) {
        if (!json.has(field)) return Optional.empty();
        String value = string(json, field, path, true, null);
        if (!TARGET_SET.matcher(value).matches()) throw invalid(path + "." + field, "名称格式无效");
        return Optional.of(value);
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
        if (element == null || !element.enabled()) throw invalid(path, "引用不存在或禁用的元素 " + id);
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
    private static final class InvalidData extends RuntimeException {
        private InvalidData(String message) { super(message); }
    }
}
