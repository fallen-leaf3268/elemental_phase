package com.elementalphase.combat;

import com.elementalphase.effect.ReactionActionExecutor;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

public final class ReactionExecutionScheduler {
    public static final int MAX_QUEUED_HITS = 4096;

    private final Deque<Entry> queue = new ArrayDeque<>();
    private final ReactionActionExecutor executor;
    private int reservations;

    public ReactionExecutionScheduler(ReactionActionExecutor executor) {
        this.executor = executor;
    }

    public synchronized Optional<Reservation> reserve(MinecraftServer server) {
        if (server == null || reservations >= MAX_QUEUED_HITS) return Optional.empty();
        reservations++;
        return Optional.of(new Reservation(this, server));
    }

    public synchronized boolean commit(Reservation reservation, QueuedHitExecution execution) {
        if (!valid(reservation) || execution == null || execution.level().getServer() != reservation.server) return false;
        reservation.closed = true;
        queue.addLast(new Entry(reservation.server, execution));
        return true;
    }

    public synchronized void release(Reservation reservation) {
        if (!valid(reservation)) return;
        reservation.closed = true;
        reservations--;
    }

    public void drain(MinecraftServer server) {
        ReactionExecutionBudget budget = new ReactionExecutionBudget();
        while (budget.tryStart()) {
            QueuedHitExecution execution;
            synchronized (this) {
                Entry entry = queue.peekFirst();
                if (entry == null || entry.server != server) break;
                queue.removeFirst();
                reservations--;
                execution = entry.execution;
            }
            executor.executeOrdered(execution, budget);
        }
    }

    public synchronized void clear() {
        queue.clear();
        reservations = 0;
    }

    synchronized int size() {
        return reservations;
    }

    private boolean valid(Reservation reservation) {
        return reservation != null && reservation.owner == this && !reservation.closed;
    }

    public static final class Reservation {
        private final ReactionExecutionScheduler owner;
        private final MinecraftServer server;
        private boolean closed;

        private Reservation(ReactionExecutionScheduler owner, MinecraftServer server) {
            this.owner = owner;
            this.server = server;
        }
    }

    private record Entry(MinecraftServer server, QueuedHitExecution execution) {
    }
}
