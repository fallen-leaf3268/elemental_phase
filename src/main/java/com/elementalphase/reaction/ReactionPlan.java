package com.elementalphase.reaction;

import com.elementalphase.data.model.ReactionAction;
import com.elementalphase.state.ElementRuntimeState;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record ReactionPlan(double mainDamageBonus, double finalDamage,
                           ElementRuntimeState.ApplyResult applyResult,
                           List<Label> labels, List<PlannedAction> actions) {
    public ReactionPlan {
        labels = List.copyOf(labels);
        actions = List.copyOf(actions);
    }

    public static ReactionPlan empty(double damage, ElementRuntimeState.ApplyResult result) {
        return new ReactionPlan(0.0D, damage, result, List.of(), List.of());
    }

    public record Label(ResourceLocation reactionId, double scale, int color, boolean visible,
                        ReactionFacts facts) {
    }

    public record PlannedAction(ReactionAction action, ReactionFacts facts) {
    }
}
