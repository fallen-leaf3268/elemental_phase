package com.elementalphase.client;

import com.elementalphase.ElementalPhase;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = ElementalPhase.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FrozenReactionRenderer {
    private static final ThreadLocal<Deque<RenderOffsetFrame>> RENDER_OFFSETS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final Int2ObjectOpenHashMap<VisualSample> VISUALS = new Int2ObjectOpenHashMap<>();

    private FrozenReactionRenderer() {
    }

    @SubscribeEvent
    public static void render(RenderLivingEvent.Post<?, ?> event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return;
        ClientFrozenStateManager.INSTANCE.updateWorldToken(minecraft.level);
        if (event.getEntity() == minecraft.player && minecraft.options.getCameraType().isFirstPerson()) return;
        if (!event.getEntity().isAlive() || event.getEntity().isInvisibleTo(minecraft.player)) return;
        long now = minecraft.level.getGameTime();
        var state = ClientFrozenStateManager.INSTANCE.state(event.getEntity().getId(), now);
        if (state == null || !state.visible(now)) return;
        double visualTime = now + event.getPartialTick();
        var entity = event.getEntity();
        var bounds = entity.getBoundingBox().move(-entity.getX(), -entity.getY(), -entity.getZ());
        var anchors = FrostShellRenderer.render(bounds, event.getPoseStack(),
                event.getMultiBufferSource(), event.getPackedLight(), state.growth(visualTime), state.opacity(visualTime),
                renderOffset(entity));
        observe(event.getEntity().getId(), event.getEntity().getUUID(), state.startedAt(), now, anchors);
    }

    public static void beginRender(Entity entity) {
        RENDER_OFFSETS.get().push(new RenderOffsetFrame(entity, Vec3.ZERO));
    }

    public static Vec3 captureRenderOffset(Vec3 offset, Entity entity) {
        var frames = RENDER_OFFSETS.get();
        var frame = frames.peek();
        if (frame != null && frame.entity() == entity) {
            frames.pop();
            frames.push(new RenderOffsetFrame(entity, offset));
        }
        return offset;
    }

    public static void endRender(Entity entity) {
        var frames = RENDER_OFFSETS.get();
        while (!frames.isEmpty()) {
            if (frames.pop().entity() == entity) break;
        }
        if (frames.isEmpty()) RENDER_OFFSETS.remove();
    }

    static Vec3 renderOffset(Entity entity) {
        var frame = RENDER_OFFSETS.get().peek();
        return frame != null && frame.entity() == entity ? frame.offset() : Vec3.ZERO;
    }

    private record RenderOffsetFrame(Entity entity, Vec3 offset) {
    }

    static void observe(int entityId, UUID identity, long startedAt, long now, List<FrostShellRenderer.Point> anchors) {
        if (anchors.isEmpty()) {
            VISUALS.remove(entityId);
            return;
        }
        var sample = VISUALS.get(entityId);
        if (sample == null || !sample.identity.equals(identity) || sample.startedAt != startedAt) {
            if (sample == null && VISUALS.size() >= 256) return;
            sample = new VisualSample(identity, startedAt);
            VISUALS.put(entityId, sample);
        }
        sample.lastSeen = now;
        sample.anchors = List.copyOf(anchors);
    }

    public static void clearVisuals() {
        VISUALS.clear();
        RENDER_OFFSETS.remove();
    }

    @SubscribeEvent
    public static void tickParticles(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var minecraft = Minecraft.getInstance();
        ClientFrozenStateManager.INSTANCE.updateWorldToken(minecraft.level);
        if (minecraft.level == null || minecraft.player == null || minecraft.isPaused()) return;
        long now = minecraft.level.getGameTime();
        var camera = minecraft.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) return;
        var setting = minecraft.options.particles().get();
        int budget = setting == ParticleStatus.MINIMAL ? 0 : setting == ParticleStatus.DECREASED ? 12 : 32;
        var iterator = VISUALS.int2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var sample = entry.getValue();
            var entity = minecraft.level.getEntity(entry.getIntKey());
            var state = ClientFrozenStateManager.INSTANCE.state(entry.getIntKey(), now);
            if (!(entity instanceof LivingEntity living) || !living.isAlive() || !sample.identity.equals(entity.getUUID())
                    || state == null || sample.startedAt != state.startedAt() || now - sample.lastSeen > 3
                    || camera.getPosition().distanceToSqr(entity.position()) > 32 * 32
                    || living.isInvisibleTo(minecraft.player)
                    || entity == minecraft.player && minecraft.options.getCameraType().isFirstPerson()) {
                iterator.remove();
                continue;
            }
            int events = sample.clock.step(state, now);
            if (events != 0 && budget > 0) budget -= spawnParticles(minecraft, living, sample, now, budget);
        }
    }

    private static int spawnParticles(Minecraft minecraft, LivingEntity entity, VisualSample sample,
                                      long now, int budget) {
        var random = new Random(entity.getId() * 0x27D4EB2DL ^ now);
        int count = Math.min(budget, 3);
        var type = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultBlockState());
        for (int index = 0; index < count; index++) {
            var anchor = sample.anchors.get(index * sample.anchors.size() / count);
            double x = entity.getX() + anchor.x();
            double y = entity.getY() + anchor.y();
            double z = entity.getZ() + anchor.z();
            double speed = 0.035;
            double vx = (random.nextDouble() - 0.5) * speed;
            double vy = 0.015 + random.nextDouble() * 0.015;
            double vz = (random.nextDouble() - 0.5) * speed;
            var particle = minecraft.particleEngine.createParticle(type, x, y, z, vx, vy, vz);
            if (particle != null) {
                particle.setParticleSpeed(vx, vy, vz);
                particle.setColor(0.90F, 0.97F, 1.0F);
                particle.scale(0.35F);
                particle.setLifetime(10);
            }
        }
        return count;
    }

    static final class ParticleClock {
        private long lastTick = Long.MIN_VALUE;
        private boolean observedFreeze;
        private boolean thawed;

        int step(ClientFrozenStateManager.ClientFrozenState state, long now) {
            if (lastTick == now) return 0;
            lastTick = now;
            if (state.active(now)) {
                observedFreeze = true;
                return 0;
            }
            if (observedFreeze && !thawed && state.visible(now)) {
                thawed = true;
                return 4;
            }
            return 0;
        }
    }

    private static final class VisualSample {
        private final UUID identity;
        private final long startedAt;
        private final ParticleClock clock = new ParticleClock();
        private long lastSeen;
        private List<FrostShellRenderer.Point> anchors = List.of();

        private VisualSample(UUID identity, long startedAt) {
            this.identity = identity;
            this.startedAt = startedAt;
        }
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
