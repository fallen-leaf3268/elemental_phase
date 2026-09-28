package com.elementalphase.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrozenStateSyncPacketTest {
    @Test
    void roundTripsFrozenAndClearPackets() {
        assertEquals(new FrozenStateSyncPacket(7, true, 100, 80), roundTrip(
                new FrozenStateSyncPacket(7, true, 100, 80)));
        assertEquals(new FrozenStateSyncPacket(7, false, 0, 0), roundTrip(
                new FrozenStateSyncPacket(7, false, 0, 0)));
        assertEquals(new FrozenStateSyncPacket(7, true, Integer.MAX_VALUE, Integer.MAX_VALUE), roundTrip(
                new FrozenStateSyncPacket(7, true, Integer.MAX_VALUE, Integer.MAX_VALUE)));
    }

    @Test
    void rejectsInvalidBoundsAndRelationships() {
        assertFalse(new FrozenStateSyncPacket(-1, true, 100, 80).isValid());
        assertFalse(new FrozenStateSyncPacket(1, true, 100, 101).isValid());
        assertFalse(new FrozenStateSyncPacket(1, true, -1, 0).isValid());
        assertFalse(new FrozenStateSyncPacket(1, false, 1, 0).isValid());
    }

    @Test
    void decodingOutOfRangeValuesReturnsInvalidPacket() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(1);
        buffer.writeBoolean(true);
        buffer.writeVarInt(-1);
        buffer.writeVarInt(0);

        assertFalse(FrozenStateSyncPacket.decode(buffer).isValid());
    }

    private static FrozenStateSyncPacket roundTrip(FrozenStateSyncPacket packet) {
        assertTrue(packet.isValid());
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        FrozenStateSyncPacket.encode(packet, buffer);
        return FrozenStateSyncPacket.decode(buffer);
    }
}
