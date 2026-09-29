package com.elementalphase.integration.jade;

import com.elementalphase.data.model.ElementAttachmentPolicy;
import com.elementalphase.data.model.ElementDefinition;
import com.elementalphase.data.model.ElementDisplayDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import snownee.jade.impl.WailaClientRegistration;
import snownee.jade.impl.config.PluginConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JadeElementDataTest {
    @Test
    void registersElementDisplayWithOneEnabledToggle() throws ReflectiveOperationException {
        var initializeListenerList = net.minecraftforge.eventbus.api.EventListenerHelper.class.getDeclaredMethod(
                "getListenerListInternal", Class.class, boolean.class);
        initializeListenerList.setAccessible(true);
        initializeListenerList.invoke(null, net.minecraftforge.network.NetworkEvent.class, true);
        initializeListenerList.invoke(null, net.minecraftforge.network.NetworkEvent.GatherLoginPayloadsEvent.class, true);
        var registration = WailaClientRegistration.instance();
        var config = PluginConfig.INSTANCE;
        var uid = ElementalPhaseJadePlugin.ELEMENT_INFO;
        assertFalse(config.containsKey(uid));
        try {
            assertDoesNotThrow(() -> new ElementalPhaseJadePlugin().registerClient(registration));
            assertEquals(Set.of(uid), config.getKeys("elemental_phase"));
            assertEquals(Boolean.TRUE, config.getEntry(uid).getDefaultValue());
            assertEquals(Boolean.TRUE, config.getEntry(uid).getValue());
            assertTrue(config.set(uid, false));
            assertEquals(Boolean.FALSE, config.getEntry(uid).getValue());
        } finally {
            config.getKeys().remove(uid);
        }
    }

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
        return new ElementDefinition(id, ElementAttachmentPolicy.DEFAULT,
                new ElementDisplayDefinition("element.test." + id.getPath(), color, visible, order, icon));
    }

    @Test
    void hidesVirtualElementsEvenWhenTheirDisplayIsEnabled() {
        ResourceLocation virtual = id("virtual");
        ResourceLocation normal = id("normal");
        var virtualDefinition = new ElementDefinition(virtual,
                new ElementAttachmentPolicy(ElementAttachmentPolicy.Mode.VIRTUAL, 0, 10, 10.0D),
                new ElementDisplayDefinition("element.test.virtual", 0xFFFFFF, true, 0, Optional.empty()));

        var entries = JadeElementData.collect(Map.of(virtual, 5.0D, normal, 2.0D),
                Map.of(virtual, virtualDefinition, normal, definition(normal, true, 0, 0xFFFFFF, Optional.empty())));

        assertEquals(java.util.List.of(normal), entries.stream().map(JadeElementData.Entry::id).toList());
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }
}
