package com.elementalphase.combat;

public final class ReactionExecutionBudget {
    public static final int DEFAULT_MAX_STARTS = 256;
    public static final int DEFAULT_MAX_TARGET_OPERATIONS = 2048;

    private int startsRemaining;
    private int targetOperationsRemaining;

    public ReactionExecutionBudget() {
        this(DEFAULT_MAX_STARTS, DEFAULT_MAX_TARGET_OPERATIONS);
    }

    public ReactionExecutionBudget(int starts, int targetOperations) {
        startsRemaining = Math.max(0, starts);
        targetOperationsRemaining = Math.max(0, targetOperations);
    }

    public boolean tryStart() {
        if (startsRemaining == 0) return false;
        startsRemaining--;
        return true;
    }

    public int claimTargetOperations(int requested) {
        int granted = Math.min(Math.max(0, requested), targetOperationsRemaining);
        targetOperationsRemaining -= granted;
        return granted;
    }

    public int startsRemaining() {
        return startsRemaining;
    }

    public int targetOperationsRemaining() {
        return targetOperationsRemaining;
    }
}
