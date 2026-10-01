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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledDamageStateTest {
    private static final AtomicInteger EXECUTION_SEQUENCE = new AtomicInteger();

    @Test
    void crossDimensionPlayerKeepsAttributionAndCannotBypassFriendlyFire() throws Exception {
        var execute = damageBoundary();
        var level = new DamageLevel();
        var team = new DamageTeam();
        var attacker = new DamageServerPlayer(level.server, team);
        UUID id = UUID.randomUUID();
        level.server.players.online.put(id, attacker);
        var target = new DamageServerPlayer(level.server, team);
        var state = playerState(id, "archer");

        assertFalse(allowed(execute, level, target, state));
        assertSame(attacker, target.checkedAttacker);
        team.friendlyFire = true;
        assertTrue(allowed(execute, level, target, state));
        assertSame(attacker, level.lastAttacker);
    }

    @Test
    void deadOnlinePlayerStillObeysPvpAndTeamRulesBeforeSourceFiltering() throws Exception {
        var execute = damageBoundary();
        var level = new DamageLevel();
        var team = new DamageTeam();
        var attacker = new DamageServerPlayer(level.server, team);
        attacker.alive = false;
        UUID id = UUID.randomUUID();
        level.server.players.online.put(id, attacker);
        level.entities.put(id, attacker);
        var target = new DamageServerPlayer(level.server, team);
        var state = playerState(id, "archer");

        level.server.pvp = false;
        assertFalse(allowed(execute, level, target, state));
        assertSame(attacker, target.checkedAttacker);
        level.server.pvp = true;
        assertFalse(allowed(execute, level, target, state));
        team.friendlyFire = true;
        assertTrue(allowed(execute, level, target, state));
        org.junit.jupiter.api.Assertions.assertNull(level.lastAttacker);
    }

    @Test
    void offlinePlayerCannotBypassDisabledPvpAndResumesWhenItIsEnabled() throws Exception {
        var execute = damageBoundary();
        var level = new DamageLevel();
        var target = new DamageServerPlayer(level.server, null);
        var state = playerState(UUID.randomUUID(), "archer");

        level.server.pvp = false;
        assertFalse(allowed(execute, level, target, state));
        level.server.pvp = true;
        assertTrue(allowed(execute, level, target, state));
    }

    @Test
    void offlinePlayerUsesCurrentTeamsAndFriendlyFireForEveryPulse() throws Exception {
        var execute = damageBoundary();
        var level = new DamageLevel();
        var team = new DamageTeam();
        level.server.scoreboard.teams.put("archer", team);
        var target = new DamageServerPlayer(level.server, team);
        var state = playerState(UUID.randomUUID(), "archer");

        assertFalse(allowed(execute, level, target, state));
        team.friendlyFire = true;
        assertTrue(allowed(execute, level, target, state));
        team.friendlyFire = false;
        assertFalse(allowed(execute, level, target, state));
        target.team = new DamageTeam();
        assertTrue(allowed(execute, level, target, state));
        level.server.scoreboard.teams.put("archer", target.team);
        assertFalse(allowed(execute, level, target, state));
        level.server.scoreboard.teams.remove("archer");
        assertTrue(allowed(execute, level, target, state));
    }

    @Test
    void cachedCurrentPlayerNameProtectsLegacyAndRenamedSources() throws Exception {
        var execute = damageBoundary();
        var level = new DamageLevel();
        UUID id = UUID.randomUUID();
        var team = new DamageTeam();
        var target = new DamageServerPlayer(level.server, team);
        level.server.profiles.profiles.put(id, new com.mojang.authlib.GameProfile(id, "current_name"));
        level.server.scoreboard.teams.put("current_name", team);

        assertFalse(allowed(execute, level, target, playerState(id, null)));
        assertFalse(allowed(execute, level, target, playerState(id, "old_name")));
        level.server.profiles.profiles.put(id, new com.mojang.authlib.GameProfile(id, "another_name"));
        assertTrue(allowed(execute, level, target, playerState(id, "old_name")));
    }

    @Test
    void missingProfileCacheFallsBackToSavedPlayerIdentity() throws Exception {
        var execute = damageBoundary();
        var level = new DamageLevel();
        level.server.profiles = null;
        level.server.pvp = false;

        assertFalse(allowed(execute, level, new DamageServerPlayer(level.server, null),
                playerState(UUID.randomUUID(), "archer")));
    }

    @Test
    void missingMobSourceStillHurtsPlayersWhenPvpIsDisabled() throws Exception {
        var execute = damageBoundary();
        var level = new DamageLevel();
        level.server.pvp = false;
        var target = new DamageServerPlayer(level.server, null);
        var state = playerState(UUID.randomUUID(), null);

        assertTrue(allowed(execute, level, target, state));
        org.junit.jupiter.api.Assertions.assertNull(level.lastAttacker);
        var mob = new DamageLiving();
        level.entities.put(state.source().attacker().orElseThrow(), mob);
        assertTrue(allowed(execute, level, target, state));
        assertSame(mob, level.lastAttacker);
        mob.alive = false;
        assertTrue(allowed(execute, level, target, state));
        org.junit.jupiter.api.Assertions.assertNull(level.lastAttacker);
    }

    @Test
    void nonPlayerTargetsRemainHittableWithPvpAndFriendlyFireDisabled() throws Exception {
        var execute = damageBoundary();
        var level = new DamageLevel();
        level.server.pvp = false;
        var team = new DamageTeam();
        level.server.scoreboard.teams.put("archer", team);

        assertTrue(allowed(execute, level, new DamageLiving(), playerState(UUID.randomUUID(), "archer")));
    }

    @Test
    void damageUsesSavedContextAndBothResistancesWhileDisplayUsesEffectIdentity() {
        var snapshot = new ReactionAction.StateSnapshot(
                com.elementalphase.reaction.formula.ReactionFormulaContext.legacy(100, 4),
                Optional.of(id("lightning")), 0.5, 0.5, 0xB388FF, false);
        var damage = new ReactionAction.StateDamage(new ReactionAction.Formula("original_damage + scale",
                DamageFormulaParser.parseReaction("original_damage + scale")), id("reaction_dot"),
                Optional.of(ReactionAction.ElementReference.aura()));
        var state = ScheduledDamageState.create(id("custom_mark"), id("origin"), 4, 100, 100, 20,
                damage, source("missing_actor"), snapshot);
        assertEquals(26, ScheduledReactionDamageExecutor.damageAmount(state));
        var label = ScheduledReactionDamageExecutor.label(state, 26);
        assertEquals(id("custom_mark"), label.id());
        assertEquals(id("origin"), label.originReactionId());
        assertEquals(0xB388FF, label.displayColor());
        assertFalse(label.showName());
    }

    @Test
    void totalAttemptsIncludeOneSeparateFirstPulse() {
        for (int duration : new int[]{100, 95, 0, 10}) {
            var state = state(2, 100, duration, 20, source("actor"));
            int attempts = 1;
            for (long now = 100; now <= 100 + duration; now++) if (state.due(now, Long.MIN_VALUE)) attempts++;
            assertEquals(1 + duration / 20, attempts);
        }
    }

    @Test
    void schedulesOnlyIntervalTicksThroughInclusiveEnd() {
        var state = state(5.0D, 100L, 100, 20, source("old"));

        for (long tick : new long[]{120, 140, 160, 180, 200}) {
            assertTrue(state.due(tick, Long.MIN_VALUE), "tick " + tick);
        }
        for (long tick : new long[]{100, 101, 199, 201}) {
            assertFalse(state.due(tick, Long.MIN_VALUE), "tick " + tick);
        }
        assertFalse(state.due(120L, 120L));
        assertFalse(state.expiredAfter(200L));
        assertTrue(state.expiredAfter(201L));
    }

    @Test
    void doesNotCreateSyntheticHitAtNonDivisibleEnd() {
        var state = state(5.0D, 100L, 95, 20, source("old"));

        assertTrue(state.due(180L, Long.MIN_VALUE));
        assertFalse(state.due(195L, Long.MIN_VALUE));
        assertTrue(state.expiredAfter(196L));
    }

    @Test
    void replacesHigherRefreshesEqualAndIgnoresLower() {
        var original = state(5.0D, 100L, 100, 20, source("old"));

        var higher = ScheduledDamageState.apply(original, state(6, 120, 80, 10, source("higher")));
        assertEquals(ScheduledDamageState.ApplyResult.REPLACED_HIGHER, higher.result());
        assertEquals(6.0D, higher.state().scale());
        assertEquals(120L, higher.state().startedAt());
        assertEquals(id("higher"), higher.state().source().sourceId());

        var equal = ScheduledDamageState.apply(higher.state(), state(6, 130, 60, 15, source("equal")));
        assertEquals(ScheduledDamageState.ApplyResult.REFRESHED_EQUAL, equal.result());
        assertEquals(130L, equal.state().startedAt());
        assertEquals(id("equal"), equal.state().source().sourceId());

        var lower = ScheduledDamageState.apply(equal.state(), state(4, 140, 40, 5, source("lower")));
        assertEquals(ScheduledDamageState.ApplyResult.IGNORED_LOWER, lower.result());
        assertSame(equal.state(), lower.state());
    }

    private static java.lang.reflect.Method damageBoundary() throws Exception {
        String original = "com/elementalphase/reaction/runtime/ScheduledReactionDamageExecutor";
        String generated = "com/elementalphase/reaction/runtime/ScheduledDamageStateTest$Execution"
                + EXECUTION_SEQUENCE.incrementAndGet();
        var types = new HashMap<String, String>();
        types.put(original, generated);
        Map<String, Class<?>> boundaries = Map.ofEntries(
                Map.entry("net/minecraft/server/level/ServerLevel", DamageLevel.class),
                Map.entry("net/minecraft/server/MinecraftServer", DamageServer.class),
                Map.entry("net/minecraft/server/players/PlayerList", DamagePlayers.class),
                Map.entry("net/minecraft/world/entity/Entity", DamageEntity.class),
                Map.entry("net/minecraft/world/entity/LivingEntity", DamageLiving.class),
                Map.entry("net/minecraft/world/entity/player/Player", DamagePlayer.class),
                Map.entry("net/minecraft/server/level/ServerPlayer", DamageServerPlayer.class),
                Map.entry("net/minecraft/world/scores/Team", DamageTeam.class),
                Map.entry("net/minecraft/world/scores/PlayerTeam", DamageTeam.class),
                Map.entry("net/minecraft/server/ServerScoreboard", DamageScoreboard.class),
                Map.entry("net/minecraft/server/players/GameProfileCache", DamageProfiles.class),
                Map.entry("com/elementalphase/state/ElementSourceSnapshot", DamageSource.class),
                Map.entry("com/elementalphase/reaction/runtime/ScheduledDamageState", DamageState.class));
        boundaries.forEach((name, type) -> types.put(name, org.objectweb.asm.Type.getInternalName(type)));
        var remapper = new Remapper() {
            @Override
            public String map(String name) { return types.getOrDefault(name, name); }
        };
        var node = new ClassNode();
        try (var stream = ScheduledDamageStateTest.class.getResourceAsStream("/" + original + ".class")) {
            org.junit.jupiter.api.Assertions.assertNotNull(stream);
            new ClassReader(stream).accept(node, ClassReader.SKIP_FRAMES);
        }
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, generated, null,
                org.objectweb.asm.Type.getInternalName(DamageBoundary.class), null);
        for (var method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) == 0 || method.name.equals("damageAmount")
                    || method.name.equals("label") || method.name.equals("<clinit>")) continue;
            if (method.name.equals("execute")) {
                var boundary = java.util.Arrays.stream(method.instructions.toArray())
                        .filter(instruction -> instruction instanceof MethodInsnNode)
                        .map(instruction -> (MethodInsnNode) instruction)
                        .filter(call -> call.name.equals("registryAccess")).findFirst().orElseThrow();
                int attacker = method.localVariables.stream().filter(local -> local.name.equals("attacker"))
                        .findFirst().orElseThrow().index;
                for (var instruction = boundary.getNext(); instruction != null;) {
                    var next = instruction.getNext();
                    method.instructions.remove(instruction);
                    instruction = next;
                }
                method.instructions.set(boundary, new InsnNode(Opcodes.POP));
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, attacker));
                method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,
                        org.objectweb.asm.Type.getInternalName(DamageLevel.class), "lastAttacker",
                        org.objectweb.asm.Type.getDescriptor(DamageEntity.class)));
                method.instructions.add(new InsnNode(Opcodes.ICONST_1));
                method.instructions.add(new InsnNode(Opcodes.IRETURN));
                method.localVariables = null;
                method.tryCatchBlocks.clear();
            }
            int access = method.access & ~Opcodes.ACC_PRIVATE | Opcodes.ACC_PUBLIC;
            method.accept(new MethodRemapper(writer.visitMethod(access, method.name,
                    remapper.mapMethodDesc(method.desc), null, null), remapper));
        }
        writer.visitEnd();
        return MethodHandles.lookup().defineClass(writer.toByteArray()).getMethod("execute",
                DamageLevel.class, DamageLiving.class, DamageState.class);
    }

    private static boolean allowed(java.lang.reflect.Method execute, DamageLevel level, DamageLiving target,
                                   DamageState state) throws ReflectiveOperationException {
        return (boolean) execute.invoke(null, level, target, state);
    }

    private static DamageState playerState(UUID attacker, String name) {
        return new DamageState(new DamageSource(Optional.of(attacker), Optional.empty(), Optional.ofNullable(name)));
    }

    public static class DamageBoundary {
        public static double damageAmount(DamageState state) { return 1.0D; }
    }

    public record DamageState(DamageSource source) { }

    public record DamageSource(Optional<UUID> attacker, Optional<UUID> directEntity, Optional<String> playerName) { }

    public static final class DamageLevel {
        private final DamageServer server = new DamageServer();
        private final Map<UUID, DamageEntity> entities = new HashMap<>();
        public DamageEntity lastAttacker;
        public DamageEntity getEntity(UUID id) { return entities.get(id); }
        public DamageServer getServer() { return server; }
    }

    public static final class DamageServer {
        private final DamagePlayers players = new DamagePlayers();
        private final DamageScoreboard scoreboard = new DamageScoreboard();
        private DamageProfiles profiles = new DamageProfiles();
        private boolean pvp = true;
        public DamagePlayers getPlayerList() { return players; }
        public DamageScoreboard getScoreboard() { return scoreboard; }
        public DamageProfiles getProfileCache() { return profiles; }
        public boolean isPvpAllowed() { return pvp; }
    }

    public static final class DamagePlayers {
        private final Map<UUID, DamageServerPlayer> online = new HashMap<>();
        public DamageServerPlayer getPlayer(UUID id) { return online.get(id); }
    }

    public static final class DamageScoreboard {
        private final Map<String, DamageTeam> teams = new HashMap<>();
        public DamageTeam getPlayersTeam(String name) { return teams.get(name); }
    }

    public static final class DamageProfiles {
        private final Map<UUID, com.mojang.authlib.GameProfile> profiles = new HashMap<>();
        public Optional<com.mojang.authlib.GameProfile> get(UUID id) { return Optional.ofNullable(profiles.get(id)); }
    }

    public static class DamageEntity {
        public boolean isRemoved() { return false; }
    }

    public static class DamageLiving extends DamageEntity {
        public boolean alive = true;
        public boolean isAlive() { return alive; }
    }

    public static class DamagePlayer extends DamageLiving {
        public DamageTeam team;
        private DamagePlayer(DamageTeam team) { this.team = team; }
        public DamageTeam getTeam() { return team; }
    }

    public static final class DamageServerPlayer extends DamagePlayer {
        private final DamageServer server;
        private DamagePlayer checkedAttacker;
        private DamageServerPlayer(DamageServer server, DamageTeam team) { super(team); this.server = server; }
        public boolean canHarmPlayer(DamagePlayer attacker) {
            checkedAttacker = attacker;
            return server.pvp && (team == null || !team.isAlliedTo(attacker.team) || team.friendlyFire);
        }
    }

    public static final class DamageTeam {
        private boolean friendlyFire;
        public boolean isAlliedTo(DamageTeam other) { return this == other; }
        public boolean isAllowFriendlyFire() { return friendlyFire; }
    }

    private static ScheduledDamageState state(double scale, long now, int duration, int interval,
                                               ElementSourceSnapshot source) {
        var snapshot = new ReactionAction.StateSnapshot(
                com.elementalphase.reaction.formula.ReactionFormulaContext.stateDamage(scale, 0), Optional.empty(),
                0, 0, 0xFFFFFF, true);
        return ScheduledDamageState.create(id("mark"), id("reaction"), scale, now, duration, interval, damage(), source, snapshot);
    }

    private static ReactionAction.StateDamage damage() {
        return new ReactionAction.StateDamage(
                new ReactionAction.Formula("scale", DamageFormulaParser.parseReaction("scale")),
                id("reaction_dot"), Optional.empty());
    }

    private static ElementSourceSnapshot source(String source) {
        return new ElementSourceSnapshot(Optional.empty(), Optional.empty(), id(source), id("reaction"),
                id("lightning"), 1.0D, 0L);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }
}
