package com.elementalphase.reaction;

import com.elementalphase.data.model.ReactionDamageDefinition;
import com.elementalphase.data.model.ReactionEffectDefinition;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

public record ReactionOutcome(double amplifyDamage, List<TriggeredEffect> effects, List<TriggeredReaction> reactions) {
    public ReactionOutcome {
        effects = List.copyOf(effects);
        reactions = List.copyOf(reactions);
    }

    public static ReactionOutcome empty() {
        return new ReactionOutcome(0.0D, List.of(), List.of());
    }

    public List<TriggeredReaction> additionalReactions() {
        return reactions.stream()
                .filter(reaction -> reaction.mode() == ReactionDamageDefinition.Mode.ADDITIONAL)
                .toList();
    }

    public record TriggeredEffect(ReactionEffectDefinition definition, double scale) {
    }

    public record TriggeredReaction(ResourceLocation id, double scale, double damage,
                                    ReactionDamageDefinition.Mode mode, Optional<ResourceLocation> damageType,
                                    int displayColor, Optional<TriggeredArea> area, ResourceLocation originReactionId,
                                    boolean showName) {
        public TriggeredReaction {
            damageType = Optional.ofNullable(damageType).orElseThrow();
            area = Optional.ofNullable(area).orElseThrow();
            java.util.Objects.requireNonNull(id);
            java.util.Objects.requireNonNull(originReactionId);
        }

        public TriggeredReaction(ResourceLocation id, double scale, double damage,
                                 ReactionDamageDefinition.Mode mode, Optional<ResourceLocation> damageType,
                                 int displayColor, Optional<TriggeredArea> area) {
            this(id, scale, damage, mode, damageType, displayColor, area, id, true);
        }

        public TriggeredReaction(ResourceLocation id, double scale, double damage,
                                 ReactionDamageDefinition.Mode mode, Optional<ResourceLocation> damageType,
                                 int displayColor) {
            this(id, scale, damage, mode, damageType, displayColor, Optional.empty());
        }

        public TriggeredReaction(ResourceLocation id, double scale) {
            this(id, scale, 0.0D, ReactionDamageDefinition.Mode.AMPLIFY, Optional.empty(), 0xFFFFFF,
                    Optional.empty());
        }
    }

    public record TriggeredArea(double radius, ResourceLocation spreadElement, double attachmentAmount,
                                boolean attachAttacker) {
    }
}
