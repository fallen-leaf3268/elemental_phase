package com.elementalphase.network;

import com.elementalphase.ElementalPhase;
import com.elementalphase.client.ClientDamagePopupReceiver;
import com.elementalphase.client.ClientFrozenStateManager;
import com.elementalphase.display.DamageDisplayPreferenceStore;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public final class ModNetwork {
    private static final String PROTOCOL_VERSION = "6";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(ElementalPhase.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);
    private static boolean registered;

    private ModNetwork() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        CHANNEL.registerMessage(0, DamagePopupPacket.class,
                DamagePopupPacket::encode,
                DamagePopupPacket::decode,
                ModNetwork::handleDamagePopup,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, DamageDisplayPreferencesPacket.class,
                DamageDisplayPreferencesPacket::encode,
                DamageDisplayPreferencesPacket::decode,
                ModNetwork::handleDamageDisplayPreferences,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(2, FrozenStateSyncPacket.class,
                FrozenStateSyncPacket::encode,
                FrozenStateSyncPacket::decode,
                ModNetwork::handleFrozenStateSync,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        registered = true;
    }

    public static void sendFrozenState(LivingEntity target, FrozenStateSyncPacket packet) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> target), packet);
    }

    public static void sendFrozenStateTo(net.minecraft.server.level.ServerPlayer player,
                                         FrozenStateSyncPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void sendDamageDisplayPreferences(boolean showReactions) {
        CHANNEL.sendToServer(new DamageDisplayPreferencesPacket(showReactions));
    }

    public static void sendDamagePopup(LivingEntity target, DamageSource source, double damage, int color,
                                       List<ResourceLocation> reactionIds) {
        if (target == null || !Double.isFinite(damage) || damage <= 0.0D) {
            return;
        }
        Vec3 anchor = target.getBoundingBox().getCenter();
        double sideOffset = Math.max(0.45D, target.getBbWidth() * 0.75D);
        DamagePopupPacket packet = new DamagePopupPacket(target.getId(), anchor.x, anchor.y, anchor.z,
                sideOffset, damage, color, reactionIds).normalizedForSending();
        if (!packet.isValid()) {
            return;
        }
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> target), packet);
    }

    private static void handleDamagePopup(DamagePopupPacket message,
                                          Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            if (message.isValid()) {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> ClientDamagePopupReceiver.receive(message));
            }
        });
        context.setPacketHandled(true);
    }

    private static void handleDamageDisplayPreferences(DamageDisplayPreferencesPacket message,
                                                       Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        var sender = context.getSender();
        if (sender != null) {
            context.enqueueWork(() -> DamageDisplayPreferenceStore.INSTANCE.update(
                    sender.getUUID(), message.showReactions()));
        }
        context.setPacketHandled(true);
    }

    private static void handleFrozenStateSync(FrozenStateSyncPacket message,
                                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            if (message.isValid()) DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> ClientFrozenStateManager.INSTANCE.receive(message));
        });
        context.setPacketHandled(true);
    }
}
