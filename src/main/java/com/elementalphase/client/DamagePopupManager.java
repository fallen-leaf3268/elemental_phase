package com.elementalphase.client;

import com.elementalphase.network.DamagePopupPacket;
import com.elementalphase.config.ElementalPhaseClientConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.SplittableRandom;
import java.util.function.IntSupplier;

public final class DamagePopupManager {
    static final double LIFETIME_TICKS = 35.0D;
    static final double FADE_TICKS = 6.0D;
    static final int MAX_POPUPS = 256;
    public static final DamagePopupManager INSTANCE = new DamagePopupManager(
            () -> ElementalPhaseClientConfig.MAX_POPUPS.get(),
            () -> ElementalPhaseClientConfig.MAX_POPUPS_PER_ENTITY.get());
    private static final double RISE_PIXELS = 18.0D;
    private static final double HORIZONTAL_SPAWN_PIXELS = 6.0D;
    private static final double VERTICAL_SPAWN_PIXELS = 24.0D;
    private static final double HORIZONTAL_DRIFT_PIXELS = 4.0D;
    private static final double WOBBLE_PIXELS = 1.0D;

    private final Deque<Popup> popups = new ArrayDeque<>();
    private final IntSupplier maxPopups;
    private final IntSupplier maxPopupsPerEntity;
    private long sequence;
    private Object worldToken;

    public DamagePopupManager() {
        this(() -> MAX_POPUPS);
    }

    public DamagePopupManager(IntSupplier maxPopups) {
        this(maxPopups, () -> 9);
    }

    public DamagePopupManager(IntSupplier maxPopups, IntSupplier maxPopupsPerEntity) {
        this.maxPopups = maxPopups;
        this.maxPopupsPerEntity = maxPopupsPerEntity;
    }

    public synchronized void add(DamagePopupPacket packet, Component text, int textWidth, double now,
                                 AABB bounds, double heightRatio) {
        if (packet == null || !packet.shouldDisplay() || text == null || textWidth < 0 || !Double.isFinite(now)
                || bounds == null || !Double.isFinite(heightRatio)) {
            return;
        }
        int maximum = Math.max(0, Math.min(MAX_POPUPS, maxPopups.getAsInt()));
        int maximumPerEntity = Math.max(0, Math.min(MAX_POPUPS, maxPopupsPerEntity.getAsInt()));
        trimTo(maximum, maximumPerEntity, now);
        if (maximum == 0) {
            return;
        }
        int sameEntityCount = 0;
        for (Popup existing : popups) {
            if (existing.packet().entityId() == packet.entityId()) {
                sameEntityCount++;
            }
        }
        while (maximumPerEntity > 0 && sameEntityCount >= maximumPerEntity) {
            removeOldestForEntity(packet.entityId());
            sameEntityCount--;
        }
        while (popups.size() >= maximum) {
            popups.removeFirst();
        }
        long id = sequence++;
        var random = new SplittableRandom(id ^ ((long) packet.entityId() << 32));
        double horizontalOffset = random.nextDouble(-HORIZONTAL_SPAWN_PIXELS, HORIZONTAL_SPAWN_PIXELS);
        double verticalOffset = -random.nextDouble(VERTICAL_SPAWN_PIXELS);
        double horizontalDrift = random.nextDouble(-HORIZONTAL_DRIFT_PIXELS, HORIZONTAL_DRIFT_PIXELS);
        double wobblePhase = random.nextDouble(Math.PI * 2.0D);
        Vec3 center = bounds.getCenter();
        AABB savedBounds = bounds.move(packet.x() - center.x(), packet.y() - center.y(), packet.z() - center.z());
        Vec3 anchor = new Vec3(packet.x(), DamagePopupPlacement.anchorY(savedBounds, heightRatio), packet.z());
        popups.addLast(new Popup(id, packet, text, textWidth, now, horizontalOffset,
                verticalOffset, horizontalDrift, wobblePhase, anchor, savedBounds));
    }

    public synchronized boolean forEachActive(double now, ActivePopupVisitor visitor) {
        if (!Double.isFinite(now)) {
            return false;
        }
        trimTo(Math.max(0, Math.min(MAX_POPUPS, maxPopups.getAsInt())),
                Math.max(0, Math.min(MAX_POPUPS, maxPopupsPerEntity.getAsInt())), now);
        boolean active = false;
        var iterator = popups.iterator();
        while (iterator.hasNext()) {
            Popup popup = iterator.next();
            double age = Math.max(0.0D, now - popup.createdAt());
            double progress = Math.min(1.0D, age / LIFETIME_TICKS);
            double verticalPixels = popup.verticalOffset() + progress * RISE_PIXELS;
            double horizontalPixels = popup.horizontalOffset() + popup.horizontalDrift() * progress
                    + (Math.sin(popup.wobblePhase() + progress * Math.PI * 2.0D)
                    - Math.sin(popup.wobblePhase())) * WOBBLE_PIXELS;
            visitor.visit(popup, horizontalPixels,
                    verticalPixels == 0.0D ? 0.0D : -verticalPixels, alphaForAge(age));
            active = true;
        }
        return active;
    }

    private void trimTo(int maximum, int maximumPerEntity, double now) {
        popups.removeIf(popup -> now - popup.createdAt() >= LIFETIME_TICKS);
        if (maximumPerEntity > 0) {
            var counts = new HashMap<Integer, Integer>();
            var iterator = popups.descendingIterator();
            while (iterator.hasNext()) {
                Popup popup = iterator.next();
                if (counts.merge(popup.packet().entityId(), 1, Integer::sum) > maximumPerEntity) {
                    iterator.remove();
                }
            }
        }
        while (popups.size() > maximum) {
            popups.removeFirst();
        }
    }

    private void removeOldestForEntity(int entityId) {
        var iterator = popups.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().packet().entityId() == entityId) {
                iterator.remove();
                return;
            }
        }
    }

    public synchronized void clear() {
        popups.clear();
        sequence = 0L;
    }

    public synchronized void updateWorldToken(Object token) {
        if (worldToken != token) {
            clear();
            worldToken = token;
        }
    }

    private static int alphaForAge(double age) {
        if (age <= LIFETIME_TICKS - FADE_TICKS) {
            return 255;
        }
        double remaining = Math.max(0.0D, LIFETIME_TICKS - age);
        return Math.max(0, (int) Math.round(255.0D * remaining / FADE_TICKS));
    }

    @FunctionalInterface
    public interface ActivePopupVisitor {
        void visit(Popup popup, double offsetX, double offsetY, int alpha);
    }

    public record Popup(long id, DamagePopupPacket packet, Component text, int textWidth, double createdAt,
                        double horizontalOffset, double verticalOffset, double horizontalDrift, double wobblePhase,
                        Vec3 anchor, AABB bounds) {
    }
}
