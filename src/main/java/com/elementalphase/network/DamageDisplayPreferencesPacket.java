package com.elementalphase.network;

import net.minecraft.network.FriendlyByteBuf;

public record DamageDisplayPreferencesPacket(boolean showReactions) {
    public static void encode(DamageDisplayPreferencesPacket message, FriendlyByteBuf buffer) {
        buffer.writeBoolean(message.showReactions());
    }

    public static DamageDisplayPreferencesPacket decode(FriendlyByteBuf buffer) {
        return new DamageDisplayPreferencesPacket(buffer.readBoolean());
    }
}
