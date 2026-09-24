package com.elementalphase.display;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class PendingMainDamageTracker<S> {
    private static final int MAX_ENTRIES = 4096;
    private final IdentityHashMap<S, Deque<Entry>> entries = new IdentityHashMap<>();
    private int size;
    private long sequence;

    public void record(S source, int targetId, long tick, List<ReactionLabel> labels) {
        Objects.requireNonNull(source, "source");
        List<ReactionLabel> copy = List.copyOf(labels);
        if (size >= MAX_ENTRIES) {
            evictOldest();
        }
        entries.computeIfAbsent(source, ignored -> new ArrayDeque<>())
                .addFirst(new Entry(targetId, tick, sequence++, copy));
        size++;
    }

    public Optional<List<ReactionLabel>> consume(S source, int targetId, long tick) {
        Deque<Entry> stack = entries.get(source);
        if (stack == null) {
            return Optional.empty();
        }
        var iterator = stack.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.targetId() == targetId && entry.tick() == tick) {
                iterator.remove();
                size--;
                if (stack.isEmpty()) {
                    entries.remove(source);
                }
                return Optional.of(entry.labels());
            }
        }
        return Optional.empty();
    }

    public void clearBefore(long tick) {
        var iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Deque<Entry> stack = iterator.next();
            int before = stack.size();
            stack.removeIf(entry -> entry.tick() < tick);
            size -= before - stack.size();
            if (stack.isEmpty()) {
                iterator.remove();
            }
        }
    }

    public int size() {
        return size;
    }

    public void clear() {
        entries.clear();
        size = 0;
        sequence = 0L;
    }

    private void evictOldest() {
        S oldestSource = null;
        long oldestSequence = Long.MAX_VALUE;
        for (var source : entries.entrySet()) {
            long candidate = source.getValue().getLast().sequence();
            if (candidate < oldestSequence) {
                oldestSource = source.getKey();
                oldestSequence = candidate;
            }
        }
        Deque<Entry> stack = entries.get(oldestSource);
        stack.removeLast();
        size--;
        if (stack.isEmpty()) {
            entries.remove(oldestSource);
        }
    }

    public record ReactionLabel(ResourceLocation id, int color) {
        public ReactionLabel {
            Objects.requireNonNull(id, "id");
        }
    }

    private record Entry(int targetId, long tick, long sequence, List<ReactionLabel> labels) {
    }
}
