package com.elementalphase.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public record ElementCatalogPacket(Map<ResourceLocation, String> elements,
                                   Map<ResourceLocation, String> reactionNames,
                                   Map<ResourceLocation, Integer> elementColors,
                                   boolean enhancementEnabled, double baseAttachmentAmount) {
    private static final int MAX_CATALOG_ENTRIES = 16_384;

    public ElementCatalogPacket {
        if ((long) elements.size() + reactionNames.size() + elementColors.size() > MAX_CATALOG_ENTRIES) {
            throw new IllegalArgumentException("Element catalog exceeds the entry limit");
        }
        if (!elements.keySet().containsAll(elementColors.keySet())) {
            throw new IllegalArgumentException("Element colors contain unknown elements");
        }
        elements = Map.copyOf(elements);
        reactionNames = Map.copyOf(reactionNames);
        elementColors = Map.copyOf(elementColors);
        if (!Double.isFinite(baseAttachmentAmount) || baseAttachmentAmount < 0.000001D
                || baseAttachmentAmount > 1_000_000.0D) {
            throw new IllegalArgumentException("Invalid enchantment base attachment amount");
        }
    }

    public ElementCatalogPacket(Map<ResourceLocation, String> elements) {
        this(elements, Map.of(), Map.of(), false, 1.0D);
    }

    public ElementCatalogPacket(Map<ResourceLocation, String> elements,
                                Map<ResourceLocation, String> reactionNames,
                                Map<ResourceLocation, Integer> elementColors) {
        this(elements, reactionNames, elementColors, false, 1.0D);
    }

    public static void encode(ElementCatalogPacket packet, FriendlyByteBuf buffer) {
        buffer.writeMap(packet.elements(), FriendlyByteBuf::writeResourceLocation, FriendlyByteBuf::writeUtf);
        buffer.writeMap(packet.reactionNames(), FriendlyByteBuf::writeResourceLocation, FriendlyByteBuf::writeUtf);
        buffer.writeMap(packet.elementColors(), FriendlyByteBuf::writeResourceLocation, FriendlyByteBuf::writeInt);
        buffer.writeBoolean(packet.enhancementEnabled());
        buffer.writeDouble(packet.baseAttachmentAmount());
    }

    public static ElementCatalogPacket decode(FriendlyByteBuf buffer) {
        var elements = readCatalogMap(buffer, MAX_CATALOG_ENTRIES, FriendlyByteBuf::readUtf);
        var reactionNames = readCatalogMap(buffer, MAX_CATALOG_ENTRIES - elements.size(), FriendlyByteBuf::readUtf);
        var elementColors = readCatalogMap(buffer,
                MAX_CATALOG_ENTRIES - elements.size() - reactionNames.size(), FriendlyByteBuf::readInt);
        return new ElementCatalogPacket(elements, reactionNames, elementColors,
                buffer.readBoolean(), buffer.readDouble());
    }

    private static <V> Map<ResourceLocation, V> readCatalogMap(FriendlyByteBuf buffer, int remainingEntries,
                                                                Function<FriendlyByteBuf, V> readValue) {
        int count = buffer.readVarInt();
        if (count < 0 || count > remainingEntries || count > buffer.readableBytes()) {
            throw new IllegalArgumentException("Invalid element catalog entry count: " + count);
        }
        Map<ResourceLocation, V> entries = new HashMap<>();
        for (int index = 0; index < count; index++) {
            ResourceLocation id = buffer.readResourceLocation();
            if (entries.put(id, readValue.apply(buffer)) != null) {
                throw new IllegalArgumentException("Duplicate element catalog entry: " + id);
            }
        }
        return entries;
    }
}
