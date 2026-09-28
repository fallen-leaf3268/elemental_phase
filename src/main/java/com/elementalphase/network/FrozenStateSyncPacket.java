package com.elementalphase.network;

import net.minecraft.network.FriendlyByteBuf;

public record FrozenStateSyncPacket(int entityId, boolean frozen, int durationTicks,
                                    int remainingTicks, boolean valid) {

    public FrozenStateSyncPacket(int entityId, boolean frozen, int durationTicks, int remainingTicks) {
        this(entityId, frozen, durationTicks, remainingTicks, true);
    }

    public static void encode(FrozenStateSyncPacket message, FriendlyByteBuf buffer) {
        if (!message.isValid()) throw new IllegalArgumentException("Invalid frozen state packet");
        buffer.writeVarInt(message.entityId());
        buffer.writeBoolean(message.frozen());
        buffer.writeVarInt(message.durationTicks());
        buffer.writeVarInt(message.remainingTicks());
    }

    public static FrozenStateSyncPacket decode(FriendlyByteBuf buffer) {
        int entityId = buffer.readVarInt();
        boolean frozen = buffer.readBoolean();
        int durationTicks = buffer.readVarInt();
        int remainingTicks = buffer.readVarInt();
        FrozenStateSyncPacket packet = new FrozenStateSyncPacket(entityId, frozen, durationTicks, remainingTicks);
        return packet.isValid() ? packet : new FrozenStateSyncPacket(-1, false, 0, 0, false);
    }

    public boolean isValid() {
        if (!valid || entityId < 0 || durationTicks < 0
                || remainingTicks < 0 || remainingTicks > durationTicks) return false;
        return frozen ? durationTicks > 0 : durationTicks == 0 && remainingTicks == 0;
    }
}
