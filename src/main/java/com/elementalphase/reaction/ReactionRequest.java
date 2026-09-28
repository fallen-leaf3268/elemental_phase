package com.elementalphase.reaction;

import com.elementalphase.data.ReactionIndex;
import com.elementalphase.data.model.ElementDefinition;
import com.elementalphase.data.model.ReactionCondition;
import com.elementalphase.state.ElementSourceSnapshot;
import com.elementalphase.state.ElementalState;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.Map;
import java.util.function.Predicate;

public record ReactionRequest(ElementalState state, ResourceLocation trigger, double triggerAmount,
                              long gameTime, ElementDefinition elementDefinition, ReactionIndex index,
                              Map<ResourceLocation, ElementDefinition> elements,
                              double originalDamage, double attackerLevel, double elementStrength,
                              double targetHealth, double targetMaxHealth, double targetResistance,
                              Optional<ElementSourceSnapshot> source,
                              Predicate<ReactionCondition> conditionEvaluator,
                              ReactionChainGuard guard, boolean hasOriginalHit) {
    public ReactionRequest {
        if (state == null || trigger == null || elementDefinition == null || index == null || elements == null) {
            throw new IllegalArgumentException("Missing reaction request data");
        }
        source = source == null ? Optional.empty() : source;
        conditionEvaluator = conditionEvaluator == null ? ignored -> true : conditionEvaluator;
        guard = guard == null ? new ReactionChainGuard() : guard;
    }
}
