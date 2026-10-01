package com.elementalphase.reaction.runtime;

import com.elementalphase.data.model.ReactionAction;
import com.elementalphase.reaction.formula.DamageFormulaParser;
import com.elementalphase.state.ElementSourceSnapshot;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReactionRuntimeControllerTest {
    private static final AtomicInteger FIXTURE_SEQUENCE = new AtomicInteger();

    @Test
    void deathEventListenersWaitForFinalEntityStateBeforeRemovingRuntime() throws Exception {
        var events = bytecode("com/elementalphase/event/CommonEvents");
        for (var method : events.methods) {
            if (!method.desc.contains("Lnet/minecraftforge/event/entity/living/LivingDeathEvent;")) continue;
            if (method.visibleAnnotations == null || method.visibleAnnotations.stream().noneMatch(annotation ->
                    annotation.desc.equals("Lnet/minecraftforge/eventbus/api/SubscribeEvent;"))) continue;
            assertFalse(java.util.Arrays.stream(method.instructions.toArray())
                    .filter(instruction -> instruction instanceof MethodInsnNode)
                    .map(instruction -> (MethodInsnNode) instruction)
                    .anyMatch(call -> call.owner.equals("com/elementalphase/reaction/runtime/ReactionRuntimeController")
                            && call.name.equals("remove")),
                    "A later death listener can still cancel death and restore health");
        }
    }

    @Test
    void finalDeathBeforeLevelEndRemovesMovementAndClearsOnlyFrozenClientState() throws Exception {
        for (boolean frozen : new boolean[]{true, false}) {
            var fixture = runtimeFixture();
            var level = new RuntimeLevel();
            var entity = level.entity();
            entity.alive = false;
            track(fixture, level, entity, frozen);

            tick(fixture, level);

            assertFalse(fixture.active.containsKey(level));
            assertEquals(1, entity.movementRemovals);
            assertEquals(frozen ? 1 : 0, entity.clears);
            assertEquals(0, entity.hits);
        }
    }

    @Test
    void finalDeathDuringScheduledPulseRemovesMovementAndClearsOnlyFrozenClientState() throws Exception {
        for (boolean frozen : new boolean[]{true, false}) {
            var fixture = runtimeFixture();
            var level = new RuntimeLevel();
            var entity = level.entity();
            entity.damage = () -> entity.alive = false;
            track(fixture, level, entity, frozen);

            tick(fixture, level);

            assertEquals(1, entity.hits);
            assertFalse(fixture.active.containsKey(level));
            assertEquals(1, entity.movementRemovals);
            assertEquals(frozen ? 1 : 0, entity.clears);
        }
    }

    @Test
    void firstScheduledPulseRemovesFreezeBeforeInvalidTargetClearsItsRuntime() throws Exception {
        var fixture = runtimeFixture();
        var level = new RuntimeLevel();
        var entity = level.entity();
        entity.damage = () -> entity.alive = false;
        var runtime = track(fixture, level, entity, true);
        var incoming = task(2, 120, 80, 20, "lethal");

        ReactionRuntimeController.applyScheduled(runtime, incoming, state -> {
            try {
                firstPulse(fixture, level, entity, state);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }, () -> entity.alive);

        assertFalse(fixture.active.containsKey(level));
        assertEquals(1, entity.movementRemovals);
        assertEquals(1, entity.clears);
        assertTrue(runtime.tasksInOrder().isEmpty());
    }

    @Test
    void canceledDeathWithRestoredHealthPreservesFreezeAndScheduledDamage() throws Exception {
        var fixture = runtimeFixture();
        var level = new RuntimeLevel();
        var entity = level.entity();
        entity.damage = () -> {
            entity.alive = false;
            entity.alive = true;
        };
        var runtime = track(fixture, level, entity, true);
        var freeze = runtime.freeze();

        firstPulse(fixture, level, entity, task(2, 120, 80, 20, "rescued"));
        tick(fixture, level);

        assertSame(runtime, fixture.active.get(level).get(entity.id));
        assertSame(freeze, runtime.freeze());
        assertNotNull(runtime.task(id("pulse")));
        assertEquals(2, entity.hits);
        assertEquals(0, entity.movementRemovals);
        assertEquals(0, entity.clears);
    }

    @Test
    void scheduledPulseCanRemoveAnotherEntryBeforeItsLevelEndTurn() throws Exception {
        var fixture = runtimeFixture();
        var level = new RuntimeLevel();
        var first = level.entity();
        var second = level.entity();
        track(fixture, level, first, true);
        track(fixture, level, second, true);
        first.damage = () -> fixture.active.get(level).remove(second.id);

        assertDoesNotThrow(() -> tick(fixture, level));

        assertEquals(1, first.hits);
        assertEquals(0, second.hits);
    }

    @Test
    void clearingLevelDuringPeriodicPulseStopsOtherOldTasksAndFrozenEntries() throws Exception {
        var fixture = runtimeFixture();
        var level = new RuntimeLevel();
        var first = level.entity();
        var second = level.entity();
        var runtime = track(fixture, level, first, false);
        runtime.task(id("tail"), task(100L, 100));
        track(fixture, level, second, true);
        first.damage = () -> fixture.active.remove(level);

        tick(fixture, level);

        assertAll(() -> assertEquals(1, first.hits),
                () -> assertEquals(0, second.hits),
                () -> assertEquals(0, second.movementApplications),
                () -> assertFalse(fixture.active.containsKey(level)));
    }

    @Test
    void replacingLevelDuringPeriodicPulsePreservesNewRuntimeAndStopsOldEntries() throws Exception {
        var fixture = runtimeFixture();
        var level = new RuntimeLevel();
        var first = level.entity();
        var second = level.entity();
        var replacement = level.entity();
        var runtime = track(fixture, level, first, false);
        runtime.task(id("tail"), task(100L, 100));
        track(fixture, level, second, true);
        first.damage = () -> {
            fixture.active.remove(level);
            track(fixture, level, replacement, true);
        };

        tick(fixture, level);

        assertAll(() -> assertEquals(1, first.hits),
                () -> assertEquals(0, second.hits),
                () -> assertEquals(0, second.movementApplications),
                () -> assertEquals(0, replacement.hits),
                () -> assertEquals(java.util.Set.of(replacement.id), fixture.active.get(level).keySet()));
    }

    @Test
    void lowerScaleStillHitsAndSameTickEqualRefreshHitsAgain() {
        var runtime = new ReactionRuntimeController.EntityRuntime();
        var old = task(4, 100, 100, 20, "old");
        runtime.task(old.effectId(), old);
        runtime.markRun(old.effectId(), 100);
        var hits = new java.util.ArrayList<ScheduledDamageState>();
        var low = task(2, 100, 40, 10, "low");
        assertEquals(ScheduledDamageState.ApplyResult.IGNORED_LOWER,
                ReactionRuntimeController.applyScheduled(runtime, low, hits::add, () -> true));
        assertSame(old, runtime.task(old.effectId()));
        assertTrue(runtime.ranAt(old.effectId(), 100));
        var equal = task(4, 100, 80, 10, "equal");
        ReactionRuntimeController.applyScheduled(runtime, equal, hits::add, () -> true);
        ReactionRuntimeController.applyScheduled(runtime, equal, hits::add, () -> true);
        assertEquals(java.util.List.of(low, equal, equal), hits);
        assertSame(equal, runtime.task(equal.effectId()));
        assertFalse(runtime.ranAt(equal.effectId(), 100));
        assertEquals(id("equal"), runtime.task(equal.effectId()).source().sourceId());
        assertTrue(runtime.task(equal.effectId()).due(110, Long.MIN_VALUE));
    }

    @Test
    void acceptedZeroDurationEndsTaskAndLowInstantKeepsOldTask() {
        var runtime = new ReactionRuntimeController.EntityRuntime();
        var old = task(4, 100, 100, 20, "old");
        runtime.task(old.effectId(), old);
        var hits = new java.util.ArrayList<ScheduledDamageState>();
        ReactionRuntimeController.applyScheduled(runtime, task(2, 110, 0, 20, "low"), hits::add, () -> true);
        assertSame(old, runtime.task(old.effectId()));
        ReactionRuntimeController.applyScheduled(runtime, task(5, 110, 0, 20, "higher"), hits::add, () -> true);
        assertNull(runtime.task(old.effectId()));
        assertEquals(2, hits.size());
    }

    @Test
    void firstPulseDoesNotDiscardOtherTaskEndpointAndInvalidTargetClearsAll() {
        var runtime = new ReactionRuntimeController.EntityRuntime();
        var endpoint = task(4, 100, 20, 20, "endpoint");
        runtime.task(id("other"), endpoint);
        var higher = task(5, 120, 100, 20, "higher");
        ReactionRuntimeController.applyScheduled(runtime, higher, ignored -> {}, () -> true);
        assertSame(endpoint, runtime.task(id("other")));
        assertTrue(endpoint.due(120, Long.MIN_VALUE));
        assertSame(higher, runtime.task(higher.effectId()));
        ReactionRuntimeController.applyScheduled(runtime, task(6, 120, 100, 20, "death"), ignored -> {}, () -> false);
        assertTrue(runtime.tasksInOrder().isEmpty());
    }

    @Test
    void retainsOnlyLiveStateAndCurrentTickTombstones() {
        var runtime = new ReactionRuntimeController.EntityRuntime();
        assertFalse(runtime.retainAt(100L));

        runtime.freeze(FrozenReactionState.create(100L, 20));
        assertTrue(runtime.retainAt(100L));
        runtime.clearFreeze();

        ResourceLocation taskId = id("pulse");
        runtime.task(taskId, task(100L, 0));
        runtime.markRun(taskId, 100L);
        runtime.removeExpiredTasks(100L);
        assertTrue(runtime.retainAt(100L));
        assertTrue(runtime.ranAt(taskId, 100L));
        assertFalse(runtime.retainAt(101L));
    }

    @Test
    void keepsDistinctTasksAndClearRemovesEverything() {
        var runtime = new ReactionRuntimeController.EntityRuntime();
        runtime.freeze(FrozenReactionState.create(100L, 20));
        runtime.task(id("one"), task(100L, 20));
        runtime.task(id("two"), task(100L, 20));

        assertTrue(runtime.retainAt(100L));
        runtime.clear();
        assertFalse(runtime.retainAt(100L));
    }

    private static ClassNode bytecode(String name) throws Exception {
        try (var stream = ReactionRuntimeControllerTest.class.getResourceAsStream("/" + name + ".class")) {
            assertNotNull(stream);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static RuntimeFixture runtimeFixture() throws Exception {
        String original = "com/elementalphase/reaction/runtime/ReactionRuntimeController";
        String generated = original + "Test$Execution" + FIXTURE_SEQUENCE.incrementAndGet();
        Map<String, String> types = Map.of(original, generated,
                "net/minecraft/server/level/ServerLevel", org.objectweb.asm.Type.getInternalName(RuntimeLevel.class),
                "net/minecraft/world/level/Level", org.objectweb.asm.Type.getInternalName(RuntimeLevel.class),
                "net/minecraft/world/entity/Entity", org.objectweb.asm.Type.getInternalName(RuntimeEntity.class),
                "net/minecraft/world/entity/LivingEntity", org.objectweb.asm.Type.getInternalName(RuntimeEntity.class),
                "net/minecraft/server/MinecraftServer", "java/lang/Object",
                "com/elementalphase/reaction/runtime/ScheduledReactionDamageExecutor", generated);
        var remapper = new Remapper() {
            @Override
            public String map(String name) { return types.getOrDefault(name, name); }
        };
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, generated, null,
                org.objectweb.asm.Type.getInternalName(RuntimeFixture.class), null);
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                org.objectweb.asm.Type.getInternalName(RuntimeFixture.class), "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        for (var method : bytecode(original).methods) {
            if (!method.name.equals("tickLevelEnd") && !method.name.equals("remove")
                    && !method.name.startsWith("lambda$scheduleDamage$")) continue;
            int access = method.access & ~Opcodes.ACC_PRIVATE | Opcodes.ACC_PUBLIC;
            method.accept(new MethodRemapper(writer.visitMethod(access, method.name,
                    remapper.mapMethodDesc(method.desc), null, null), remapper));
        }
        writer.visitEnd();
        return (RuntimeFixture) MethodHandles.lookup().defineClass(writer.toByteArray()).getConstructor().newInstance();
    }

    private static ReactionRuntimeController.EntityRuntime track(RuntimeFixture fixture, RuntimeLevel level,
                                                                 RuntimeEntity entity, boolean frozen) {
        var runtime = new ReactionRuntimeController.EntityRuntime();
        if (frozen) runtime.freeze(FrozenReactionState.create(100L, 100));
        runtime.task(id("pulse"), task(100L, 100));
        fixture.active.computeIfAbsent(level, ignored -> new LinkedHashMap<>()).put(entity.id, runtime);
        return runtime;
    }

    private static void tick(RuntimeFixture fixture, RuntimeLevel level) throws ReflectiveOperationException {
        fixture.getClass().getMethod("tickLevelEnd", RuntimeLevel.class, long.class).invoke(fixture, level, 120L);
    }

    private static void firstPulse(RuntimeFixture fixture, RuntimeLevel level, RuntimeEntity entity,
                                   ScheduledDamageState state) throws ReflectiveOperationException {
        var firstHit = java.util.Arrays.stream(fixture.getClass().getDeclaredMethods())
                .filter(method -> method.getName().startsWith("lambda$scheduleDamage$")
                        && method.getReturnType() == void.class).findFirst().orElseThrow();
        firstHit.invoke(Modifier.isStatic(firstHit.getModifiers()) ? null : fixture, level, entity, state);
    }

    public static class RuntimeFixture {
        public final Map<RuntimeLevel, Map<UUID, ReactionRuntimeController.EntityRuntime>> active = new LinkedHashMap<>();
        public static boolean valid(RuntimeLevel level, RuntimeEntity entity) {
            return entity.level == level && entity.alive;
        }
        public static void applyFreezeMovement(RuntimeEntity entity) { entity.movementApplications++; }
        public static void clearHorizontalMomentum(RuntimeEntity entity) { }
        public static void removeFreezeMovement(RuntimeEntity entity) { entity.movementRemovals++; }
        public static void sendClear(RuntimeEntity entity) { entity.clears++; }
        public static boolean execute(RuntimeLevel level, RuntimeEntity entity, ScheduledDamageState state) {
            entity.hits++;
            entity.damage.run();
            return true;
        }
        public static void executeIfDue(RuntimeLevel level, RuntimeEntity entity,
                                       ReactionRuntimeController.EntityRuntime runtime, ResourceLocation taskId,
                                       ScheduledDamageState state, long now) {
            if (!state.due(now, runtime.lastRun(taskId))) return;
            runtime.markRun(taskId, now);
            execute(level, entity, state);
        }
    }

    public static final class RuntimeLevel {
        private final Map<UUID, RuntimeEntity> entities = new LinkedHashMap<>();
        public RuntimeEntity getEntity(UUID id) { return entities.get(id); }
        public Object getServer() { return this; }
        RuntimeEntity entity() {
            var entity = new RuntimeEntity(this);
            entities.put(entity.id, entity);
            return entity;
        }
    }

    public static final class RuntimeEntity {
        private final UUID id = UUID.randomUUID();
        private final RuntimeLevel level;
        private boolean alive = true;
        private int movementRemovals;
        private int movementApplications;
        private int clears;
        private int hits;
        private Runnable damage = () -> { };
        private RuntimeEntity(RuntimeLevel level) { this.level = level; }
        public RuntimeLevel level() { return level; }
        public UUID getUUID() { return id; }
    }

    private static ScheduledDamageState task(long now, int duration) {
        return task(1, now, duration, 20, "source");
    }

    private static ScheduledDamageState task(double scale, long now, int duration, int interval, String sourceId) {
        var damage = new ReactionAction.StateDamage(
                new ReactionAction.Formula("scale", DamageFormulaParser.parseReaction("scale")),
                id("reaction_dot"), Optional.empty());
        var source = new ElementSourceSnapshot(Optional.empty(), Optional.empty(), id(sourceId), id("reaction_dot"),
                id("lightning"), 1.0D, now);
        var snapshot = new ReactionAction.StateSnapshot(
                com.elementalphase.reaction.formula.ReactionFormulaContext.stateDamage(scale, 0), Optional.empty(),
                0, 0, sourceId.hashCode() & 0xFFFFFF, !sourceId.equals("low"));
        return ScheduledDamageState.create(id("pulse"), id("reaction"), scale, now, duration, interval, damage, source, snapshot);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }
}
