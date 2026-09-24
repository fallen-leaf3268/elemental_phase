package com.elementalphase.profile;

import com.elementalphase.data.ElementDataManager;
import com.elementalphase.data.ElementDataSnapshot;
import com.elementalphase.data.model.EntityProfileDefinition;
import com.elementalphase.registry.ModAttributes;
import com.elementalphase.state.ElementalState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class EntityProfileResolver {
    public ResolvedEntityProfile resolve(ResourceLocation entityTypeId, Set<ResourceLocation> tags,
                                         List<EntityProfileDefinition> profiles) {
        List<EntityProfileDefinition> matches = profiles.stream()
                .filter(profile -> matches(profile.selector(), entityTypeId, tags))
                .sorted(Comparator.comparingInt(EntityProfileDefinition::priority)
                        .thenComparing(profile -> profile.selector().kind() == EntityProfileDefinition.SelectorKind.ENTITY_TAG ? 0 : 1)
                        .thenComparing(profile -> profile.id().toString()))
                .toList();

        Map<ResourceLocation, EntityProfileDefinition.PermanentElement> permanent = new HashMap<>();
        Map<ResourceLocation, Double> resistances = new HashMap<>();
        Optional<EntityProfileDefinition.IntrinsicAttack> intrinsic = Optional.empty();
        double strength = 1.0D;
        for (EntityProfileDefinition profile : matches) {
            for (ResourceLocation removed : profile.removedPermanentElements()) {
                permanent.remove(removed);
            }
            permanent.putAll(profile.permanentElements());
            resistances.putAll(profile.resistances());
            if (profile.clearIntrinsicAttack()) {
                intrinsic = Optional.empty();
            }
            if (profile.intrinsicAttack().isPresent()) {
                intrinsic = profile.intrinsicAttack();
            }
            if (profile.elementStrength().isPresent()) {
                strength = profile.elementStrength().getAsDouble();
            }
        }
        return new ResolvedEntityProfile(permanent, resistances, intrinsic, strength);
    }

    public void apply(LivingEntity entity, ElementalState state, ElementDataSnapshot snapshot) {
        ResourceLocation entityTypeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        Set<ResourceLocation> tags = snapshot.entityProfiles().stream()
                .map(EntityProfileDefinition::selector)
                .filter(selector -> selector.kind() == EntityProfileDefinition.SelectorKind.ENTITY_TAG)
                .map(EntityProfileDefinition.Selector::id)
                .filter(tag -> entity.getType().is(TagKey.create(Registries.ENTITY_TYPE, tag)))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        ResolvedEntityProfile profile = resolve(entityTypeId, tags, snapshot.entityProfiles());

        state.reconcilePermanent(profile.permanentElements());
        state.replaceResistances(profile.resistances());
        state.clearIntrinsicAttack();
        profile.intrinsicAttack().ifPresent(attack -> state.setIntrinsicAttack(attack.element(), attack.baseAmount()));
        state.reconcileElementLimits(snapshot.elements(), entity.level().getGameTime());
        AttributeInstance attribute = entity.getAttribute(ModAttributes.ELEMENT_STRENGTH.get());
        if (attribute != null) {
            attribute.setBaseValue(profile.elementStrength());
        }
        state.markInitialized(ElementDataManager.generation());
    }

    public void initializeIfNeeded(LivingEntity entity, ElementalState state, ElementDataSnapshot snapshot) {
        if (!state.initialized() || state.appliedGeneration() != ElementDataManager.generation()) {
            apply(entity, state, snapshot);
        }
    }

    private static boolean matches(EntityProfileDefinition.Selector selector, ResourceLocation entityTypeId,
                                   Set<ResourceLocation> tags) {
        return switch (selector.kind()) {
            case ENTITY_ID -> selector.id().equals(entityTypeId);
            case ENTITY_TAG -> tags.contains(selector.id());
        };
    }

    public record ResolvedEntityProfile(
            Map<ResourceLocation, EntityProfileDefinition.PermanentElement> permanentElements,
            Map<ResourceLocation, Double> resistances,
            Optional<EntityProfileDefinition.IntrinsicAttack> intrinsicAttack,
            double elementStrength) {
        public ResolvedEntityProfile {
            permanentElements = Map.copyOf(permanentElements);
            resistances = Map.copyOf(resistances);
            intrinsicAttack = intrinsicAttack == null ? Optional.empty() : intrinsicAttack;
        }
    }
}
