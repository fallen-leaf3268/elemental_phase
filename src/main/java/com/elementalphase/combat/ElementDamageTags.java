package com.elementalphase.combat;

import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public final class ElementDamageTags {
    private ElementDamageTags() {
    }

    public static ResourceLocation tagId(ResourceLocation element) {
        return ResourceLocation.fromNamespaceAndPath(element.getNamespace(), "elements/" + element.getPath());
    }

    public static Selection select(Collection<ResourceLocation> elements, Predicate<ResourceLocation> matchesTag) {
        return new Selection(elements.stream().distinct().filter(element -> matchesTag.test(tagId(element)))
                .sorted(Comparator.comparing(ResourceLocation::toString)).toList());
    }

    public record Selection(List<ResourceLocation> matches) {
        public Selection {
            matches = List.copyOf(matches);
        }

        public boolean conflicted() {
            return matches.size() > 1;
        }

        public Optional<ResourceLocation> element() {
            return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
        }
    }
}
