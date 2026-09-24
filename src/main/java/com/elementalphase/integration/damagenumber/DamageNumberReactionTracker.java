package com.elementalphase.integration.damagenumber;

import com.elementalphase.display.PendingMainDamageTracker.ReactionLabel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class DamageNumberReactionTracker<S> {
    static final int MAX_ENTRIES = 4096;
    private final Map<S, Deque<Entry>> entries = new IdentityHashMap<>();
    private int size;

    public boolean record(S source, UUID playerId, long tick, List<ReactionLabel> labels) {
        if (source == null || playerId == null || labels == null || labels.isEmpty()
                || labels.stream().anyMatch(java.util.Objects::isNull) || size >= MAX_ENTRIES) {
            return false;
        }
        entries.computeIfAbsent(source, ignored -> new ArrayDeque<>())
                .addLast(new Entry(playerId, tick, List.copyOf(labels)));
        size++;
        return true;
    }

    public Optional<List<ReactionLabel>> consume(S source, UUID playerId, long tick) {
        if (source == null || playerId == null) {
            return Optional.empty();
        }
        Deque<Entry> sourceEntries = entries.get(source);
        if (sourceEntries == null) {
            return Optional.empty();
        }
        Iterator<Entry> iterator = sourceEntries.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.tick() == tick && entry.playerId().equals(playerId)) {
                iterator.remove();
                size--;
                if (sourceEntries.isEmpty()) {
                    entries.remove(source);
                }
                return Optional.of(entry.labels());
            }
        }
        return Optional.empty();
    }

    public void clearBefore(long tick) {
        List<S> emptySources = new ArrayList<>();
        for (Map.Entry<S, Deque<Entry>> sourceEntry : entries.entrySet()) {
            Iterator<Entry> iterator = sourceEntry.getValue().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().tick() < tick) {
                    iterator.remove();
                    size--;
                }
            }
            if (sourceEntry.getValue().isEmpty()) {
                emptySources.add(sourceEntry.getKey());
            }
        }
        emptySources.forEach(entries::remove);
    }

    public void clear() {
        entries.clear();
        size = 0;
    }

    private record Entry(UUID playerId, long tick, List<ReactionLabel> labels) {
    }
}
