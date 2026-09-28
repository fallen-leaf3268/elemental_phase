package com.elementalphase.reaction;

import com.elementalphase.data.model.ReactionAction;
import com.elementalphase.state.ElementRuntimeState;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

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

    public List<Label> mainDamageLabels() {
        return labels.stream().filter(Label::amplifiesMainDamage).toList();
    }

    public record Label(ResourceLocation reactionId, double scale, int color, boolean visible,
                        ReactionFacts facts, boolean amplifiesMainDamage) {
    }

    public record PlannedAction(ReactionAction action, ReactionFacts facts, Optional<ReactionAction.StateSnapshot> stateSnapshot) {
        public PlannedAction { stateSnapshot = java.util.Objects.requireNonNull(stateSnapshot); }
        public PlannedAction(ReactionAction action, ReactionFacts facts) { this(action, facts, Optional.empty()); }
    }
}
