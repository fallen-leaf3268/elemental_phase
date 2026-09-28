package com.elementalphase.data.model;

import net.minecraft.resources.ResourceLocation;
import java.util.Map;

public record ReactionDisplay(Color color, boolean showReaction) {
    public ReactionDisplay(int color, boolean showReaction) {
        this(new Color(color, Reference.FIXED), showReaction);
    }

    public enum Reference { FIXED, TRIGGER, AURA }

    public record Color(int fixed, Reference reference) {
        public int resolve(ResourceLocation trigger, ResourceLocation aura,
                           Map<ResourceLocation, ElementDefinition> elements) {
            if (reference == Reference.FIXED) return fixed;
            var definition = elements.get(reference == Reference.TRIGGER ? trigger : aura);
            return definition == null ? 0xFFFFFF : definition.display().color();
        }
    }
}
