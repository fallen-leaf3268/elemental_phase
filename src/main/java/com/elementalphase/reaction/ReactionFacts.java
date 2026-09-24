package com.elementalphase.reaction;

import com.elementalphase.data.model.ReactionDirection;
import com.elementalphase.state.ElementPortion;
import com.elementalphase.state.ElementSourceSnapshot;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

public record ReactionFacts(ResourceLocation reactionId, ReactionDirection direction,
                            double originalDamage, double damageBeforeReaction,
                            double triggerAmount, double auraAmount, double scale,
                            double consumedTrigger, double consumedAura,
                            double remainingTrigger, double remainingAura,
                            double attackerLevel, double elementStrength,
                            double targetHealth, double targetMaxHealth, double targetResistance,
                            Optional<ElementSourceSnapshot> source,
                            List<ElementPortion> triggerPortions, List<ElementPortion> auraPortions) {
    public ReactionFacts {
        source = source == null ? Optional.empty() : source;
        triggerPortions = List.copyOf(triggerPortions);
        auraPortions = List.copyOf(auraPortions);
    }
}
