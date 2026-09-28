package com.elementalphase.data.model;

public record ElementAttachmentPolicy(Mode mode, int cooldownTicks, int durationTicks,
                                      double maxAmount) {
    public static final ElementAttachmentPolicy DEFAULT = new ElementAttachmentPolicy(Mode.NORMAL, 2, 100, 1_000_000.0D);

    public ElementAttachmentPolicy {
        if (mode == null || cooldownTicks < 0 || durationTicks < 1 || !Double.isFinite(maxAmount)
                || maxAmount < 0.1D || maxAmount > 1_000_000.0D) {
            throw new IllegalArgumentException("Invalid element attachment policy");
        }
    }

    public boolean virtual() {
        return mode == Mode.VIRTUAL;
    }

    public int limitDuration(int duration) {
        return virtual() ? Math.min(duration, durationTicks) : duration;
    }

    public enum Mode {
        NORMAL, VIRTUAL
    }
}
