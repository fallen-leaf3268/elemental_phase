package com.elementalphase.profile;

import com.elementalphase.data.ElementDataManager;
import com.elementalphase.data.ElementDataSnapshot;
import com.elementalphase.data.model.EntityProfileDefinition;
import com.elementalphase.state.ElementalState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class EntityProfileResolver {
    public ResolvedEntityProfile resolve(ResourceLocation entityTypeId, Set<ResourceLocation> tags,
                                         List<EntityProfileDefinition> profiles) {
        Optional<EntityProfileDefinition> selected = profiles.stream()
                .filter(profile -> matches(profile.selector(), entityTypeId, tags))
                .max(Comparator.comparingInt(EntityProfileDefinition::priority)
                        .thenComparing(profile -> profile.selector().kind() == EntityProfileDefinition.SelectorKind.ENTITY_TAG ? 0 : 1)
                        .thenComparing(profile -> profile.id().toString()));
        if (selected.isEmpty()) {
            return new ResolvedEntityProfile(Map.of(), Map.of(), Map.of(), Optional.empty(), Optional.empty(), 0);
        }
        EntityProfileDefinition profile = selected.orElseThrow();
        return new ResolvedEntityProfile(profile.permanentElements(), profile.resistances(), profile.reactionResistances(),
                profile.intrinsicAttack(), Optional.of(profile.id()), profile.priority());
    }

    public ResolvedEntityProfile resolve(LivingEntity entity, ElementDataSnapshot snapshot) {
        ResourceLocation entityTypeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        Set<ResourceLocation> tags = snapshot.entityProfiles().stream()
                .map(EntityProfileDefinition::selector)
                .filter(selector -> selector.kind() == EntityProfileDefinition.SelectorKind.ENTITY_TAG)
                .map(EntityProfileDefinition.Selector::id)
                .filter(tag -> entity.getType().is(TagKey.create(Registries.ENTITY_TYPE, tag)))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return resolve(entityTypeId, tags, snapshot.entityProfiles());
    }

    public void apply(LivingEntity entity, ElementalState state, ElementDataSnapshot snapshot) {
        ResolvedEntityProfile profile = resolve(entity, snapshot);

        state.clearVirtualElements();
        state.reconcilePermanent(profile.permanentElements());
        state.replaceResistances(profile.resistances());
        state.replaceReactionResistances(profile.reactionResistances());
        state.clearIntrinsicAttack();
        profile.intrinsicAttack().ifPresent(attack -> state.setIntrinsicAttack(attack.element(), attack.baseAmount()));
        state.reconcileElementLimits(snapshot.elements(), entity.level().getGameTime());
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
            Map<ResourceLocation, Double> reactionResistances,
            Optional<EntityProfileDefinition.IntrinsicAttack> intrinsicAttack,
            Optional<ResourceLocation> profileId,
            int priority) {
        public ResolvedEntityProfile {
            permanentElements = Map.copyOf(permanentElements);
            resistances = Map.copyOf(resistances);
            reactionResistances = Map.copyOf(reactionResistances);
            intrinsicAttack = intrinsicAttack == null ? Optional.empty() : intrinsicAttack;
            profileId = profileId == null ? Optional.empty() : profileId;
        }
    }
}
