package com.elementalphase.display;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public final class PendingFinalDamageQueue<E, T> {
    static final int MAX_ENTRIES = 4096;
    private final Deque<Entry<E, T>> entries = new ArrayDeque<>();

    public boolean record(E event, T payload) {
        if (event == null || payload == null || entries.size() >= MAX_ENTRIES) {
            return false;
        }
        entries.addLast(new Entry<>(event, payload));
        return true;
    }

    public List<Resolved<T>> drain(Function<? super E, FinalState> stateResolver) {
        return drainOutcomes(stateResolver).stream()
                .filter(value -> !value.canceled() && value.damage() > 0.0D)
                .map(value -> new Resolved<>(value.payload(), value.damage()))
                .toList();
    }

    public List<Outcome<T>> drainOutcomes(Function<? super E, FinalState> stateResolver) {
        Objects.requireNonNull(stateResolver, "stateResolver");
        List<Outcome<T>> outcomes = new ArrayList<>(entries.size());
        while (!entries.isEmpty()) {
            Entry<E, T> entry = entries.removeFirst();
            FinalState state = stateResolver.apply(entry.event());
            outcomes.add(new Outcome<>(entry.payload(), state == null || state.canceled(),
                    state == null ? 0.0D : displayedDamage(state.amount())));
        }
        return List.copyOf(outcomes);
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    static double displayedDamage(double amount) {
        if (!Double.isFinite(amount)) {
            return 0.0D;
        }
        return Math.max(0.0D, amount);
    }

    public record FinalState(boolean canceled, double amount) {
    }

    public record Resolved<T>(T payload, double damage) {
    }

    public record Outcome<T>(T payload, boolean canceled, double damage) {
    }

    private record Entry<E, T>(E event, T payload) {
    }
}
