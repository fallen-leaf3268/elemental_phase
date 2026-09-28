package com.elementalphase.client;

import com.elementalphase.ElementalPhase;
import com.elementalphase.config.ElementalPhaseClientConfig;
import com.elementalphase.network.ModNetwork;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.particle.CritParticle;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import org.joml.Matrix4f;

import java.util.Iterator;

@Mod.EventBusSubscriber(modid = ElementalPhase.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientDamagePopupRenderer {
    private static final String HUD_OVERLAY_ID = "damage_popups";
    private static final Long2ObjectOpenHashMap<OcclusionSample> OCCLUSION_CACHE = new Long2ObjectOpenHashMap<>();
    private static Matrix4f projectionMatrix;
    private static Object occlusionWorldToken;
    private static double lastOcclusionCleanup = Double.NEGATIVE_INFINITY;

    private ClientDamagePopupRenderer() {
    }

    @SubscribeEvent
    public static void captureProjection(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        projectionMatrix = new Matrix4f(event.getProjectionMatrix());
    }

    private static void renderOverlay(Gui gui, GuiGraphics graphics, float partialTick,
                                      int screenWidth, int screenHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        DamagePopupManager.INSTANCE.updateWorldToken(minecraft.level);
        if (minecraft.level == null || minecraft.player == null || projectionMatrix == null) {
            return;
        }
        if (occlusionWorldToken != minecraft.level) {
            OCCLUSION_CACHE.clear();
            occlusionWorldToken = minecraft.level;
            lastOcclusionCleanup = Double.NEGATIVE_INFINITY;
        }
        double now = minecraft.level.getGameTime() + partialTick;
        cleanupOcclusionCache(now);
        var camera = minecraft.gameRenderer.getMainCamera();
        if (camera == null || !camera.isInitialized()) {
            return;
        }
        var cameraPosition = camera.getPosition();
        var cameraLeft = camera.getLeftVector();
        var cameraUp = camera.getUpVector();
        var cameraForward = camera.getLookVector();
        boolean firstPerson = minecraft.options.getCameraType().isFirstPerson();
        int localPlayerId = minecraft.player.getId();
        double maxDistance = ElementalPhaseClientConfig.MAX_DISTANCE.get();
        double maxDistanceSquared = maxDistance * maxDistance;
        float fontScale = ElementalPhaseClientConfig.FONT_SCALE.get().floatValue();
        var projected = new DamagePopupPlacement.ScreenPoint();
        DamagePopupManager.INSTANCE.forEachActive(now, (popup, offsetX, offsetY, alpha) -> {
            var packet = popup.packet();
            int entityId = packet.entityId();
            if (!DamagePopupVisibility.shouldRender(entityId, localPlayerId, firstPerson)
                    || !DamagePopupVisibility.shouldRenderAlpha(alpha)) {
                return;
            }
            Vec3 anchor = popup.anchor();
            double distanceSquared = anchor.distanceToSqr(cameraPosition);
            if (!Double.isFinite(distanceSquared) || distanceSquared > maxDistanceSquared
                    || !DamagePopupPlacement.projectToScreen(
                    anchor.x(), anchor.y(), anchor.z(),
                    cameraPosition.x(), cameraPosition.y(), cameraPosition.z(),
                    cameraLeft.x(), cameraLeft.y(), cameraLeft.z(),
                    cameraUp.x(), cameraUp.y(), cameraUp.z(),
                    cameraForward.x(), cameraForward.y(), cameraForward.z(),
                    projectionMatrix, screenWidth, screenHeight, projected)) {
                return;
            }
            if (isBlocked(popup.id(), now, minecraft.level, cameraPosition, popup.bounds())) {
                return;
            }
            double renderX = projected.x() + offsetX;
            double renderY = projected.y() + offsetY;
            double halfWidth = popup.textWidth() * fontScale * 0.5D;
            double height = minecraft.font.lineHeight * fontScale;
            if (renderX + halfWidth < 0.0D || renderX - halfWidth > screenWidth
                    || renderY + height < 0.0D || renderY > screenHeight) {
                return;
            }
            var pose = graphics.pose();
            pose.pushPose();
            pose.translate(renderX, renderY, 0.0D);
            pose.scale(fontScale, fontScale, 1.0F);
            int textX = -popup.textWidth() / 2;
            int alphaColor = alpha << 24;
            int darkColor = darkColor(packet.color()) | alphaColor;
            graphics.drawString(minecraft.font, popup.text(), textX + 1, 1, darkColor, false);
            graphics.drawString(minecraft.font, popup.text(), textX, 0, packet.color() | alphaColor, false);
            pose.popPose();
        });
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientFrozenStateManager.INSTANCE.clear();
        DamagePopupManager.INSTANCE.clear();
        DamagePopupManager.INSTANCE.updateWorldToken(null);
        OCCLUSION_CACHE.clear();
        occlusionWorldToken = null;
        projectionMatrix = null;
        lastOcclusionCleanup = Double.NEGATIVE_INFINITY;
    }

    @SubscribeEvent
    public static void login(ClientPlayerNetworkEvent.LoggingIn event) {
        sendDisplayPreferences();
    }

    private static void sendDisplayPreferences() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() != null) {
            ModNetwork.sendDamageDisplayPreferences(ElementalPhaseClientConfig.SHOW_REACTIONS.get());
        }
    }

    private static boolean isBlocked(long popupId, double now, Level level,
                                     Vec3 cameraPosition, AABB bounds) {
        OcclusionSample sample = OCCLUSION_CACHE.get(popupId);
        if (sample == null || DamagePopupOcclusion.shouldRefresh(
                sample.checkedAt, now, sample.cameraX, sample.cameraY, sample.cameraZ,
                cameraPosition.x(), cameraPosition.y(), cameraPosition.z(), sample.bounds, bounds)) {
            boolean blocked = DamagePopupOcclusion.isFullyBlocked(bounds,
                    point -> hasOpaqueBlockBetween(level, cameraPosition, point));
            sample = new OcclusionSample(now, cameraPosition.x(), cameraPosition.y(), cameraPosition.z(), bounds, blocked);
            OCCLUSION_CACHE.put(popupId, sample);
        }
        return sample.blocked;
    }

    private static boolean hasOpaqueBlockBetween(Level level, Vec3 from, Vec3 to) {
        if (from.distanceToSqr(to) <= 0.000001D) {
            return false;
        }
        return BlockGetter.traverseBlocks(from, to, level,
                (world, blockPos) -> world.getBlockState(blockPos).isSolidRender(world, blockPos)
                        ? Boolean.TRUE : null,
                world -> Boolean.FALSE);
    }

    private static void cleanupOcclusionCache(double now) {
        if (now - lastOcclusionCleanup < 20.0D) {
            return;
        }
        lastOcclusionCleanup = now;
        Iterator<OcclusionSample> iterator = OCCLUSION_CACHE.values().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().checkedAt > DamagePopupManager.LIFETIME_TICKS + 4.0D) {
                iterator.remove();
            }
        }
    }

    private static final class OcclusionSample {
        private final double checkedAt;
        private final double cameraX;
        private final double cameraY;
        private final double cameraZ;
        private final AABB bounds;
        private final boolean blocked;

        private OcclusionSample(double checkedAt, double cameraX, double cameraY, double cameraZ,
                                AABB bounds, boolean blocked) {
            this.checkedAt = checkedAt;
            this.cameraX = cameraX;
            this.cameraY = cameraY;
            this.cameraZ = cameraZ;
            this.bounds = bounds;
            this.blocked = blocked;
        }
    }

    @Mod.EventBusSubscriber(modid = ElementalPhase.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ClientModEvents {
        private ClientModEvents() {
        }

        @SubscribeEvent
        public static void registerOverlays(RegisterGuiOverlaysEvent event) {
            event.registerAboveAll(HUD_OVERLAY_ID, ClientDamagePopupRenderer::renderOverlay);
        }

        @SubscribeEvent
        public static void registerParticleProviders(RegisterParticleProvidersEvent event) {
            event.registerSpriteSet(ParticleTypes.DAMAGE_INDICATOR, sprites ->
                    new DamageIndicatorParticleProvider(
                            new CritParticle.DamageIndicatorProvider(sprites),
                            ElementalPhaseClientConfig.DISABLE_VANILLA_DAMAGE_INDICATOR::get));
        }

        @SubscribeEvent
        public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((ResourceManagerReloadListener) manager -> {
                ClientFrozenStateManager.INSTANCE.clear();
                DamagePopupManager.INSTANCE.clear();
                OCCLUSION_CACHE.clear();
                lastOcclusionCleanup = Double.NEGATIVE_INFINITY;
            });
        }

        @SubscribeEvent
        public static void reloadConfig(ModConfigEvent.Reloading event) {
            if (event.getConfig().getSpec() == ElementalPhaseClientConfig.SPEC) {
                sendDisplayPreferences();
            }
        }
    }

    private static int darkColor(int color) {
        int red = ((color >> 16) & 0xFF) / 4;
        int green = ((color >> 8) & 0xFF) / 4;
        int blue = (color & 0xFF) / 4;
        return red << 16 | green << 8 | blue;
    }
}
