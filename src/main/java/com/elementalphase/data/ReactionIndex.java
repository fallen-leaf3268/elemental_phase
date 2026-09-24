package com.elementalphase.data;

import com.elementalphase.data.model.ReactionDirection;
import com.elementalphase.data.model.ReactionSpec;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ReactionIndex {
    private final Map<ReactionDirectionKey, List<Candidate>> candidates;

    private ReactionIndex(Map<ReactionDirectionKey, List<Candidate>> candidates) {
        this.candidates = Map.copyOf(candidates);
    }

    public static ReactionIndex build(Map<ResourceLocation, ReactionSpec> reactions) {
        Map<ReactionDirectionKey, List<Candidate>> result = new HashMap<>();
        reactions.values().forEach(reaction -> reaction.directions().forEach(direction ->
                result.computeIfAbsent(new ReactionDirectionKey(direction.trigger(), direction.aura()), ignored -> new ArrayList<>())
                        .add(new Candidate(reaction, direction))));
        Comparator<Candidate> ordering = Comparator.comparingInt((Candidate value) -> value.direction().priority()).reversed()
                .thenComparing(value -> value.reaction().id().toString())
                .thenComparing(value -> value.direction().trigger().toString())
                .thenComparing(value -> value.direction().aura().toString());
        result.replaceAll((key, values) -> values.stream().sorted(ordering).toList());
        return new ReactionIndex(result);
    }

    public List<Candidate> candidates(ResourceLocation trigger, ResourceLocation aura) {
        return candidates.getOrDefault(new ReactionDirectionKey(trigger, aura), List.of());
    }

    public record Candidate(ReactionSpec reaction, ReactionDirection direction) {}
}
