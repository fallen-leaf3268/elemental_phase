package com.elementalphase.client;

import com.elementalphase.ElementalPhase;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = ElementalPhase.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FrozenReactionRenderer {
    private FrozenReactionRenderer() {
    }

    @SubscribeEvent
    public static void render(RenderLivingEvent.Post<?, ?> event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        ClientFrozenStateManager.INSTANCE.updateWorldToken(minecraft.level);
        if (event.getEntity() == minecraft.player && minecraft.options.getCameraType().isFirstPerson()) return;
        long now = minecraft.level.getGameTime();
        var state = ClientFrozenStateManager.INSTANCE.state(event.getEntity().getId(), now);
        if (state == null || !state.active(now)) return;
        FrostShellRenderer.render(event.getEntity(), event.getPoseStack(), event.getMultiBufferSource(),
                event.getPackedLight());
    }

    @SubscribeEvent
    public static void constrainLocalPlayer(TickEvent.PlayerTickEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.phase != TickEvent.Phase.END || !event.player.level().isClientSide()
                || event.player != minecraft.player) return;
        long now = event.player.level().getGameTime();
        ClientFrozenStateManager.INSTANCE.cleanup(event.player.level(), now);
        var state = ClientFrozenStateManager.INSTANCE.state(event.player.getId(), now);
        if (state != null && state.active(now)) {
            var movement = event.player.getDeltaMovement();
            event.player.setDeltaMovement(0.0D, movement.y, 0.0D);
        }
    }
}
