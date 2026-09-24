package com.elementalphase.display;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

final class AreaDamageTargetSelector {
    private AreaDamageTargetSelector() {
    }

    static <T> List<T> select(T primary, T attacker, Iterable<T> candidates,
                              Predicate<T> valid, ToDoubleFunction<T> distanceSquared,
                              ToIntFunction<T> stableId, double radiusSquared, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Set<T> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<T> selected = new ArrayList<>();
        if (primary != null && primary != attacker && valid.test(primary)) {
            selected.add(primary);
            seen.add(primary);
        }
        int remaining = limit - selected.size();
        if (remaining == 0) {
            return List.copyOf(selected);
        }
        Comparator<T> nearestFirst = Comparator.comparingDouble(distanceSquared).thenComparingInt(stableId);
        PriorityQueue<T> nearby = new PriorityQueue<>(remaining, nearestFirst.reversed());
        for (T candidate : candidates) {
            if (candidate == null || candidate == attacker || candidate == primary || !seen.add(candidate)
                    || !valid.test(candidate) || distanceSquared.applyAsDouble(candidate) > radiusSquared) {
                continue;
            }
            if (nearby.size() < remaining) {
                nearby.add(candidate);
            } else if (nearestFirst.compare(candidate, nearby.peek()) < 0) {
                nearby.poll();
                nearby.add(candidate);
            }
        }
        List<T> orderedNearby = new ArrayList<>(nearby);
        orderedNearby.sort(nearestFirst);
        selected.addAll(orderedNearby);
        return List.copyOf(selected);
    }
}
