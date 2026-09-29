package com.elementalphase.network;

import com.elementalphase.display.DamagePopupText;
import com.elementalphase.data.model.ReactionSpec;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ElementCatalogPacketTest {
    @Test
    void catalogCarriesServerEnchantmentSettings() {
        assertEquals(List.of("elements", "reactionNames", "elementColors", "enhancementEnabled",
                        "baseAttachmentAmount"),
                java.util.Arrays.stream(ElementCatalogPacket.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName).toList());
    }

    @Test
    void decodesElementColorsAlongsideNames() {
        var id = ResourceLocation.parse("example:weather/steam");
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeMap(Map.of(id, "element.example.steam"),
                    FriendlyByteBuf::writeResourceLocation, FriendlyByteBuf::writeUtf);
            buffer.writeMap(Map.<ResourceLocation, String>of(),
                    FriendlyByteBuf::writeResourceLocation, FriendlyByteBuf::writeUtf);
            buffer.writeMap(Map.of(id, 0x7EE7C4),
                    FriendlyByteBuf::writeResourceLocation, FriendlyByteBuf::writeInt);
            buffer.writeBoolean(true);
            buffer.writeDouble(2.5D);
            var packet = ElementCatalogPacket.decode(buffer);
            assertEquals("element.example.steam", packet.elements().get(id));
            assertEquals(0x7EE7C4, packet.elementColors().get(id));
            assertTrue(packet.enhancementEnabled());
            assertEquals(2.5D, packet.baseAttachmentAmount());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void roundTripsCustomNamespaceAndNestedElementIds() {
        var id = ResourceLocation.parse("example:weather/steam");
        var reaction = ResourceLocation.parse("example:weather/vaporize");
        var packet = new ElementCatalogPacket(Map.of(id, "element.example.steam"),
                Map.of(reaction, "reaction.example.shared_vaporize"), Map.of(id, 0x7EE7C4), true, 2.5D);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ElementCatalogPacket.encode(packet, buffer);
            assertEquals(packet, ElementCatalogPacket.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void reactionNamesRefreshIndependentlyAndClearOnEmptyCatalog() {
        var id = ResourceLocation.parse("example:weather/vaporize");
        var names = new HashMap<ResourceLocation, String>();
        names.put(id, "reaction.example.first_name");
        try {
            DamagePopupText.replaceReactionNames(names);
            names.put(id, "reaction.example.changed_outside");
            assertEquals("reaction.example.first_name", DamagePopupText.translationKey(id));
            DamagePopupText.replaceReactionNames(Map.of(id, "reaction.example.second_name"));
            assertEquals("reaction.example.second_name", DamagePopupText.translationKey(id));
            var cleared = new ElementCatalogPacket(Map.of());
            assertTrue(cleared.reactionNames().isEmpty());
            DamagePopupText.replaceReactionNames(cleared.reactionNames());
            assertEquals("reaction.example.weather.vaporize", DamagePopupText.translationKey(id));
        } finally {
            DamagePopupText.replaceReactionNames(Map.of());
        }
    }

    @Test
    void serverReactionNamesDoNotDependOnClientCatalog() {
        var id = ResourceLocation.parse("example:weather/vaporize");
        var spec = new ReactionSpec(id, 0, Set.of(), List.of(), "reaction.example.server_name");
        try {
            DamagePopupText.replaceReactionNames(Map.of(id, "reaction.example.client_name"));
            assertEquals("reaction.example.server_name", DamagePopupText.translationKey(id, Map.of(id, spec)));
            assertEquals("reaction.example.client_name", DamagePopupText.translationKey(id));
            assertEquals("reaction.example.weather.vaporize", DamagePopupText.translationKey(id, Map.of()));
        } finally {
            DamagePopupText.replaceReactionNames(Map.of());
        }
    }

    @Test
    void catalogCopiesAllMaps() {
        var id = ResourceLocation.parse("example:custom");
        var elements = new HashMap<ResourceLocation, String>();
        var names = new HashMap<ResourceLocation, String>();
        var colors = new HashMap<ResourceLocation, Integer>();
        elements.put(id, "element.example.custom");
        names.put(id, "reaction.example.custom");
        colors.put(id, 0x7EE7C4);
        var packet = new ElementCatalogPacket(elements, names, colors);
        elements.clear();
        names.clear();
        colors.clear();
        assertEquals("element.example.custom", packet.elements().get(id));
        assertEquals("reaction.example.custom", packet.reactionNames().get(id));
        assertEquals(0x7EE7C4, packet.elementColors().get(id));
        assertThrows(UnsupportedOperationException.class, () -> packet.reactionNames().clear());
        assertThrows(UnsupportedOperationException.class, () -> packet.elementColors().clear());
    }

    @Test
    void synchronizedEnchantmentSettingsReplaceAndClearBetweenWorlds() {
        try {
            assertTrue(com.elementalphase.enchantment.ElementBookCatalog.replaceClientSettings(true, 2.5D));
            assertTrue(com.elementalphase.enchantment.ElementBookCatalog.enhancementEnabled());
            assertEquals(2.5D, com.elementalphase.enchantment.ElementBookCatalog.baseAttachmentAmount());
            assertFalse(com.elementalphase.enchantment.ElementBookCatalog.replaceClientSettings(true, 2.5D));
            assertTrue(com.elementalphase.enchantment.ElementBookCatalog.replaceClientSettings(false, 1.0D));
            assertFalse(com.elementalphase.enchantment.ElementBookCatalog.enhancementEnabled());
            assertEquals(1.0D, com.elementalphase.enchantment.ElementBookCatalog.baseAttachmentAmount());
        } finally {
            com.elementalphase.enchantment.ElementBookCatalog.clearClientSettings();
        }
        assertFalse(com.elementalphase.enchantment.ElementBookCatalog.enhancementEnabled());
        assertEquals(1.0D, com.elementalphase.enchantment.ElementBookCatalog.baseAttachmentAmount());
    }

    @Test
    void emptyCatalogClearsPreviousElements() {
        var id = ResourceLocation.parse("example:removed");
        assertTrue(com.elementalphase.enchantment.ElementBookCatalog.replaceClient(
                Map.of(id, "removed"), Map.of(id, 0x7EE7C4)));
        assertTrue(com.elementalphase.enchantment.ElementBookCatalog.replaceClient(Map.of()));
        assertTrue(com.elementalphase.enchantment.ElementBookCatalog.clientElements().isEmpty());
        assertTrue(com.elementalphase.enchantment.ElementBookCatalog.clientColors().isEmpty());
        assertEquals(net.minecraft.ChatFormatting.GRAY.getColor(),
                com.elementalphase.enchantment.ElementBookCatalog.color(id));
        assertFalse(com.elementalphase.enchantment.ElementBookCatalog.replaceClient(Map.of()));
    }

    @Test
    void synchronizedColorsReplaceWithoutChangingElementNames() {
        var id = ResourceLocation.parse("example:weather/steam");
        var names = com.elementalphase.enchantment.ElementBookCatalog.clientElements();
        var originalColors = com.elementalphase.enchantment.ElementBookCatalog.clientColors();
        var colors = new HashMap<ResourceLocation, Integer>();
        colors.put(id, 0);
        try {
            var elements = Map.of(id, "element.example.steam");
            assertTrue(com.elementalphase.enchantment.ElementBookCatalog.replaceClient(elements, colors));
            colors.put(id, 0x7EE7C4);
            assertEquals(0, com.elementalphase.enchantment.ElementBookCatalog.color(id));
            assertTrue(com.elementalphase.enchantment.ElementBookCatalog.replaceClient(elements, colors));
            assertEquals(0x7EE7C4, com.elementalphase.enchantment.ElementBookCatalog.color(id));
            assertFalse(com.elementalphase.enchantment.ElementBookCatalog.replaceClient(elements, colors));
            assertThrows(UnsupportedOperationException.class,
                    () -> com.elementalphase.enchantment.ElementBookCatalog.clientColors().clear());
        } finally {
            com.elementalphase.enchantment.ElementBookCatalog.replaceClient(names, originalColors);
        }
    }
}
