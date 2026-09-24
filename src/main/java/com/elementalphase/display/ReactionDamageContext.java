package com.elementalphase.display;

import com.elementalphase.reaction.ReactionOutcome.TriggeredReaction;
import com.elementalphase.reaction.ReactionChainGuard;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class ReactionDamageContext {
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private ReactionDamageContext() {
    }

    public static Optional<TriggeredReaction> current() {
        return Optional.ofNullable(CURRENT.get()).map(Context::reaction);
    }

    public static Optional<TriggeredReaction> currentFor(Object source) {
        Context context = CURRENT.get();
        return context != null && context.source() == source
                ? Optional.of(context.reaction()) : Optional.empty();
    }

    public static boolean allowsElementApplication() {
        Context context = CURRENT.get();
        return context != null && context.allowElementApplication();
    }

    public static boolean allowsReactions() {
        Context context = CURRENT.get();
        return context != null && context.allowReactions();
    }

    public static Optional<ReactionChainGuard> chainGuard() {
        return Optional.ofNullable(CURRENT.get()).map(Context::guard);
    }

    public static void run(TriggeredReaction reaction, Object source, Runnable action) {
        call(reaction, source, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T call(TriggeredReaction reaction, Object source, Supplier<T> action) {
        return call(reaction, source, false, false, action);
    }

    public static <T> T call(TriggeredReaction reaction, Object source, boolean allowElementApplication,
                             boolean allowReactions, Supplier<T> action) {
        return call(reaction, source, allowElementApplication, allowReactions, null, action);
    }

    public static <T> T call(TriggeredReaction reaction, Object source, boolean allowElementApplication,
                             boolean allowReactions, ReactionChainGuard guard, Supplier<T> action) {
        Objects.requireNonNull(reaction, "reaction");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(action, "action");
        Context previous = CURRENT.get();
        CURRENT.set(new Context(reaction, source, allowElementApplication, allowReactions, guard));
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    private record Context(TriggeredReaction reaction, Object source, boolean allowElementApplication,
                           boolean allowReactions, ReactionChainGuard guard) {
    }
}
