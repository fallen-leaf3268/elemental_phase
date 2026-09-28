package com.elementalphase.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record DamagePopupPacket(int entityId, double x, double y, double z, double sideOffset, double damage,
                                int color, List<ResourceLocation> reactionIds) {
    public static final int MAX_REACTION_IDS = 16;

    public DamagePopupPacket {
        reactionIds = reactionIds == null ? null : Collections.unmodifiableList(new ArrayList<>(reactionIds));
    }

    public static void encode(DamagePopupPacket message, FriendlyByteBuf buffer) {
        if (!message.isValid()) {
            throw new IllegalArgumentException("Cannot encode invalid damage popup packet");
        }
        buffer.writeVarInt(message.entityId());
        buffer.writeDouble(message.x());
        buffer.writeDouble(message.y());
        buffer.writeDouble(message.z());
        buffer.writeDouble(message.sideOffset());
        buffer.writeDouble(message.damage());
        buffer.writeInt(message.color());
        buffer.writeVarInt(message.reactionIds().size());
        for (ResourceLocation reactionId : message.reactionIds()) {
            buffer.writeResourceLocation(reactionId);
        }
    }

    public DamagePopupPacket normalizedForSending() {
        if (reactionIds != null && reactionIds.size() > MAX_REACTION_IDS) {
            return new DamagePopupPacket(entityId, x, y, z, sideOffset, damage, color,
                    reactionIds.subList(0, MAX_REACTION_IDS));
        }
        return this;
    }

    public static DamagePopupPacket decode(FriendlyByteBuf buffer) {
        int entityId = buffer.readVarInt();
        double x = buffer.readDouble();
        double y = buffer.readDouble();
        double z = buffer.readDouble();
        double sideOffset = buffer.readDouble();
        double damage = buffer.readDouble();
        int color = buffer.readInt();
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_REACTION_IDS) {
            throw new IllegalArgumentException("Invalid damage popup reaction id count: " + size);
        }
        List<ResourceLocation> reactionIds = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            reactionIds.add(buffer.readResourceLocation());
        }
        return new DamagePopupPacket(entityId, x, y, z, sideOffset, damage, color, reactionIds);
    }

    public boolean shouldDisplay() {
        return isValid() && damage > 0.1F;
    }

    public boolean isValid() {
        if (entityId < 0 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(sideOffset) || sideOffset < 0.0D
                || !Double.isFinite(damage) || damage <= 0.0D
                || color < 0 || color > 0xFFFFFF
                || reactionIds == null || reactionIds.size() > MAX_REACTION_IDS) {
            return false;
        }
        for (ResourceLocation reactionId : reactionIds) {
            if (reactionId == null || reactionId.getNamespace().isEmpty() || reactionId.getPath().isEmpty()) {
                return false;
            }
        }
        return true;
    }

}
