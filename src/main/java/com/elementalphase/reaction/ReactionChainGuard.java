package com.elementalphase.reaction;

public final class ReactionChainGuard {
    public static final int MAX_DEPTH = 8;
    public static final int MAX_REACTIONS = 32;

    private final int depth;
    private final Counter counter;

    public ReactionChainGuard() {
        this(0, new Counter());
    }

    private ReactionChainGuard(int depth, Counter counter) {
        this.depth = depth;
        this.counter = counter;
    }

    public boolean tryReaction() {
        if (depth >= MAX_DEPTH || counter.reactions >= MAX_REACTIONS) return false;
        counter.reactions++;
        return true;
    }

    public ReactionChainGuard child() {
        return new ReactionChainGuard(depth + 1, counter);
    }

    public int depth() {
        return depth;
    }

    public int reactions() {
        return counter.reactions;
    }

    private static final class Counter {
        private int reactions;
    }
}
