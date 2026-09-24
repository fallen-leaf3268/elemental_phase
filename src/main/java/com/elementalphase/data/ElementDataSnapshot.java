package com.elementalphase.data;

import com.elementalphase.data.model.AttackSourceDefinition;
import com.elementalphase.data.model.ElementDefinition;
import com.elementalphase.data.model.EntityProfileDefinition;
import com.elementalphase.data.model.ReactionSpec;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ElementDataSnapshot(
        Map<ResourceLocation, ElementDefinition> elements,
        Map<ResourceLocation, ReactionSpec> reactions,
        ReactionIndex reactionIndex,
        List<EntityProfileDefinition> entityProfiles,
        List<AttackSourceDefinition> attackSources) {
    public ElementDataSnapshot {
        elements = sortedMap(elements);
        reactions = sortedMap(reactions);
        reactionIndex = reactionIndex == null ? ReactionIndex.build(reactions) : reactionIndex;
        entityProfiles = List.copyOf(entityProfiles);
        attackSources = List.copyOf(attackSources);
    }

    public static ElementDataSnapshot empty() {
        return new ElementDataSnapshot(Map.of(), Map.of(), ReactionIndex.build(Map.of()), List.of(), List.of());
    }

    private static <V> Map<ResourceLocation, V> sortedMap(Map<ResourceLocation, V> source) {
        Map<ResourceLocation, V> sorted = new LinkedHashMap<>();
        source.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
                .forEach(entry -> sorted.put(Objects.requireNonNull(entry.getKey()),
                        Objects.requireNonNull(entry.getValue())));
        return Collections.unmodifiableMap(sorted);
    }
}
