package com.elementalphase.client;

import com.elementalphase.network.DamagePopupPacket;
import com.elementalphase.config.ElementalPhaseClientConfig;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.IntSupplier;

public final class DamagePopupManager {
    static final double LIFETIME_TICKS = 35.0D;
    static final double FADE_TICKS = 6.0D;
    static final int MAX_POPUPS = 256;
    public static final DamagePopupManager INSTANCE = new DamagePopupManager(() -> ElementalPhaseClientConfig.MAX_POPUPS.get());
    private static final double BASE_HORIZONTAL_OFFSET_PIXELS = 14.0D;
    private static final double RISE_PIXELS = 18.0D;
    private static final double LANE_VERTICAL_OFFSET_PIXELS = 3.0D;
    private static final double[] LANE_HORIZONTAL_OFFSETS_PIXELS = {-6.0D, 6.0D, -12.0D, 12.0D, -18.0D, 18.0D};

    private final Deque<Popup> popups = new ArrayDeque<>();
    private final IntSupplier maxPopups;
    private long sequence;
    private Object worldToken;

    public DamagePopupManager() {
        this(() -> MAX_POPUPS);
    }

    public DamagePopupManager(IntSupplier maxPopups) {
        this.maxPopups = maxPopups;
    }

    public synchronized void add(DamagePopupPacket packet, Component text, int textWidth, double now) {
        if (packet == null || !packet.isValid() || text == null || textWidth < 0 || !Double.isFinite(now)) {
            return;
        }
        int maximum = Math.max(0, Math.min(MAX_POPUPS, maxPopups.getAsInt()));
        trimTo(maximum);
        if (maximum == 0) {
            return;
        }
        while (popups.size() >= maximum) {
            popups.removeFirst();
        }
        int lane = (int) Math.floorMod(sequence++, LANE_HORIZONTAL_OFFSETS_PIXELS.length);
        popups.addLast(new Popup(packet, text, textWidth, now, LANE_HORIZONTAL_OFFSETS_PIXELS[lane],
                (lane % 3) * LANE_VERTICAL_OFFSET_PIXELS));
    }

    public synchronized boolean forEachActive(double now, ActivePopupVisitor visitor) {
        if (!Double.isFinite(now)) {
            return false;
        }
        trimTo(Math.max(0, Math.min(MAX_POPUPS, maxPopups.getAsInt())));
        boolean active = false;
        var iterator = popups.iterator();
        while (iterator.hasNext()) {
            Popup popup = iterator.next();
            if (now - popup.createdAt() >= LIFETIME_TICKS) {
                iterator.remove();
                continue;
            }
            double age = Math.max(0.0D, now - popup.createdAt());
            double progress = Math.min(1.0D, age / LIFETIME_TICKS);
            double verticalPixels = popup.verticalOffset() + progress * RISE_PIXELS;
            visitor.visit(popup, BASE_HORIZONTAL_OFFSET_PIXELS + popup.horizontalOffset(),
                    verticalPixels == 0.0D ? 0.0D : -verticalPixels, alphaForAge(age));
            active = true;
        }
        return active;
    }

    private void trimTo(int maximum) {
        while (popups.size() > maximum) {
            popups.removeFirst();
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
        void visit(Popup popup, double y, double sideOffset, int alpha);
    }

    public record Popup(DamagePopupPacket packet, Component text, int textWidth, double createdAt,
                        double horizontalOffset, double verticalOffset) {
    }
}
