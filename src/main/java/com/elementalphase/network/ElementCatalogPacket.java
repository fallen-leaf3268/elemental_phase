package com.elementalphase.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

public record ElementCatalogPacket(Map<ResourceLocation, String> elements,
                                   Map<ResourceLocation, String> reactionNames) {
    public ElementCatalogPacket {
        elements = Map.copyOf(elements);
        reactionNames = Map.copyOf(reactionNames);
    }

    public ElementCatalogPacket(Map<ResourceLocation, String> elements) {
        this(elements, Map.of());
    }

    public static void encode(ElementCatalogPacket packet, FriendlyByteBuf buffer) {
        buffer.writeMap(packet.elements(), FriendlyByteBuf::writeResourceLocation, FriendlyByteBuf::writeUtf);
        buffer.writeMap(packet.reactionNames(), FriendlyByteBuf::writeResourceLocation, FriendlyByteBuf::writeUtf);
    }

    public static ElementCatalogPacket decode(FriendlyByteBuf buffer) {
        return new ElementCatalogPacket(
                buffer.readMap(FriendlyByteBuf::readResourceLocation, FriendlyByteBuf::readUtf),
                buffer.readMap(FriendlyByteBuf::readResourceLocation, FriendlyByteBuf::readUtf));
    }
}
