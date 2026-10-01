package com.elementalphase.data;

import com.elementalphase.data.model.ElementAttachmentPolicy;
import com.elementalphase.data.model.ElementDefinition;
import com.elementalphase.data.model.ElementDisplayDefinition;
import com.elementalphase.integration.kubejs.KubeJsHooks;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ElementDataSnapshotTest {
    @Test
    void overlayPreparationWaitsForCommitAndFailurePreservesPublishedData() throws Exception {
        var bootstrap = Class.forName("com.elementalphase.enchantment.ElementTestBootstrap")
                .getDeclaredMethod("initialize");
        bootstrap.setAccessible(true);
        bootstrap.invoke(null);
        var registries = new RegistryAccess.ImmutableRegistryAccess(List.of(
                new net.minecraft.core.MappedRegistry<net.minecraft.world.damagesource.DamageType>(
                        Registries.DAMAGE_TYPE, com.mojang.serialization.Lifecycle.stable())));
        var original = ElementDataManager.baseSnapshot();
        KubeJsHooks.noop();
        try {
            var first = new ElementDataSnapshot(Map.of(id("first"), definition(id("first"))),
                    Map.of(), null, List.of());
            var next = new ElementDataSnapshot(Map.of(id("next"), definition(id("next"))),
                    Map.of(), null, List.of());
            ElementDataManager.replace(first, registries);
            var published = ElementDataManager.snapshot();
            long generation = ElementDataManager.generation();
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            KubeJsHooks.install(base -> {
                calls.incrementAndGet();
                throw new IllegalStateException("reload failed");
            });
            assertEquals(0, calls.get());
            org.junit.jupiter.api.Assertions.assertSame(published, ElementDataManager.snapshot());
            assertEquals(generation, ElementDataManager.generation());
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> ElementDataManager.replace(next, registries));
            org.junit.jupiter.api.Assertions.assertSame(published, ElementDataManager.snapshot());
            org.junit.jupiter.api.Assertions.assertSame(first, ElementDataManager.baseSnapshot());
            assertEquals(generation, ElementDataManager.generation());
            KubeJsHooks.install(base -> {
                calls.incrementAndGet();
                org.junit.jupiter.api.Assertions.assertSame(next, base);
                return KubeJsHooks.Overlay.empty();
            });
            ElementDataManager.replace(next, registries);
            assertEquals(2, calls.get());
            assertEquals(next.elements(), ElementDataManager.snapshot().elements());
            assertEquals(generation + 1, ElementDataManager.generation());
        } finally {
            KubeJsHooks.noop();
            ElementDataManager.replace(original, registries);
        }
    }

    @Test
    void storesElementsInDeterministicIdOrder() {
        Map<ResourceLocation, ElementDefinition> elements = new LinkedHashMap<>();
        for (String path : List.of("zeta", "alpha", "theta", "beta", "eta", "gamma", "delta", "iota")) {
            ResourceLocation id = id(path);
            elements.put(id, definition(id));
        }

        ElementDataSnapshot snapshot = new ElementDataSnapshot(elements, Map.of(), null, List.of());

        assertEquals(elements.keySet().stream().sorted().toList(), new ArrayList<>(snapshot.elements().keySet()));
    }

    private static ElementDefinition definition(ResourceLocation id) {
        return new ElementDefinition(id,
                ElementAttachmentPolicy.DEFAULT,
                new ElementDisplayDefinition("element.test." + id.getPath(), 0xFFFFFF, true, 0, Optional.empty()));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }
}
