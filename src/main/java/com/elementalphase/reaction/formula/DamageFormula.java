package com.elementalphase.reaction.formula;

@FunctionalInterface
public interface DamageFormula {
    double evaluate(double originalDamage, double scale);
}
