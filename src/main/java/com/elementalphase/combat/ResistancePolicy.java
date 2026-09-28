package com.elementalphase.combat;

public final class ResistancePolicy {
    public static final double MIN_RESISTANCE = -2147483647.0D;
    public static final double MAX_RESISTANCE = 1.0D;
    private static final double MAX_DAMAGE = 1_000_000.0D;

    private ResistancePolicy() {
    }

    public static boolean isValid(double resistance) {
        return Double.isFinite(resistance) && resistance >= MIN_RESISTANCE && resistance <= MAX_RESISTANCE;
    }

    public static double clamp(double resistance) {
        return Double.isFinite(resistance)
                ? Math.max(MIN_RESISTANCE, Math.min(MAX_RESISTANCE, resistance)) : 0.0D;
    }

    public static double apply(double damage, double resistance) {
        return apply(damage, resistance, 0.0D);
    }

    public static double apply(double damage, double elementResistance, double reactionResistance) {
        if (!Double.isFinite(damage) || damage <= 0.0D) return 0.0D;
        double elementMultiplier = Math.max(0.0D, 1.0D - clamp(elementResistance));
        double reactionMultiplier = Math.max(0.0D, 1.0D - clamp(reactionResistance));
        if (elementMultiplier == 0.0D || reactionMultiplier == 0.0D) return 0.0D;
        return Math.min(MAX_DAMAGE, damage * elementMultiplier * reactionMultiplier);
    }
}
