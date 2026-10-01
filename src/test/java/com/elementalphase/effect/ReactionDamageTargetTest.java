package com.elementalphase.effect;

import com.elementalphase.combat.ReactionExecutionBudget;
import com.elementalphase.data.ElementDataManager;
import com.elementalphase.data.ElementDataParser;
import com.elementalphase.data.ElementDataSnapshot;
import com.elementalphase.data.model.ReactionAction;
import com.elementalphase.integration.kubejs.KubeJsHooks;
import com.elementalphase.reaction.ReactionChainGuard;
import com.elementalphase.reaction.ReactionEngine;
import com.elementalphase.reaction.ReactionPlan;
import com.elementalphase.reaction.ReactionRequest;
import com.elementalphase.state.ElementalState;
import com.google.gson.JsonParser;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.LazyOptional;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ReactionDamageTargetTest {
    @net.minecraftforge.gametest.GameTestHolder("elemental_phase")
    @net.minecraftforge.gametest.PrefixGameTestTemplate(false)
    public static class RuntimeGameTests {
        private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

        @net.minecraft.gametest.framework.GameTest(template = "empty", batch = "periodicReload", timeoutTicks = 200)
        public static void periodicDamageReloadStopsOldTasksAndFreeze(net.minecraft.gametest.framework.GameTestHelper helper) throws Exception {
            var level = helper.getLevel();
            var server = level.getServer();
            var runtime = com.elementalphase.reaction.runtime.ReactionRuntimeController.INSTANCE;
            runtime.clear(server);
            var a = helper.spawn(EntityType.IRON_GOLEM, 3, 3, 3);
            var b = helper.spawn(EntityType.IRON_GOLEM, 6, 3, 3);
            for (var entity : List.of(a, b)) {
                entity.setNoAi(true);
                entity.setNoGravity(true);
                runtime.applyFreeze(level, entity, 100, level.getGameTime());
            }
            var activeField = runtime.getClass().getDeclaredField("active");
            activeField.setAccessible(true);
            var entries = (Map<?, ?>) ((Map<?, ?>) activeField.get(runtime)).get(level);
            var firstId = entries.keySet().iterator().next();
            var target = firstId.equals(a.getUUID()) ? a : b;
            var other = target == a ? b : a;
            var source = new com.elementalphase.state.ElementSourceSnapshot(Optional.empty(), Optional.empty(),
                    ResourceLocation.parse("elemental_phase:runtime_verify"), ReactionAction.DEFAULT_DOT_DAMAGE_TYPE,
                    ResourceLocation.parse("elemental_phase:lightning"), 1, level.getGameTime());
            var damage = new ReactionAction.StateDamage(constant(2), ReactionAction.DEFAULT_DOT_DAMAGE_TYPE, Optional.empty());
            var snapshot = new ReactionAction.StateSnapshot(
                    com.elementalphase.reaction.formula.ReactionFormulaContext.stateDamage(1, 0), Optional.empty(), 0, 0, 0xFFFFFF, false);
            for (String id : List.of("periodic_a", "periodic_b")) {
                runtime.scheduleDamage(level, target, ResourceLocation.parse("elemental_phase:" + id), source.sourceId(),
                        1, 100, 20, damage, source, snapshot, level.getGameTime());
            }
            target.setHealth(target.getMaxHealth());
            float before = target.getHealth();
            var hits = new java.util.concurrent.atomic.AtomicInteger();
            java.util.function.Consumer<net.minecraftforge.event.entity.living.LivingHurtEvent> listener = event -> {
                if (event.getEntity() == target && com.elementalphase.display.ReactionDamageContext.current().isPresent()
                        && hits.incrementAndGet() == 1) reload(server);
            };
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                    net.minecraftforge.eventbus.api.EventPriority.NORMAL, false,
                    net.minecraftforge.event.entity.living.LivingHurtEvent.class, listener);
            helper.runAfterDelay(25, () -> {
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(listener);
                helper.assertTrue(hits.get() == 1 && target.getHealth() == before - 2,
                        "Reload during periodic pulse allowed another old DOT task to hurt the target");
                var movement = other.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
                helper.assertTrue(movement != null && movement.getModifier(java.util.UUID.fromString(
                        "81f1fcf8-e734-42fa-b8ad-d682de86d663")) == null,
                        "Old level iteration reapplied frozen movement after reload cleared it");
                helper.assertTrue(!runtime.isFrozen(level, other, level.getGameTime()), "Old freeze remained active after reload");
                passed("periodic DOT reload stops same-tick old tasks and prevents frozen movement restoration");
                helper.succeed();
            });
        }

        @net.minecraft.gametest.framework.GameTest(template = "empty", timeoutTicks = 200)
        public static void actualRuntimeReloadAndCombat(net.minecraft.gametest.framework.GameTestHelper helper) throws Exception {
            var level = helper.getLevel();
            var server = level.getServer();
            var runtime = com.elementalphase.reaction.runtime.ReactionRuntimeController.INSTANCE;
            var schedulerField = com.elementalphase.event.CommonEvents.class.getDeclaredField("REACTION_SCHEDULER");
            schedulerField.setAccessible(true);
            var scheduler = (com.elementalphase.combat.ReactionExecutionScheduler) schedulerField.get(null);
            var target = helper.spawn(EntityType.IRON_GOLEM, 3, 3, 3);
            target.setNoAi(true);
            target.setNoGravity(true);
            var water = ResourceLocation.parse("elemental_phase:water");

            queueFreeze(helper, target, water);
            helper.assertTrue(!runtime.isFrozen(level, target, level.getGameTime()), "Freeze must wait for queue execution");
            scheduler.drain(server);
            helper.assertTrue(runtime.isFrozen(level, target, level.getGameTime()), "Real ice hit did not trigger frozen reaction");
            var before = target.position();
            target.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(0.25, 0.5, 0.25));
            helper.assertTrue(target.position().x == before.x && target.position().z == before.z
                    && target.position().y > before.y, "Frozen movement mixin must constrain horizontal movement only");
            passed("normal reaction and real frozen movement");

            reload(server);
            helper.assertTrue(!runtime.isFrozen(level, target, level.getGameTime()), "Reload did not remove active freeze");
            before = target.position();
            target.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(0.25, 0, 0));
            helper.assertTrue(target.position().x > before.x, "Horizontal movement remained frozen after reload");
            passed("successful reload clears active runtime and restores movement");

            queueFreeze(helper, target, water);
            helper.assertTrue(queueSize(scheduler) == 1, "Real hit must enqueue one freeze");
            var published = ElementDataManager.snapshot();
            reload(server);
            helper.assertTrue(ElementDataManager.snapshot() != published && queueSize(scheduler) == 0,
                    "Successful reload must publish new data and clear old queue");
            scheduler.drain(server);
            helper.assertTrue(!runtime.isFrozen(level, target, level.getGameTime()), "Old queued freeze survived reload");
            passed("successful reload discards old queued reaction");

            reset(target);
            var facts = freezeFacts(target, water);
            var freeze = new ReactionAction.Special(List.of(new ReactionAction.SpecialEntry(
                    ReactionAction.SpecialEntry.Kind.FREEZE, 100)), context -> 1);
            var extra = new ReactionAction.AdditionalDamage(constant(2), new ReactionAction.DamageSettings(
                    ReactionAction.DEFAULT_DAMAGE_TYPE, Optional.empty()), context -> 1);
            var hits = new java.util.concurrent.atomic.AtomicInteger();
            java.util.function.Consumer<net.minecraftforge.event.entity.living.LivingHurtEvent> listener = event -> {
                if (event.getEntity() == target && com.elementalphase.display.ReactionDamageContext.current().isPresent()) {
                    hits.incrementAndGet();
                    reload(server);
                }
            };
            enqueue(scheduler, target, plan(facts, extra, freeze));
            float health = target.getHealth();
            withHurtListener(listener, () -> scheduler.drain(server));
            helper.assertTrue(hits.get() == 1 && target.getHealth() < health, "Actual damage event did not execute reload");
            helper.assertTrue(!runtime.isFrozen(level, target, level.getGameTime()), "Old action resumed after damage-triggered reload");
            passed("real damage event reload stops subsequent old action");

            reset(target);
            var first = helper.spawn(EntityType.IRON_GOLEM, 5, 3, 3);
            var second = helper.spawn(EntityType.IRON_GOLEM, 6, 3, 3);
            first.setNoAi(true);
            second.setNoAi(true);
            first.setNoGravity(true);
            second.setNoGravity(true);
            float firstHealth = first.getHealth(), secondHealth = second.getHealth();
            hits.set(0);
            listener = event -> {
                if (event.getEntity() == first && com.elementalphase.display.ReactionDamageContext.current().isPresent()) {
                    hits.incrementAndGet();
                    reload(server);
                }
            };
            var area = new ReactionAction.Area(constant(4), Optional.of(new ReactionAction.AreaDamageValue(
                    constant(2), extra.settings(), false)), Optional.of(new ReactionAction.AreaAttachment(
                    ReactionAction.ElementReference.fixed(water), constant(1))), false, Optional.of(8), context -> 1);
            enqueue(scheduler, target, plan(freezeFacts(target, water), area));
            withHurtListener(listener, () -> scheduler.drain(server));
            helper.assertTrue(hits.get() == 1 && first.getHealth() < firstHealth && second.getHealth() == secondHealth,
                    "Old area damage continued to further targets after reload");
            helper.assertTrue(state(first).state(water) == null && state(second).state(water) == null,
                    "Old area attachment was recreated after reload");
            passed("area damage reload stops old attachment and remaining targets");

            queueFreeze(helper, target, water);
            runtime.applyFreeze(level, first, 80, level.getGameTime());
            published = ElementDataManager.snapshot();
            long generation = ElementDataManager.generation();
            boolean failed = false;
            KubeJsHooks.install(base -> { throw new IllegalStateException("EXPECTED_RUNTIME_VERIFY_RELOAD_FAILURE"); });
            try {
                reload(server);
            } catch (java.util.concurrent.CompletionException expected) {
                failed = true;
            } finally {
                KubeJsHooks.noop();
            }
            helper.assertTrue(failed && ElementDataManager.snapshot() == published && ElementDataManager.generation() == generation,
                    "Failed reload changed published mod data");
            helper.assertTrue(queueSize(scheduler) == 1, "Failed reload discarded old queued reaction");
            helper.assertTrue(runtime.isFrozen(level, first, level.getGameTime()), "Failed reload cleared existing active freeze");
            scheduler.drain(server);
            helper.assertTrue(runtime.isFrozen(level, target, level.getGameTime()), "Queued freeze stopped after failed reload");
            passed("actual failed resource reload preserves published data and old queue");

            var oldReservation = scheduler.reserve(server).orElseThrow();
            reload(server);
            var currentReservation = scheduler.reserve(server).orElseThrow();
            scheduler.release(oldReservation);
            helper.assertTrue(queueSize(scheduler) == 1, "Old reservation release changed current queue count");
            var currentExecution = execution(target, plan(freezeFacts(target, water), freeze));
            helper.assertTrue(!scheduler.commit(oldReservation, currentExecution), "Old reservation survived successful reload");
            helper.assertTrue(scheduler.commit(currentReservation, currentExecution), "Current reservation was rejected after reload");
            scheduler.drain(server);
            helper.assertTrue(queueSize(scheduler) == 0 && runtime.isFrozen(level, target, level.getGameTime()),
                    "Current reaction did not run after old reservation was invalidated");
            passed("real scheduler invalidates old reservation and accepts new reaction");

            var dotTarget = helper.spawn(EntityType.IRON_GOLEM, 12, 3, 3);
            dotTarget.setNoAi(true);
            dotTarget.setNoGravity(true);
            hits.set(0);
            listener = event -> {
                if (event.getEntity() == dotTarget && com.elementalphase.display.ReactionDamageContext.current().isPresent()) {
                    hits.incrementAndGet();
                    reload(server);
                }
            };
            var dot = new ReactionAction.ScheduleDamage(ResourceLocation.parse("elemental_phase:runtime_verify"), 100, 20,
                    new ReactionAction.StateDamage(constant(2), ReactionAction.DEFAULT_DOT_DAMAGE_TYPE, Optional.empty()), context -> 1);
            var dotSnapshot = new ReactionAction.StateSnapshot(
                    com.elementalphase.reaction.formula.ReactionFormulaContext.stateDamage(1, 0), Optional.empty(), 0, 0, 0xFFFFFF, false);
            facts = freezeFacts(dotTarget, water);
            enqueue(scheduler, dotTarget, new ReactionPlan(0, 0,
                    com.elementalphase.state.ElementRuntimeState.ApplyResult.APPLIED, List.of(), List.of(
                    new ReactionPlan.PlannedAction(dot, facts, Optional.of(dotSnapshot)), new ReactionPlan.PlannedAction(freeze, facts))));
            float dotInitialHealth = dotTarget.getHealth();
            withHurtListener(listener, () -> scheduler.drain(server));
            helper.assertTrue(hits.get() == 1 && dotTarget.getHealth() < dotInitialHealth,
                    "First real DOT pulse did not execute reload");
            helper.assertTrue(!runtime.isFrozen(level, dotTarget, level.getGameTime()), "Old freeze followed DOT-triggered reload");
            final float dotHealthAfterReload = dotTarget.getHealth();
            passed("first real DOT pulse reload stops remaining old actions");

            helper.runAfterDelay(25, () -> {
                helper.assertTrue(dotTarget.getHealth() == dotHealthAfterReload,
                        "Old DOT task registered itself again and delivered a later pulse after reload");
                passed("first DOT pulse reload leaves no later old pulse across real server ticks");
                helper.succeed();
            });
        }

        private static void reset(net.minecraft.world.entity.LivingEntity target) {
            target.invulnerableTime = 0;
            target.setHealth(target.getMaxHealth());
            state(target).clear();
        }

        private static ElementalState state(net.minecraft.world.entity.LivingEntity target) {
            return com.elementalphase.capability.ElementalCapabilities.get(target).resolve().orElseThrow();
        }

        private static void queueFreeze(net.minecraft.gametest.framework.GameTestHelper helper,
                                        net.minecraft.world.entity.LivingEntity target, ResourceLocation water) {
            reset(target);
            helper.assertTrue(com.elementalphase.api.ElementalPhaseApi.applyTemporary(target, water, 2).changed(),
                    "Actual capability/API rejected water attachment");
            helper.assertTrue(target.hurt(helper.getLevel().damageSources().freeze(), 1), "Actual native ice damage was rejected");
        }

        private static com.elementalphase.reaction.ReactionFacts freezeFacts(net.minecraft.world.entity.LivingEntity target,
                                                                            ResourceLocation water) {
            var data = ElementDataManager.snapshot();
            var ice = ResourceLocation.parse("elemental_phase:ice");
            var state = new ElementalState();
            state.putPermanent(water, 2, 100);
            return new ReactionEngine().react(new ReactionRequest(state, ice, 1, target.level().getGameTime(),
                    data.elements().get(ice), data.reactionIndex(), data.elements(), 1, 0, 1, target.getHealth(),
                    target.getMaxHealth(), 0, Optional.empty(), condition -> true, new ReactionChainGuard(), true))
                    .actions().get(0).facts();
        }

        private static ReactionAction.Formula constant(double value) {
            return new ReactionAction.Formula(Double.toString(value), context -> value);
        }

        private static ReactionPlan plan(com.elementalphase.reaction.ReactionFacts facts, ReactionAction... actions) {
            return new ReactionPlan(0, 0, com.elementalphase.state.ElementRuntimeState.ApplyResult.APPLIED,
                    List.of(), java.util.Arrays.stream(actions).map(action -> new ReactionPlan.PlannedAction(action, facts)).toList());
        }

        private static com.elementalphase.combat.QueuedHitExecution execution(net.minecraft.world.entity.LivingEntity target,
                                                                             ReactionPlan plan) {
            var level = (net.minecraft.server.level.ServerLevel) target.level();
            return new com.elementalphase.combat.QueuedHitExecution(level, null, target, level.damageSources().generic(), plan,
                    ElementDataManager.snapshot(), level.getGameTime(), new ReactionChainGuard());
        }

        private static void enqueue(com.elementalphase.combat.ReactionExecutionScheduler scheduler,
                                    net.minecraft.world.entity.LivingEntity target, ReactionPlan plan) {
            var execution = execution(target, plan);
            if (!scheduler.commit(scheduler.reserve(execution.level().getServer()).orElseThrow(), execution)) {
                throw new IllegalStateException("Runtime verification could not queue reaction");
            }
        }

        private static int queueSize(com.elementalphase.combat.ReactionExecutionScheduler scheduler) throws Exception {
            var method = scheduler.getClass().getDeclaredMethod("size");
            method.setAccessible(true);
            return (int) method.invoke(scheduler);
        }

        private static void reload(net.minecraft.server.MinecraftServer server) {
            server.reloadResources(server.getPackRepository().getSelectedIds()).join();
        }

        private static void withHurtListener(java.util.function.Consumer<net.minecraftforge.event.entity.living.LivingHurtEvent> listener,
                                             Runnable action) {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                    net.minecraftforge.eventbus.api.EventPriority.NORMAL, false,
                    net.minecraftforge.event.entity.living.LivingHurtEvent.class, listener);
            try { action.run(); } finally { net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(listener); }
        }

        private static void passed(String scenario) {
            LOGGER.info("RUNTIME_VERIFY_PASS: {}", scenario);
        }
    }

    @Test
    void successfulReloadDiscardsQueuedOldFreezeBeforeDrain() throws Exception {
        try (var fixture = new ReloadFixture()) {
            fixture.enqueue(fixture.freezePlan);
            fixture.commitReload();
            assertEquals(0, fixture.size());
            fixture.drain();
            assertEquals(0, ReloadRuntime.INSTANCE.freezes);
            assertEquals(1, ReloadRuntime.INSTANCE.clears);
        }
    }

    @Test
    void failedReloadPreservesPublishedDataAndQueuedFreeze() throws Exception {
        try (var fixture = new ReloadFixture()) {
            fixture.enqueue(fixture.freezePlan);
            var published = ElementDataManager.snapshot();
            KubeJsHooks.install(base -> { throw new IllegalStateException("reload failed"); });
            var failure = assertThrows(java.lang.reflect.InvocationTargetException.class, fixture::commitReload);
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertSame(published, ElementDataManager.snapshot());
            assertEquals(1, fixture.size());
            fixture.drain();
            assertEquals(1, ReloadRuntime.INSTANCE.freezes);
            assertEquals(0, ReloadRuntime.INSTANCE.clears);
        }
    }

    @Test
    void damageTriggeredReloadStopsRemainingOrderedActions() throws Exception {
        try (var fixture = new ReloadFixture()) {
            fixture.target.onDamage = fixture::reloadUnchecked;
            fixture.enqueue(fixture.plan(new ReactionAction.AdditionalDamage(formula(2),
                    new ReactionAction.DamageSettings(ReactionAction.DEFAULT_DAMAGE_TYPE, Optional.empty()), c -> 1),
                    fixture.freezePlan.actions().get(0).action()));
            fixture.drain();
            assertEquals(1, fixture.target.hits);
            assertEquals(1, ReloadRuntime.INSTANCE.clears);
            assertEquals(0, ReloadRuntime.INSTANCE.freezes);
            assertEquals(0, fixture.size());
        }
    }

    @Test
    void damageTriggeredReloadStopsAreaAttachmentAndFurtherTargets() throws Exception {
        try (var fixture = new ReloadFixture()) {
            var first = fixture.neighbor(1);
            var second = fixture.neighbor(2);
            first.onDamage = fixture::reloadUnchecked;
            fixture.enqueue(fixture.plan(new ReactionAction.Area(formula(8),
                    Optional.of(new ReactionAction.AreaDamageValue(formula(2),
                            new ReactionAction.DamageSettings(ReactionAction.DEFAULT_DAMAGE_TYPE, Optional.empty()), false)),
                    Optional.of(new ReactionAction.AreaAttachment(ReactionAction.ElementReference.fixed(fixture.water), formula(1))),
                    false, Optional.of(8), c -> 1)));
            fixture.drain();
            assertEquals(1, first.hits);
            assertEquals(0, second.hits);
            assertNull(first.state.state(fixture.water));
            assertNull(second.state.state(fixture.water));
            assertEquals(1, ReloadRuntime.INSTANCE.clears);
        }
    }

    @Test
    void reloadWithinSpecialStopsLaterSpecialEntries() throws Exception {
        try (var fixture = new ReloadFixture()) {
            fixture.target.onIgnite = fixture::reloadUnchecked;
            fixture.enqueue(fixture.plan(new ReactionAction.Special(List.of(
                    new ReactionAction.SpecialEntry(ReactionAction.SpecialEntry.Kind.IGNITE, 100),
                    new ReactionAction.SpecialEntry(ReactionAction.SpecialEntry.Kind.FREEZE, 100)), c -> 1)));
            fixture.drain();
            assertEquals(100, fixture.target.fireTicks);
            assertEquals(1, ReloadRuntime.INSTANCE.clears);
            assertEquals(0, ReloadRuntime.INSTANCE.freezes);
        }
    }

    @Test
    void clearInvalidatesOutstandingReservationsWithoutChangingNewReservations() throws Exception {
        try (var fixture = new ReloadFixture()) {
            var old = fixture.reserve();
            fixture.commitReload();
            assertEquals(0, fixture.size());
            var current = fixture.reserve();
            fixture.release(old);
            assertEquals(1, fixture.size());
            assertFalse(fixture.commit(old, fixture.execution(fixture.freezePlan)));
            assertTrue(fixture.commit(current, fixture.execution(fixture.freezePlan)));
            fixture.drain();
            assertEquals(0, fixture.size());
            assertEquals(1, ReloadRuntime.INSTANCE.freezes);
        }
    }

    @Test
    void unchangedDataAllowsAreaDamageAndAttachment() throws Exception {
        try (var fixture = new ReloadFixture()) {
            var first = fixture.neighbor(1);
            var second = fixture.neighbor(2);
            fixture.enqueue(fixture.plan(new ReactionAction.Area(formula(8),
                    Optional.of(new ReactionAction.AreaDamageValue(formula(2),
                            new ReactionAction.DamageSettings(ReactionAction.DEFAULT_DAMAGE_TYPE, Optional.empty()), false)),
                    Optional.of(new ReactionAction.AreaAttachment(ReactionAction.ElementReference.fixed(fixture.water), formula(1))),
                    false, Optional.of(8), c -> 1)));
            fixture.drain();
            assertEquals(1, first.hits);
            assertEquals(1, second.hits);
            assertNotNull(first.state.state(fixture.water));
            assertNotNull(second.state.state(fixture.water));
            assertEquals(0, ReloadRuntime.INSTANCE.clears);
        }
    }

    private static ReactionAction.Formula formula(double value) {
        return new ReactionAction.Formula(Double.toString(value), c -> value);
    }

    public static class ReloadEntity {
        public java.util.UUID getUUID() { return new java.util.UUID(0, 1); }
        public int getId() { return 0; }
    }

    public static class ReloadLiving extends ReloadEntity {
        public ReloadLevel world;
        public final ElementalState state = new ElementalState();
        public int id, hits, fireTicks;
        public Runnable onDamage = () -> {}, onIgnite = () -> {};
        public ReloadLevel level() { return world; }
        public EntityType<?> getType() { return EntityType.ZOMBIE; }
        public boolean isAlive() { return true; }
        public boolean isRemoved() { return false; }
        public float getHealth() { return 20; }
        public float getMaxHealth() { return 20; }
        public Vec3 position() { return new Vec3(id, 0, 0); }
        public int getId() { return id; }
        public boolean fireImmune() { return false; }
        public int getRemainingFireTicks() { return fireTicks; }
        public void setRemainingFireTicks(int value) { fireTicks = value; onIgnite.run(); }
        public boolean hurt(ReloadDamageSource source, float amount) { hits++; onDamage.run(); return true; }
    }

    public static class ReloadPlayer extends ReloadLiving {}

    public static class ReloadResources {
        public ReloadableServerResources manager;
        public ReloadableServerResources managers() { return manager; }
    }

    public static class ReloadPlayers {
        public List<ReloadPlayer> getPlayers() { return List.of(); }
    }

    public static class ReloadServer {
        public ReloadResources resources = new ReloadResources();
        public RegistryAccess.Frozen registries;
        public List<ReloadLevel> levels = new ArrayList<>();
        public ReloadResources getServerResources() { return resources; }
        public RegistryAccess.Frozen registryAccess() { return registries; }
        public Iterable<ReloadLevel> getAllLevels() { return levels; }
        public ReloadPlayers getPlayerList() { return new ReloadPlayers(); }
        public int getTickCount() { return 100; }
    }

    public static class ReloadLevel {
        public ReloadServer server;
        public final List<ReloadLiving> entities = new ArrayList<>();
        public ReloadServer getServer() { return server; }
        public RegistryAccess registryAccess() { return server.registries; }
        public Iterable<ReloadLiving> getAllEntities() { return entities; }
        public List<ReloadLiving> getEntitiesOfClass(Class<?> type, AABB bounds) { return entities; }
        public long getGameTime() { return 100; }
    }

    public static class ReloadDamageSource {
        private final Holder<DamageType> type;
        public ReloadDamageSource(Holder<DamageType> type, ReloadEntity direct, ReloadEntity attacker, Vec3 position) {
            this.type = type;
        }
        public ReloadEntity getDirectEntity() { return null; }
        public ReloadEntity getEntity() { return null; }
        public Vec3 sourcePositionRaw() { return null; }
        public Holder<DamageType> typeHolder() { return type; }
    }

    public static class ReloadCaps {
        public static LazyOptional<ElementalState> get(ReloadLiving entity) { return LazyOptional.of(() -> entity.state); }
    }

    public static class ReloadApi {
        public static double getResistance(ReloadLiving entity, ResourceLocation element) { return 0; }
        public static double getReactionResistance(ReloadLiving entity, ResourceLocation reaction) { return 0; }
    }

    public static class ReloadNetwork {
        public static void sendElementCatalog(ReloadPlayer player) {}
    }

    public static class ReloadNumbers {
        public static void record(ReloadDamageSource source, long tick,
                                  com.elementalphase.display.PendingMainDamageTracker.Appearance appearance) {}
        public static com.elementalphase.display.PendingMainDamageTracker.Appearance appearance(
                com.elementalphase.reaction.ReactionOutcome.TriggeredReaction reaction) {
            return new com.elementalphase.display.PendingMainDamageTracker.Appearance(reaction.displayColor(), List.of());
        }
    }

    public static class ReloadConfigValue {
        private final Object value;
        public ReloadConfigValue(Object value) { this.value = value; }
        public Object get() { return value; }
    }

    public static class ReloadConfig {
        public static final ReloadConfigValue MAX_RADIUS = new ReloadConfigValue(32.0D);
        public static final ReloadConfigValue MAX_TARGETS = new ReloadConfigValue(64);
    }

    public static class ReloadRuntime {
        public static final ReloadRuntime INSTANCE = new ReloadRuntime();
        public int freezes, clears;
        public void clear(ReloadServer server) { clears++; }
        public void applyFreeze(ReloadLevel level, ReloadLiving target, int duration, long now) { freezes++; }
    }

    private static class ReloadLoader extends ClassLoader {
        ReloadLoader() { super(ReactionDamageTargetTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }

    private static class ReloadFixture implements AutoCloseable {
        final ElementDataSnapshot original = ElementDataManager.baseSnapshot();
        final ResourceLocation water = ResourceLocation.parse("elemental_phase:water");
        final ReloadServer server = new ReloadServer();
        final ReloadLevel level = new ReloadLevel();
        final ReloadLiving target = new ReloadLiving();
        final ReactionPlan freezePlan;
        final Class<?> schedulerType, reservationType, executionType, commonType;
        final Object scheduler;
        final Map<String, String> mappings = new java.util.HashMap<>();
        final ReloadLoader loader = new ReloadLoader();

        ReloadFixture() throws Exception {
            var bootstrap = Class.forName("com.elementalphase.enchantment.ElementTestBootstrap").getDeclaredMethod("initialize");
            bootstrap.setAccessible(true);
            bootstrap.invoke(null);
            var damageRegistry = new MappedRegistry<DamageType>(Registries.DAMAGE_TYPE, com.mojang.serialization.Lifecycle.stable());
            damageRegistry.register(ResourceKey.create(Registries.DAMAGE_TYPE, ReactionAction.DEFAULT_DAMAGE_TYPE),
                    new DamageType("reaction", 0), com.mojang.serialization.Lifecycle.stable());
            server.registries = new RegistryAccess.ImmutableRegistryAccess(List.of(damageRegistry)).freeze();
            var resources = new java.util.HashMap<ResourceLocation, com.google.gson.JsonElement>();
            for (var path : List.of("elements/ice", "elements/water", "reactions/frozen")) {
                try (var input = ReactionDamageTargetTest.class.getClassLoader().getResourceAsStream(
                        "data/elemental_phase/elemental_phase/" + path + ".json")) {
                    resources.put(ResourceLocation.parse("elemental_phase:elemental_phase/" + path + ".json"),
                            JsonParser.parseReader(new InputStreamReader(java.util.Objects.requireNonNull(input))));
                }
            }
            KubeJsHooks.noop();
            var report = new ElementDataParser().parseLenient(resources);
            assertTrue(report.errors().isEmpty());
            ElementDataManager.replace(report.snapshot(), server.registries);
            var data = ElementDataManager.snapshot();
            var ice = ResourceLocation.parse("elemental_phase:ice");
            target.state.putPermanent(water, 2, 100);
            freezePlan = new ReactionEngine().react(new ReactionRequest(target.state, ice, 1, 100,
                    data.elements().get(ice), data.reactionIndex(), data.elements(), 8, 0, 1, 20, 20, 0,
                    Optional.empty(), condition -> true, new ReactionChainGuard(), true));
            assertEquals(1, freezePlan.actions().size());
            level.server = server;
            target.world = level;
            level.entities.add(target);
            server.levels.add(level);
            var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            server.resources.manager = (ReloadableServerResources) ((sun.misc.Unsafe) unsafeField.get(null))
                    .allocateInstance(ReloadableServerResources.class);
            map("net/minecraft/server/MinecraftServer", ReloadServer.class);
            map("net/minecraft/server/MinecraftServer$ReloadableResources", ReloadResources.class);
            map("net/minecraft/server/players/PlayerList", ReloadPlayers.class);
            map("net/minecraft/server/level/ServerLevel", ReloadLevel.class);
            map("net/minecraft/world/level/Level", ReloadLevel.class);
            map("net/minecraft/world/entity/Entity", ReloadEntity.class);
            map("net/minecraft/world/entity/LivingEntity", ReloadLiving.class);
            map("net/minecraft/world/entity/player/Player", ReloadPlayer.class);
            map("net/minecraft/server/level/ServerPlayer", ReloadPlayer.class);
            map("net/minecraft/world/damagesource/DamageSource", ReloadDamageSource.class);
            map("com/elementalphase/capability/ElementalCapabilities", ReloadCaps.class);
            map("com/elementalphase/api/ElementalPhaseApi", ReloadApi.class);
            map("com/elementalphase/network/ModNetwork", ReloadNetwork.class);
            map("com/elementalphase/integration/damagenumber/DamageNumberCompat", ReloadNumbers.class);
            map("com/elementalphase/config/ElementalPhaseServerConfig", ReloadConfig.class);
            map("net/minecraftforge/common/ForgeConfigSpec$DoubleValue", ReloadConfigValue.class);
            map("net/minecraftforge/common/ForgeConfigSpec$IntValue", ReloadConfigValue.class);
            map("com/elementalphase/reaction/runtime/ReactionRuntimeController", ReloadRuntime.class);
            var nativeTypes = List.of("com/elementalphase/combat/QueuedHitExecution",
                    "com/elementalphase/effect/ReactionActionExecutor", "com/elementalphase/effect/ReactionActionExecutor$DamageOutcome",
                    "com/elementalphase/combat/ReactionExecutionScheduler", "com/elementalphase/combat/ReactionExecutionScheduler$Reservation",
                    "com/elementalphase/combat/ReactionExecutionScheduler$Entry", "com/elementalphase/profile/EntityProfileResolver",
                    "com/elementalphase/profile/EntityProfileResolver$ResolvedEntityProfile", "com/elementalphase/event/CommonEvents");
            for (var type : nativeTypes) mappings.put(type, "com/elementalphase/effect/ReloadChecked" + type.substring(type.lastIndexOf('/') + 1));
            var types = new java.util.HashMap<String, Class<?>>();
            for (var type : nativeTypes) types.put(type, define(type));
            var executorType = types.get("com/elementalphase/effect/ReactionActionExecutor");
            schedulerType = types.get("com/elementalphase/combat/ReactionExecutionScheduler");
            reservationType = types.get("com/elementalphase/combat/ReactionExecutionScheduler$Reservation");
            executionType = types.get("com/elementalphase/combat/QueuedHitExecution");
            commonType = types.get("com/elementalphase/event/CommonEvents");
            scheduler = schedulerType.getConstructor(executorType).newInstance(executorType.getConstructor().newInstance());
            field("REACTION_SCHEDULER", scheduler);
            field("PROFILE_RESOLVER", types.get("com/elementalphase/profile/EntityProfileResolver").getConstructor().newInstance());
            field("LOGGER", com.mojang.logging.LogUtils.getLogger());
            ReloadRuntime.INSTANCE.clears = 0;
            ReloadRuntime.INSTANCE.freezes = 0;
        }

        void map(String name, Class<?> type) { mappings.put(name, org.objectweb.asm.Type.getInternalName(type)); }
        Class<?> define(String name) throws Exception {
            var node = new ClassNode();
            try (var input = ReactionDamageTargetTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
                new ClassReader(java.util.Objects.requireNonNull(input)).accept(node, 0);
            }
            if (name.equals("com/elementalphase/event/CommonEvents")) {
                node.visibleAnnotations = null;
                node.invisibleAnnotations = null;
                node.fields.forEach(field -> field.access &= ~Opcodes.ACC_FINAL);
                node.methods.removeIf(method -> !method.name.equals("commitStaged") && !method.name.startsWith("lambda$commitStaged"));
                node.methods.forEach(method -> method.access = (method.access & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PUBLIC);
            }
            var writer = new ClassWriter(0);
            node.accept(new ClassRemapper(writer, new Remapper() {
                @Override public String map(String type) { return mappings.getOrDefault(type, type); }
            }));
            return loader.define(mappings.get(name).replace('/', '.'), writer.toByteArray());
        }
        void field(String name, Object value) throws Exception {
            var field = commonType.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        }
        ReactionPlan plan(ReactionAction... actions) {
            return new ReactionPlan(0, 0, freezePlan.applyResult(), List.of(), java.util.Arrays.stream(actions)
                    .map(action -> new ReactionPlan.PlannedAction(action, freezePlan.actions().get(0).facts())).toList());
        }
        Object execution(ReactionPlan plan) throws Exception {
            var type = server.registries.registryOrThrow(Registries.DAMAGE_TYPE)
                    .getHolderOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, ReactionAction.DEFAULT_DAMAGE_TYPE));
            return executionType.getConstructors()[0].newInstance(level, null, target,
                    new ReloadDamageSource(type, null, null, null), plan, ElementDataManager.snapshot(), 100L, new ReactionChainGuard());
        }
        Object reserve() throws Exception {
            return ((Optional<?>) schedulerType.getMethod("reserve", ReloadServer.class).invoke(scheduler, server)).orElseThrow();
        }
        boolean commit(Object reservation, Object execution) throws Exception {
            return (boolean) schedulerType.getMethod("commit", reservationType, executionType).invoke(scheduler, reservation, execution);
        }
        void release(Object reservation) throws Exception { schedulerType.getMethod("release", reservationType).invoke(scheduler, reservation); }
        void enqueue(ReactionPlan plan) throws Exception { assertTrue(commit(reserve(), execution(plan))); }
        void drain() throws Exception { schedulerType.getMethod("drain", ReloadServer.class).invoke(scheduler, server); }
        int size() throws Exception {
            var method = schedulerType.getDeclaredMethod("size");
            method.setAccessible(true);
            return (int) method.invoke(scheduler);
        }
        void commitReload() throws Exception {
            var next = new ElementDataSnapshot(ElementDataManager.snapshot().elements(), Map.of(), null, List.of());
            ElementDataManager.stage(server.resources.manager, new ElementDataParser.ParseReport(next, List.of()));
            commonType.getMethod("commitStaged", ReloadServer.class).invoke(null, server);
        }
        void reloadUnchecked() {
            try { commitReload(); } catch (Exception exception) { throw new AssertionError(exception); }
        }
        ReloadLiving neighbor(int id) {
            var entity = new ReloadLiving();
            entity.world = level;
            entity.id = id;
            level.entities.add(entity);
            return entity;
        }
        @Override public void close() {
            KubeJsHooks.noop();
            ElementDataManager.replace(original, server.registries);
        }
    }

    @Test
    void configuredCapsCanExceedTheirDefaultsAndDamageRunsFirst() {
        assertEquals(48, ReactionActionExecutor.effectiveRadius(48, 96));
        assertEquals(96, ReactionActionExecutor.effectiveRadius(120, 96));
        assertEquals(0, ReactionActionExecutor.effectiveRadius(Double.NaN, 96));
        assertEquals(128, ReactionActionExecutor.effectiveTargetLimit(java.util.Optional.empty(), 128));
        var events = new java.util.ArrayList<String>();
        ReactionActionExecutor.runAreaParts(false, () -> events.add("damage"), () -> true, () -> events.add("attachment"));
        assertEquals(java.util.List.of("damage", "attachment"), events);
        events.clear();
        ReactionActionExecutor.runAreaParts(false, () -> events.add("damage"), () -> false, () -> events.add("attachment"));
        assertEquals(java.util.List.of("damage"), events);
    }

    private record Candidate(int id, double distanceSquared) {}

    @Test
    void parentAndChildSelectionsShareAndExhaustTheSameBudget() {
        var budget = new com.elementalphase.combat.ReactionExecutionBudget(1, 2);
        var far = new Candidate(1, 4);
        var near = new Candidate(2, 1);
        var unchecked = new Candidate(3, 0);
        var checked = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Predicate<Candidate> valid = candidate -> { checked.incrementAndGet(); return true; };
        var parent = ReactionActionExecutor.selectCandidates(null, null, java.util.List.of(far, near, unchecked),
                valid, Candidate::distanceSquared, Candidate::id, 100, 1, false, false, budget);
        assertEquals(java.util.List.of(near), parent);
        assertEquals(2, checked.get());
        assertEquals(0, budget.targetOperationsRemaining());
        assertTrue(ReactionActionExecutor.selectCandidates(null, null, java.util.List.of(unchecked), valid,
                Candidate::distanceSquared, Candidate::id, 100, 1, false, false, budget).isEmpty());
        assertEquals(2, checked.get());
    }

    @Test
    void attackerSharesLimitAndPrimaryDuplicatesAndOutsideAreExcluded() {
        var original = new Candidate(1, 0);
        var attacker = new Candidate(8, 4);
        var nearby = new Candidate(3, 4);
        var outside = new Candidate(9, 100);
        var budget = new com.elementalphase.combat.ReactionExecutionBudget(1, 20);
        assertEquals(java.util.List.of(nearby), ReactionActionExecutor.selectCandidates(original, attacker,
                java.util.List.of(original, attacker, nearby, nearby, outside), ignored -> true,
                Candidate::distanceSquared, Candidate::id, 9, 1, false, true, budget));
        assertEquals(17, budget.targetOperationsRemaining());
    }

    @Test
    void rejectedDamageStillAllowsAttachmentForValidTargets() {
        for (var outcome : ReactionActionExecutor.DamageOutcome.values()) {
            var events = new java.util.ArrayList<String>();
            ReactionActionExecutor.runAreaParts(false, () -> events.add(outcome.name()), () -> true, () -> events.add("attachment"));
            assertEquals(java.util.List.of(outcome.name(), "attachment"), events);
        }
    }

    @Test
    void includedPrimaryTakesDamageOnceWithoutAttachment() {
        var original = new Candidate(8, 0);
        var nearby = new Candidate(3, 4);
        var budget = new com.elementalphase.combat.ReactionExecutionBudget(1, 20);
        var targets = ReactionActionExecutor.selectCandidates(original, null,
                java.util.List.of(original, nearby, original, nearby), ignored -> true,
                Candidate::distanceSquared, Candidate::id, 9, 10, true, false, budget);
        assertEquals(java.util.List.of(original, nearby), targets);
        assertEquals(18, budget.targetOperationsRemaining());

        var events = new java.util.ArrayList<String>();
        for (var target : targets) {
            ReactionActionExecutor.runAreaParts(target == original, () -> events.add(target.id() + ":damage"),
                    () -> true, () -> events.add(target.id() + ":attachment"));
        }
        assertEquals(java.util.List.of("8:damage", "3:damage", "3:attachment"), events);
    }

    @Test
    void includedPrimaryOccupiesFirstTargetSlotEvenWhenEntitiesOverlap() {
        var original = new Candidate(8, 0);
        var overlapping = new Candidate(1, 0);
        var budget = new com.elementalphase.combat.ReactionExecutionBudget(1, 20);
        assertEquals(java.util.List.of(original), ReactionActionExecutor.selectCandidates(original, null,
                java.util.List.of(overlapping), ignored -> true, Candidate::distanceSquared, Candidate::id,
                9, 1, true, false, budget));
    }

    @Test
    void includedPrimarySharesAndExhaustsTheTargetBudget() {
        var original = new Candidate(8, 0);
        var nearby = new Candidate(3, 4);
        var unchecked = new Candidate(2, 1);
        var budget = new com.elementalphase.combat.ReactionExecutionBudget(1, 2);
        var checked = new java.util.ArrayList<Candidate>();
        assertEquals(java.util.List.of(original, nearby), ReactionActionExecutor.selectCandidates(original, null,
                java.util.List.of(original, nearby, unchecked), candidate -> { checked.add(candidate); return true; },
                Candidate::distanceSquared, Candidate::id, 9, 10, true, false, budget));
        assertEquals(java.util.List.of(original, nearby), checked);
        assertEquals(0, budget.targetOperationsRemaining());
        assertTrue(ReactionActionExecutor.selectCandidates(original, null, java.util.List.of(nearby),
                ignored -> fail("Exhausted budget must not check targets"), Candidate::distanceSquared,
                Candidate::id, 9, 10, true, false, budget).isEmpty());
    }

    @Test
    void invalidPrimaryIsSkippedAndAttackerCannotSuppressIncludedPrimary() {
        var original = new Candidate(8, 0);
        var nearby = new Candidate(3, 4);
        var invalidBudget = new com.elementalphase.combat.ReactionExecutionBudget(1, 20);
        assertEquals(java.util.List.of(nearby), ReactionActionExecutor.selectCandidates(original, null,
                java.util.List.of(original, nearby), candidate -> candidate != original,
                Candidate::distanceSquared, Candidate::id, 9, 10, true, false, invalidBudget));
        assertEquals(18, invalidBudget.targetOperationsRemaining());

        assertEquals(java.util.List.of(original), ReactionActionExecutor.selectCandidates(original, original,
                java.util.List.of(original), ignored -> true, Candidate::distanceSquared, Candidate::id,
                9, 10, true, false, new com.elementalphase.combat.ReactionExecutionBudget(1, 20)));
    }
}
