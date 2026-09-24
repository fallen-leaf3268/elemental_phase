package com.elementalphase.reaction.formula;

public record ReactionFormulaContext(
        double originalDamage,
        double currentDamage,
        double scale,
        double triggerAmount,
        double auraAmount,
        double consumedTrigger,
        double consumedAura,
        double remainingTrigger,
        double remainingAura,
        double attackerLevel,
        double elementStrength,
        double targetHealth,
        double targetMaxHealth,
        double targetHealthRatio,
        double targetResistance,
        double distance,
        double radius) {

    public static ReactionFormulaContext legacy(double originalDamage, double scale) {
        return new ReactionFormulaContext(originalDamage, originalDamage, scale,
                0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D,
                0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
    }

    public static ReactionFormulaContext stateDamage(double scale, double attackerLevel) {
        return new ReactionFormulaContext(0.0D, 0.0D, scale,
                0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D,
                attackerLevel, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
    }
}
