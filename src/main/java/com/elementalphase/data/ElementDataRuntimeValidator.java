package com.elementalphase.data;

import com.elementalphase.data.model.ReactionAction;
import com.elementalphase.data.model.ElementDefinition;
import com.elementalphase.data.model.ReactionCondition;
import com.elementalphase.data.model.ReactionSpec;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class ElementDataRuntimeValidator {
    private ElementDataRuntimeValidator() {
    }

    public static ElementDataParser.ParseReport validate(ElementDataParser.ParseReport report) {
        return validateReactions(report, ignored -> true, ignored -> true, false);
    }

    public static ElementDataParser.ParseReport validate(ElementDataParser.ParseReport report, RegistryAccess registries) {
        Registry<DamageType> damageTypes = registries.registryOrThrow(Registries.DAMAGE_TYPE);
        return validateReactions(report, damageTypes::containsKey, BuiltInRegistries.MOB_EFFECT::containsKey, true);
    }

    static ElementDataParser.ParseReport validateDamageTypes(ElementDataParser.ParseReport report,
                                                              Predicate<ResourceLocation> knownDamageTypes) {
        return validateReactions(report, knownDamageTypes, ignored -> true, true);
    }

    private static ElementDataParser.ParseReport validateReactions(ElementDataParser.ParseReport report,
                                                                    Predicate<ResourceLocation> knownDamageTypes,
                                                                    Predicate<ResourceLocation> knownMobEffects,
                                                                    boolean checkDamageTypes) {
        Map<ResourceLocation, ReactionSpec> reactions = new LinkedHashMap<>();
        List<ElementDataParser.FileError> errors = new ArrayList<>(report.errors());
        Map<ResourceLocation, ElementDefinition> elements = new LinkedHashMap<>(report.snapshot().elements());
        for (Map.Entry<ResourceLocation, ReactionSpec> entry : report.snapshot().reactions().entrySet()) {
            ReactionSpec reaction = entry.getValue();
            String invalid = validateReaction(reaction, elements, knownDamageTypes, knownMobEffects, checkDamageTypes);
            if (invalid == null) {
                reactions.put(entry.getKey(), reaction);
            } else {
                errors.add(new ElementDataParser.FileError(resource(reaction), invalid));
            }
        }
        ElementDataSnapshot source = report.snapshot();
        return new ElementDataParser.ParseReport(new ElementDataSnapshot(elements, reactions,
                ReactionIndex.build(reactions), source.entityProfiles(), source.attackSources()), errors);
    }

    private static String validateStateDamage(ReactionAction.StateDamage damage,
                                              Map<ResourceLocation, ElementDefinition> elements,
                                              Predicate<ResourceLocation> knownDamageTypes,
                                              boolean checkDamageTypes) {
        if (damage == null) return null;
        if (checkDamageTypes && !knownDamageTypes.test(damage.damageType())) {
            return "Unknown damage type " + damage.damageType();
        }
        if (damage.resistanceElement().isEmpty()) return null;
        ReactionAction.ElementReference reference = damage.resistanceElement().orElseThrow();
        if (reference.kind() != ReactionAction.ElementReference.Kind.FIXED
                || !elements.containsKey(reference.fixed())) {
            return "Unknown resistance element " + reference.fixed();
        }
        return null;
    }

    private static String validateReaction(ReactionSpec reaction,
                                           Map<ResourceLocation, com.elementalphase.data.model.ElementDefinition> elements,
                                           Predicate<ResourceLocation> knownDamageTypes,
                                           Predicate<ResourceLocation> knownMobEffects,
                                           boolean checkDamageTypes) {
        for (ResourceLocation participant : reaction.elements()) {
            if (!elements.containsKey(participant)) return "Unknown element " + participant;
        }
        for (var direction : reaction.directions()) {
            for (ReactionCondition condition : direction.conditions()) {
                if (checkDamageTypes && condition instanceof ReactionCondition.DamageType damage
                        && damage.damageType().isPresent()
                        && !knownDamageTypes.test(damage.damageType().orElseThrow())) {
                    return "Unknown damage type " + damage.damageType().orElseThrow();
                }
            }
            for (ReactionAction action : direction.actions()) {
                if (action instanceof ReactionAction.AttachElement attach) {
                    var element = elements.get(attach.element());
                    if (element == null || !element.enabled()) return "Unknown element " + attach.element();
                    if (!element.application().fromReaction()) {
                        return "Element cannot be created by a reaction " + attach.element();
                    }
                }
                if (action instanceof ReactionAction.SpreadElement spread) {
                    String invalid = validateReactionApplication(spread.element(), direction.trigger(), direction.aura(), elements);
                    if (invalid != null) return invalid;
                }
                if (action instanceof ReactionAction.ModifyElement modify
                        && (modify.operation() == ReactionAction.ElementOperation.ADD
                        || modify.operation() == ReactionAction.ElementOperation.SET)) {
                    String invalid = validateReactionApplication(modify.element(), direction.trigger(), direction.aura(), elements);
                    if (invalid != null) return invalid;
                }
                if (action instanceof ReactionAction.MobEffect effect
                        && !knownMobEffects.test(effect.effect())) {
                    return "Unknown mob effect " + effect.effect();
                }
                if (action instanceof ReactionAction.ScheduleDamage scheduled) {
                    String invalid = validateStateDamage(scheduled.damage(), elements,
                            knownDamageTypes, checkDamageTypes);
                    if (invalid != null) return invalid;
                }
                if (!checkDamageTypes) continue;
                ResourceLocation damageType = damageType(action);
                if (damageType != null && !knownDamageTypes.test(damageType)) return "Unknown damage type " + damageType;
            }
        }
        return null;
    }

    private static String validateReactionApplication(ReactionAction.ElementReference reference,
                                                      ResourceLocation trigger, ResourceLocation aura,
                                                      Map<ResourceLocation, ElementDefinition> elements) {
        ResourceLocation id = switch (reference.kind()) {
            case TRIGGER -> trigger;
            case AURA -> aura;
            case FIXED -> reference.fixed();
        };
        ElementDefinition element = elements.get(id);
        if (element == null || !element.enabled()) return "Unknown element " + id;
        return element.application().fromReaction()
                ? null : "Element cannot be created by a reaction " + id;
    }

    private static ResourceLocation damageType(ReactionAction action) {
        if (action instanceof ReactionAction.AdditionalDamage damage) return damage.settings().damageType();
        if (action instanceof ReactionAction.AreaDamage damage) return damage.settings().damageType();
        if (action instanceof ReactionAction.ScheduleDamage damage) return damage.damage().damageType();
        return null;
    }

    private static ResourceLocation resource(ReactionSpec reaction) {
        return ResourceLocation.fromNamespaceAndPath(reaction.id().getNamespace(),
                "elemental_phase/reactions/" + reaction.id().getPath() + ".json");
    }

}
