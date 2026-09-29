package com.elementalphase.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

public record ElementCatalogPacket(Map<ResourceLocation, String> elements,
                                   Map<ResourceLocation, String> reactionNames,
                                   Map<ResourceLocation, Integer> elementColors,
                                   boolean enhancementEnabled, double baseAttachmentAmount) {
    public ElementCatalogPacket {
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
        return new ElementCatalogPacket(
                buffer.readMap(FriendlyByteBuf::readResourceLocation, FriendlyByteBuf::readUtf),
                buffer.readMap(FriendlyByteBuf::readResourceLocation, FriendlyByteBuf::readUtf),
                buffer.readMap(FriendlyByteBuf::readResourceLocation, FriendlyByteBuf::readInt),
                buffer.readBoolean(), buffer.readDouble());
    }
}
