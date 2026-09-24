package com.elementalphase.reaction.runtime;

import com.elementalphase.data.model.ReactionAction;
import com.elementalphase.network.FrozenStateSyncPacket;
import com.elementalphase.network.ModNetwork;
import com.elementalphase.state.ElementSourceSnapshot;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ReactionRuntimeController {
    public static final ReactionRuntimeController INSTANCE = new ReactionRuntimeController();
    private static final UUID FREEZE_MOVEMENT_MODIFIER_ID = UUID.fromString("81f1fcf8-e734-42fa-b8ad-d682de86d663");
    private static final String FREEZE_MOVEMENT_MODIFIER_NAME = "Elemental Phase frozen movement";

    private final Map<ServerLevel, Map<UUID, EntityRuntime>> active = new IdentityHashMap<>();

    private ReactionRuntimeController() {
    }

    public void applyFreeze(ServerLevel level, LivingEntity target, int durationTicks, long now) {
        if (!valid(level, target)) return;
        EntityRuntime runtime = runtime(level, target);
        runtime.freeze(runtime.freeze() == null
                ? FrozenReactionState.create(now, durationTicks)
                : runtime.freeze().refresh(now, durationTicks));
        applyFreezeMovement(target);
        clearHorizontalMomentum(target);
        send(target, runtime.freeze(), now);
    }

    public void scheduleDamage(ServerLevel level, LivingEntity target, ResourceLocation taskId,
                               ResourceLocation reactionId, double scale, int durationTicks, int intervalTicks,
                               ReactionAction.StateDamage damage, ElementSourceSnapshot source, long now) {
        if (!valid(level, target)) return;
        EntityRuntime runtime = runtime(level, target);
        ScheduledDamageState current = runtime.task(taskId);
        ScheduledDamageState.Application application;
        if (current == null) {
            application = new ScheduledDamageState.Application(
                    ScheduledDamageState.create(reactionId, scale, now, durationTicks, intervalTicks, damage, source),
                    ScheduledDamageState.ApplyResult.CREATED);
        } else {
            application = ScheduledDamageState.apply(current, reactionId, scale, now,
                    durationTicks, intervalTicks, damage, source);
        }
        if (application.result() == ScheduledDamageState.ApplyResult.IGNORED_LOWER) return;
        runtime.task(taskId, application.state());
        executeIfDue(level, target, runtime, taskId, application.state(), now);
        runtime.removeExpiredTasks(now);
        if (!valid(level, target)) remove(target);
    }

    public void tickLevelEnd(ServerLevel level, long now) {
        Map<UUID, EntityRuntime> entries = active.get(level);
        if (entries == null) return;
        for (UUID id : List.copyOf(entries.keySet())) {
            Entity raw = level.getEntity(id);
            if (!(raw instanceof LivingEntity target) || !valid(level, target)) {
                entries.remove(id);
                continue;
            }
            EntityRuntime runtime = entries.get(id);
            if (runtime.freeze() != null) {
                if (runtime.freeze().active(now)) {
                    applyFreezeMovement(target);
                    clearHorizontalMomentum(target);
                } else {
                    runtime.clearFreeze();
                    removeFreezeMovement(target);
                    sendClear(target);
                }
            }
            for (var entry : runtime.tasksInOrder()) {
                if (!valid(level, target)) break;
                executeIfDue(level, target, runtime, entry.getKey(), entry.getValue(), now);
            }
            runtime.removeExpiredTasks(now);
            if (!valid(level, target)) {
                removeFreezeMovement(target);
                entries.remove(id);
            } else if (!runtime.retainAt(now)) {
                removeFreezeMovement(target);
                entries.remove(id);
            }
        }
        if (entries.isEmpty()) active.remove(level);
    }

    public void syncTo(ServerPlayer player, LivingEntity target) {
        if (!(target.level() instanceof ServerLevel level)) return;
        Map<UUID, EntityRuntime> entries = active.get(level);
        EntityRuntime runtime = entries == null ? null : entries.get(target.getUUID());
        FrozenReactionState freeze = runtime == null ? null : runtime.freeze();
        long now = level.getGameTime();
        ModNetwork.sendFrozenStateTo(player, freeze != null && freeze.active(now)
                ? packet(target, freeze, now)
                : new FrozenStateSyncPacket(target.getId(), false, 0, 0));
    }

    public void remove(LivingEntity entity) {
        if (!(entity.level() instanceof ServerLevel currentLevel)) return;
        boolean removedFreeze = false;
        for (ServerLevel level : new ArrayList<>(active.keySet())) {
            if (level.getServer() != currentLevel.getServer()) continue;
            Map<UUID, EntityRuntime> entries = active.get(level);
            if (entries == null) continue;
            EntityRuntime removed = entries.remove(entity.getUUID());
            removedFreeze |= removed != null && removed.freeze() != null;
            if (entries.isEmpty()) active.remove(level);
        }
        removeFreezeMovement(entity);
        if (removedFreeze) sendClear(entity);
    }

    public void clear(MinecraftServer server) {
        for (ServerLevel level : new ArrayList<>(active.keySet())) {
            if (level.getServer() != server) continue;
            Map<UUID, EntityRuntime> entries = active.remove(level);
            if (entries == null) continue;
            for (UUID id : entries.keySet()) {
                Entity entity = level.getEntity(id);
                if (entity instanceof LivingEntity living) {
                    removeFreezeMovement(living);
                    sendClear(living);
                }
            }
        }
    }

    private static void executeIfDue(ServerLevel level, LivingEntity target, EntityRuntime runtime,
                                     ResourceLocation taskId, ScheduledDamageState state, long now) {
        if (!state.due(now, runtime.lastRun(taskId))) return;
        runtime.markRun(taskId, now);
        ScheduledReactionDamageExecutor.execute(level, target, state);
    }

    private EntityRuntime runtime(ServerLevel level, LivingEntity target) {
        return active.computeIfAbsent(level, ignored -> new HashMap<>())
                .computeIfAbsent(target.getUUID(), ignored -> new EntityRuntime());
    }

    private static boolean valid(ServerLevel level, LivingEntity entity) {
        return entity != null && entity.level() == level && entity.isAlive() && !entity.isRemoved();
    }

    private static void applyFreezeMovement(LivingEntity entity) {
        AttributeInstance attribute = entity.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attribute == null || attribute.getModifier(FREEZE_MOVEMENT_MODIFIER_ID) != null) return;
        attribute.addTransientModifier(new AttributeModifier(FREEZE_MOVEMENT_MODIFIER_ID,
                FREEZE_MOVEMENT_MODIFIER_NAME, -1.0D, AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    private static void removeFreezeMovement(LivingEntity entity) {
        AttributeInstance attribute = entity.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attribute != null) attribute.removeModifier(FREEZE_MOVEMENT_MODIFIER_ID);
    }

    private static void clearHorizontalMomentum(LivingEntity entity) {
        var movement = entity.getDeltaMovement();
        entity.setDeltaMovement(0.0D, movement.y, 0.0D);
        entity.hurtMarked = true;
    }

    private static void send(LivingEntity entity, FrozenReactionState state, long now) {
        ModNetwork.sendFrozenState(entity, packet(entity, state, now));
    }

    private static void sendClear(LivingEntity entity) {
        ModNetwork.sendFrozenState(entity, new FrozenStateSyncPacket(entity.getId(), false, 0, 0));
    }

    private static FrozenStateSyncPacket packet(LivingEntity entity, FrozenReactionState state, long now) {
        return new FrozenStateSyncPacket(entity.getId(), true,
                Math.min(FrozenStateSyncPacket.MAX_TICKS, state.durationTicks()),
                Math.min(FrozenStateSyncPacket.MAX_TICKS, state.remainingTicks(now)));
    }

    static final class EntityRuntime {
        private FrozenReactionState freeze;
        private final Map<ResourceLocation, ScheduledDamageState> tasks = new HashMap<>();
        private final Map<ResourceLocation, Long> lastRuns = new HashMap<>();

        FrozenReactionState freeze() {
            return freeze;
        }

        void freeze(FrozenReactionState freeze) {
            this.freeze = freeze;
        }

        void clearFreeze() {
            freeze = null;
        }

        ScheduledDamageState task(ResourceLocation id) {
            return tasks.get(id);
        }

        void task(ResourceLocation id, ScheduledDamageState task) {
            tasks.put(id, task);
        }

        long lastRun(ResourceLocation id) {
            return lastRuns.getOrDefault(id, Long.MIN_VALUE);
        }

        void markRun(ResourceLocation id, long now) {
            lastRuns.put(id, now);
        }

        boolean ranAt(ResourceLocation id, long now) {
            return lastRun(id) == now;
        }

        List<Map.Entry<ResourceLocation, ScheduledDamageState>> tasksInOrder() {
            return tasks.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.naturalOrder())).toList();
        }

        void removeExpiredTasks(long now) {
            tasks.entrySet().removeIf(entry -> now >= entry.getValue().expiresAt());
        }

        boolean retainAt(long now) {
            if (freeze != null && !freeze.active(now)) freeze = null;
            removeExpiredTasks(now);
            lastRuns.entrySet().removeIf(entry -> !tasks.containsKey(entry.getKey()) && entry.getValue() != now);
            return freeze != null || !tasks.isEmpty() || !lastRuns.isEmpty();
        }

        void clear() {
            freeze = null;
            tasks.clear();
            lastRuns.clear();
        }
    }
}
