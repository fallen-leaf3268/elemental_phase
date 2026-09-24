package com.elementalphase.reaction.formula;

@FunctionalInterface
public interface ReactionFormula {
    double evaluate(ReactionFormulaContext context);
}
