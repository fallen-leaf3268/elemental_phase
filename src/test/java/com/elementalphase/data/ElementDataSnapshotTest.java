package com.elementalphase.data;

import com.elementalphase.data.model.ElementAttachmentPolicy;
import com.elementalphase.data.model.ElementDefinition;
import com.elementalphase.data.model.ElementDisplayDefinition;
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
