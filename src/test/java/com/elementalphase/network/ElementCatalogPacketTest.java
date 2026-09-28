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
    void roundTripsCustomNamespaceAndNestedElementIds() {
        var id = ResourceLocation.parse("example:weather/steam");
        var reaction = ResourceLocation.parse("example:weather/vaporize");
        var packet = new ElementCatalogPacket(Map.of(id, "element.example.steam"),
                Map.of(reaction, "reaction.example.shared_vaporize"));
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
    void catalogCopiesBothMaps() {
        var id = ResourceLocation.parse("example:custom");
        var elements = new HashMap<ResourceLocation, String>();
        var names = new HashMap<ResourceLocation, String>();
        elements.put(id, "element.example.custom");
        names.put(id, "reaction.example.custom");
        var packet = new ElementCatalogPacket(elements, names);
        elements.clear();
        names.clear();
        assertEquals("element.example.custom", packet.elements().get(id));
        assertEquals("reaction.example.custom", packet.reactionNames().get(id));
        assertThrows(UnsupportedOperationException.class, () -> packet.reactionNames().clear());
    }

    @Test
    void emptyCatalogClearsPreviousElements() {
        var id = ResourceLocation.parse("example:removed");
        assertTrue(com.elementalphase.enchantment.ElementBookCatalog.replaceClient(Map.of(id, "removed")));
        assertTrue(com.elementalphase.enchantment.ElementBookCatalog.replaceClient(Map.of()));
        assertTrue(com.elementalphase.enchantment.ElementBookCatalog.clientElements().isEmpty());
        assertFalse(com.elementalphase.enchantment.ElementBookCatalog.replaceClient(Map.of()));
    }
}
