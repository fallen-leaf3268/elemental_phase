package com.elementalphase.integration.kubejs;

import com.elementalphase.data.ElementDataSnapshot;
import com.elementalphase.data.ReactionIndex;
import com.elementalphase.data.model.ReactionSpec;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class KubeJsHooks {
    private static volatile Function<ElementDataSnapshot, Overlay> provider = ignored -> Overlay.empty();

    private KubeJsHooks() {
    }

    public static void install(Function<ElementDataSnapshot, Overlay> next) {
        provider = next == null ? ignored -> Overlay.empty() : next;
    }

    public static void noop() {
        provider = ignored -> Overlay.empty();
    }

    public static ElementDataSnapshot apply(ElementDataSnapshot base) {
        Overlay overlay = provider.apply(base);
        if (overlay == null || overlay.replacements().isEmpty() && overlay.disabled().isEmpty()) return base;
        Map<ResourceLocation, ReactionSpec> reactions = new LinkedHashMap<>(base.reactions());
        overlay.disabled().forEach(reactions::remove);
        reactions.putAll(overlay.replacements());
        return new ElementDataSnapshot(base.elements(), reactions, ReactionIndex.build(reactions),
                base.entityProfiles());
    }

    public record Overlay(Map<ResourceLocation, ReactionSpec> replacements, Set<ResourceLocation> disabled,
                          Map<ResourceLocation, java.util.List<Object>> callbacks) {
        public Overlay {
            replacements = Map.copyOf(replacements);
            disabled = Set.copyOf(disabled);
            callbacks = Map.copyOf(callbacks);
        }

        public static Overlay empty() {
            return new Overlay(Map.of(), Set.of(), Map.of());
        }
    }
}
