package com.elementalphase.integration.jade;

import com.elementalphase.data.model.ElementApplicationPolicy;
import com.elementalphase.data.model.ElementAttachmentPolicy;
import com.elementalphase.data.model.ElementDefinition;
import com.elementalphase.data.model.ElementDisplayDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JadeElementDataTest {
    @Test
    void filtersSortsAndRoundTripsDisplayMetadata() {
        ResourceLocation alpha = id("alpha");
        ResourceLocation beta = id("beta");
        ResourceLocation hidden = id("hidden");
        ResourceLocation icon = ResourceLocation.fromNamespaceAndPath("test", "textures/gui/elements/alpha.png");
        Map<ResourceLocation, Double> amounts = new LinkedHashMap<>();
        amounts.put(beta, 2.0D);
        amounts.put(hidden, 3.0D);
        amounts.put(alpha, 1.0D);
        Map<ResourceLocation, ElementDefinition> definitions = Map.of(
                alpha, definition(alpha, true, 10, 0x112233, Optional.of(icon)),
                beta, definition(beta, true, 10, 0x445566, Optional.empty()),
                hidden, definition(hidden, false, -100, 0x778899, Optional.empty()));

        var entries = JadeElementData.collect(amounts, definitions);
        assertEquals(java.util.List.of(alpha, beta), entries.stream().map(JadeElementData.Entry::id).toList());

        CompoundTag tag = new CompoundTag();
        JadeElementData.write(tag, entries);
        assertEquals(entries, JadeElementData.read(tag));
    }

    private static ElementDefinition definition(ResourceLocation id, boolean visible, int order, int color,
                                                Optional<ResourceLocation> icon) {
        return new ElementDefinition(id, true, ElementApplicationPolicy.DEFAULT, ElementAttachmentPolicy.DEFAULT,
                new ElementDisplayDefinition("element.test." + id.getPath(), color, visible, order, icon));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }
}
