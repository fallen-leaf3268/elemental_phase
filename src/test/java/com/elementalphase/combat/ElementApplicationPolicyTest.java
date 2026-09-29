package com.elementalphase.combat;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.elementalphase.config.ElementalPhaseServerConfig;
import com.elementalphase.data.ReactionIndex;
import com.elementalphase.data.model.ElementAttachmentPolicy;
import com.elementalphase.data.model.ElementDefinition;
import com.elementalphase.data.model.ElementDisplayDefinition;
import com.elementalphase.data.model.ReactionConsumption;
import com.elementalphase.data.model.ReactionDirection;
import com.elementalphase.data.model.ReactionDisplay;
import com.elementalphase.data.model.ReactionSpec;
import com.elementalphase.data.model.ReactionAction;
import com.elementalphase.reaction.formula.DamageFormulaParser;
import com.elementalphase.state.ElementRuntimeState;
import com.elementalphase.state.ElementalState;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class ElementApplicationPolicyTest {
    private static final ResourceLocation FIRE = ResourceLocation.fromNamespaceAndPath("test", "fire");
    private static final ResourceLocation WATER = ResourceLocation.fromNamespaceAndPath("test", "water");

    @Test
    void sharesCooldownAcrossSourcesForTheSameTargetElement() {
        ElementalState state = new ElementalState();

        assertEquals(ElementRuntimeState.ApplyResult.APPLIED,
                state.applyTemporary(FIRE, 5.0D, 0L, 100, 10));
        assertFalse(state.elementApplicationReady(FIRE, 1L));
        assertEquals(ElementRuntimeState.ApplyResult.BLOCKED_COOLDOWN,
                state.applyTemporary(FIRE, 8.0D, 1L, 100, 10));
        assertTrue(state.elementApplicationReady(WATER, 1L));
    }

    @Test
    void ignoredLowerAmountDoesNotStartCooldownButEqualRefreshDoes() {
        ElementalState state = new ElementalState();
        state.applyTemporaryIgnoringCooldown(FIRE, 5.0D, 0L, 100, null);

        assertEquals(ElementRuntimeState.ApplyResult.IGNORED_LOWER,
                state.applyTemporary(FIRE, 4.0D, 1L, 100, 10));
        assertTrue(state.elementApplicationReady(FIRE, 1L));
        assertEquals(ElementRuntimeState.ApplyResult.REFRESHED,
                state.applyTemporary(FIRE, 5.0D, 1L, 100, 10));
        assertFalse(state.elementApplicationReady(FIRE, 2L));
    }

    @Test
    void instantAttackElementReactsButDoesNotKeepItsRemainder() {
        ElementalState state = new ElementalState();
        state.putPermanent(FIRE, 2.0D, 100);
        ElementDefinition water = definition(WATER, false, 10.0D);

        resolve(state, water, 5.0D, reactionIndex());

        assertEquals(null, state.state(WATER));
        assertEquals(0.0D, state.state(FIRE).effectiveAmount(0L));
    }

    @Test
    void retainedAttackElementStoresOnlyTheCappedRemainder() {
        ElementalState state = new ElementalState();
        ElementDefinition water = definition(WATER, true, 3.0D);

        resolve(state, water, 8.0D, ReactionIndex.build(Map.of()));

        assertEquals(3.0D, state.state(WATER).effectiveAmount(0L));
        assertFalse(state.elementApplicationReady(WATER, 1L));
    }

    @Test
    void lowerAttackAmountDoesNotStartElementCooldown() {
        ElementalState state = new ElementalState();
        state.applyTemporaryIgnoringCooldown(WATER, 5.0D, 0L, 100, null);

        resolve(state, definition(WATER, true, 10.0D), 4.0D, ReactionIndex.build(Map.of()));

        assertEquals(5.0D, state.state(WATER).effectiveAmount(0L));
        assertTrue(state.elementApplicationReady(WATER, 1L));
    }

    @Test
    void rejectsNonFiniteAttackAmountBeforeElementSpecificClamping() {
        assertThrows(IllegalArgumentException.class, () -> new ElementAttackContext(WATER,
                Double.POSITIVE_INFINITY, ElementAttackContext.SourceKind.INTRINSIC,
                ResourceLocation.fromNamespaceAndPath("test", "source")));
    }

    @Test
    void virtualAttackWithoutReactionPersistsOnlyUntilItsShortExpiry() {
        ElementalState state = new ElementalState();
        resolve(state, definition(WATER, false, 10.0D), 5.0D, ReactionIndex.build(Map.of()));

        assertTrue(state.state(WATER).virtual());
        assertEquals(5.0D, state.state(WATER).effectiveAmount(9L));
        assertEquals(0.0D, state.state(WATER).effectiveAmount(10L));
    }

    @Test
    void virtualAuraClearsAllItsRemainderAfterReaction() {
        ElementalState state = new ElementalState();
        ElementDefinition fire = definition(FIRE, false, 10.0D);
        ElementDefinition water = definition(WATER, true, 10.0D);
        state.applyElement(fire, 8.0D, 0L, 100, true, null);
        var result = resolveAt(state, water, 1.0D, reactionIndex(), Map.of(FIRE, fire, WATER, water), 0L);

        assertEquals(1, result.reactionPlan().labels().size());
        assertEquals(0.0D, state.state(FIRE).effectiveAmount(0L));
        assertEquals(0.0D, result.reactionPlan().labels().get(0).facts().remainingAura());
        assertFalse(state.elementApplicationReady(FIRE, 1L));
    }

    @Test
    void virtualTriggerStopsAfterFirstSuccessfulReaction() {
        ResourceLocation ice = ResourceLocation.fromNamespaceAndPath("test", "ice");
        ResourceLocation second = ResourceLocation.fromNamespaceAndPath("test", "second");
        ElementalState state = new ElementalState();
        state.putPermanent(FIRE, 1.0D, 100);
        state.putPermanent(ice, 1.0D, 100);
        var direction = new ReactionDirection(WATER, ice, 0.1D,
                new ReactionConsumption(1.0D, 1.0D), List.of(), new ReactionDisplay(0xFFFFFF, true), List.of());
        var secondReaction = new ReactionSpec(second, 0, Set.of(WATER, ice), List.of(direction));
        var firstReaction = reactionIndex().candidates(WATER, FIRE).get(0).reaction();
        var reactions = new java.util.HashMap<>(Map.of(firstReaction.id(), firstReaction));
        reactions.put(second, secondReaction);
        ElementDefinition water = definition(WATER, false, 10.0D);
        var result = resolveAt(state, water, 8.0D, ReactionIndex.build(reactions),
                Map.of(FIRE, definition(FIRE, true, 10), WATER, water, ice, definition(ice, true, 10)), 0L);

        assertEquals(1, result.reactionPlan().labels().size());
        assertEquals(1.0D, state.state(ice).effectiveAmount(0L));
        assertEquals(0.0D, result.reactionPlan().labels().get(0).facts().remainingTrigger());
    }

    @Test
    void lowerVirtualAttackDoesNotReactOrRefreshButEqualAttackCanReact() {
        ElementalState state = new ElementalState();
        ElementDefinition water = definition(WATER, false, 10.0D);
        state.applyElement(water, 5.0D, 0L, 10, false, null);
        state.putPermanent(FIRE, 2.0D, 100);
        var definitions = Map.of(FIRE, definition(FIRE, true, 10), WATER, water);

        var lower = resolveAt(state, water, 4.0D, reactionIndex(), definitions, 1L);
        assertEquals(ElementRuntimeState.ApplyResult.IGNORED_LOWER, lower.applyResult());
        assertTrue(lower.reactionPlan().labels().isEmpty());
        assertEquals(10L, state.state(WATER).temporaryExpiresAt());
        assertTrue(state.elementApplicationReady(WATER, 1L));

        var equal = resolveAt(state, water, 5.0D, reactionIndex(), definitions, 1L);
        assertEquals(1, equal.reactionPlan().labels().size());
        assertEquals(0.0D, state.state(WATER).effectiveAmount(1L));
        assertFalse(state.elementApplicationReady(WATER, 2L));
    }

    @Test
    void tooSmallReactionDoesNotClearVirtualAttachment() {
        ElementalState state = new ElementalState();
        state.putPermanent(FIRE, 0.05D, 100);
        ElementDefinition water = definition(WATER, false, 10.0D);
        var result = resolveAt(state, water, 5.0D, reactionIndex(),
                Map.of(FIRE, definition(FIRE, true, 10), WATER, water), 0L);

        assertTrue(result.reactionPlan().labels().isEmpty());
        assertEquals(5.0D, state.state(WATER).effectiveAmount(0L));
        assertEquals(0.05D, state.state(FIRE).effectiveAmount(0L));
    }

    @Test
    void attackElementHookRunsAfterValidationAndBeforeNativeCooldown() throws Exception {
        var mixin = readClass("com/elementalphase/mixin/LivingEntityMixin");
        var handler = mixin.methods.stream().filter(method -> annotation(method,
                "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;") != null).findFirst().orElseThrow();
        var modify = annotation(handler, "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;");
        assertEquals(List.of("hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"), value(modify, "method"));
        assertEquals(Boolean.TRUE, value(modify, "argsOnly"));
        assertEquals(0, value(modify, "ordinal"));
        var at = (AnnotationNode) value(modify, "at");
        assertEquals("FIELD", value(at, "value"));
        assertEquals("Lnet/minecraft/world/entity/LivingEntity;invulnerableTime:I", value(at, "target"));
        assertEquals(Opcodes.GETFIELD, value(at, "opcode"));
        assertEquals(0, value(at, "ordinal"));
        try (var stream = getClass().getResourceAsStream("/elemental_phase.mixins.json")) {
            assertNotNull(stream);
            var config = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(config.getAsJsonArray("mixins").asList().stream()
                    .anyMatch(entry -> entry.getAsString().equals("LivingEntityMixin")));
        }
        var hurt = nativeHurt();
        int attack = -1, immunity = -1, shield = -1, cooldown = -1, lastHurt = -1;
        for (int i = 0; i < hurt.instructions.size(); i++) {
            var instruction = hurt.instructions.get(i);
            if (instruction instanceof MethodInsnNode call) {
                if (call.name.equals("onLivingAttack")) attack = i;
                if (call.name.equals("isInvulnerableTo")) immunity = i;
                if (call.name.equals("onShieldBlock")) shield = i;
            }
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD) {
                if (field.name.equals("invulnerableTime") && cooldown < 0) cooldown = i;
                if (field.name.equals("lastHurt") && lastHurt < 0) lastHurt = i;
            }
        }
        assertTrue(attack >= 0 && immunity > attack && shield > immunity && cooldown > shield && lastHurt > cooldown);
        var events = readClass("com/elementalphase/event/CommonEvents");
        assertFalse(events.methods.stream().anyMatch(method -> method.desc.contains("LivingHurtEvent;")
                && annotation(method, "Lnet/minecraftforge/eventbus/api/SubscribeEvent;") != null));
    }

    @Test
    void mainDamageAppearanceIsRecordedOnlyAtTheTwoAcceptedNativeBranches() throws Exception {
        var mixin = readClass("com/elementalphase/mixin/LivingEntityMixin");
        assertTrue(mixin.methods.stream().anyMatch(method -> {
            var inject = annotation(method, "Lorg/spongepowered/asm/mixin/injection/Inject;");
            if (inject == null) return false;
            var sites = (List<?>) value(inject, "at");
            return sites.stream().map(AnnotationNode.class::cast).anyMatch(at ->
                    "INVOKE".equals(value(at, "value")) &&
                            "Lnet/minecraft/world/entity/LivingEntity;actuallyHurt(Lnet/minecraft/world/damagesource/DamageSource;F)V"
                                    .equals(value(at, "target")));
        }));
        assertEquals(2, java.util.Arrays.stream(nativeHurt().instructions.toArray())
                .filter(instruction -> instruction instanceof MethodInsnNode call && call.name.equals("actuallyHurt")).count());
    }

    @Test
    void sameTickDifferentElementsReactBeforeEqualDamageIsRejected() throws Exception {
        var state = new ElementalState();
        var definitions = Map.of(FIRE, definition(FIRE, true, 10), WATER, definition(WATER, true, 10));
        var target = newNativeTarget();
        var fire = resolveHit(state, definitions, FIRE, 8, 0, 0, ReactionIndex.build(Map.of()));
        assertTrue(nativeHit(target, fire.finalPreArmorDamage(), false));
        var water = resolveHit(state, definitions, WATER, 8, 0, 0, reactionIndex());
        assertFalse(nativeHit(target, water.finalPreArmorDamage(), false));
        assertEquals(1, water.reactionPlan().labels().size());
        assertEquals(0, state.state(FIRE).effectiveAmount(0));
        assertFalse(state.elementApplicationReady(FIRE, 1));
        assertFalse(state.elementApplicationReady(WATER, 1));
        assertEquals(8, target.delivered);
        assertEquals(20, target.invulnerableTime);
    }

    @Test
    void fullAttackBonusIsMergedBeforeNativeDifferenceCalculation() throws Exception {
        var state = new ElementalState();
        state.putPermanent(FIRE, 1, 100);
        var definitions = Map.of(FIRE, definition(FIRE, true, 10), WATER, definition(WATER, true, 10));
        var target = newNativeTarget();
        target.invulnerableTime = 18;
        target.lastHurt = 8;
        var result = resolveHit(state, definitions, WATER, 8, 0, 0, amplifyingIndex());
        assertEquals(16, result.finalPreArmorDamage());
        assertEquals(8, result.reactionPlan().labels().get(0).facts().originalDamage());
        assertTrue(nativeHit(target, result.finalPreArmorDamage(), false));
        assertEquals(8, target.delivered);
        assertEquals(16, target.lastHurt);
        assertEquals(18, target.invulnerableTime);
    }

    @Test
    void rejectedAmplifiedHitStillConsumesElementsAndKeepsIndependentActions() throws Exception {
        var state = new ElementalState();
        state.putPermanent(FIRE, 1, 100);
        var target = newNativeTarget();
        target.invulnerableTime = 18;
        target.lastHurt = 8;
        var additional = new ReactionAction.AdditionalDamage(new ReactionAction.Formula("scale * 3",
                DamageFormulaParser.parseReaction("scale * 3")),
                new ReactionAction.DamageSettings(ReactionAction.DEFAULT_DAMAGE_TYPE, Optional.empty()), ignored -> 1);
        var definitions = Map.of(FIRE, definition(FIRE, true, 10), WATER, definition(WATER, true, 10));
        var result = resolveHit(state, definitions, WATER, 4, 0, 0, amplifyingIndex(additional));
        assertFalse(nativeHit(target, result.finalPreArmorDamage(), false));
        assertEquals(8, result.finalPreArmorDamage());
        assertEquals(0, state.state(FIRE).effectiveAmount(0));
        assertEquals(List.of(additional), result.reactionPlan().actions().stream().map(value -> value.action()).toList());
        assertEquals(8, target.lastHurt);
        assertEquals(18, target.invulnerableTime);
    }

    @Test
    void sameElementCooldownStillBlocksMountingWithoutBlockingTheAttack() throws Exception {
        var state = new ElementalState();
        var definitions = Map.of(FIRE, definition(FIRE, true, 10), WATER, definition(WATER, true, 10));
        var target = newNativeTarget();
        var first = resolveHit(state, definitions, FIRE, 8, 0, 0, ReactionIndex.build(Map.of()));
        assertTrue(nativeHit(target, first.finalPreArmorDamage(), false));
        var second = resolveHit(state, definitions, FIRE, 12, 0, 0, ReactionIndex.build(Map.of()));
        assertEquals(ElementRuntimeState.ApplyResult.BLOCKED_COOLDOWN, second.applyResult());
        assertTrue(nativeHit(target, second.finalPreArmorDamage(), false));
        assertEquals(4, target.delivered);
        assertEquals(1, state.state(FIRE).effectiveAmount(0));
    }

    @Test
    void resistanceIsAppliedOnceBeforeNativeFilteringAndBypassKeepsVanillaStateChanges() throws Exception {
        var previous = configureEnchantmentEnhancement(true);
        try {
            var state = new ElementalState();
            state.putPermanent(FIRE, 1, 100);
            state.setReactionResistance(ResourceLocation.fromNamespaceAndPath("test", "reaction"), 0.5);
            var definitions = Map.of(FIRE, definition(FIRE, true, 10), WATER, definition(WATER, true, 10));
            var result = resolveHit(state, definitions, WATER, 8, 0.5, 0, amplifyingIndex());
            assertEquals(4, result.baseDamageAfterResistance());
            assertEquals(2, result.amplifyDamage());
            assertEquals(6, result.finalPreArmorDamage());
            var target = newNativeTarget();
            target.invulnerableTime = 16;
            target.lastHurt = 5;
            assertTrue(nativeHit(target, result.finalPreArmorDamage(), false));
            assertEquals(1, target.delivered);
            assertTrue(nativeHit(target, 1, true));
            assertEquals(1, target.lastHurt);
            assertEquals(20, target.invulnerableTime);
        } finally {
            ElementalPhaseServerConfig.SPEC.setConfig(previous);
        }
    }

    @Test
    void disabledEnchantmentEnhancementKeepsOriginalDamagePhysicalAndStillReacts() throws Exception {
        var previous = configureEnchantmentEnhancement(false);
        try {
            var state = new ElementalState();
            state.putPermanent(FIRE, 1, 100);
            var definitions = Map.of(FIRE, definition(FIRE, true, 10), WATER, definition(WATER, true, 10));
            var result = resolveHit(state, definitions, WATER, 9, 0.5, 0, amplifyingIndex());
            assertEquals(9, result.baseDamageAfterResistance());
            assertEquals(9, result.reactionPlan().labels().get(0).facts().originalDamage());
            assertEquals(18, result.finalPreArmorDamage());
            assertEquals(0, state.state(FIRE).effectiveAmount(0));
        } finally {
            ElementalPhaseServerConfig.SPEC.setConfig(previous);
        }
    }

    @Test
    void enabledEnchantmentEnhancementResistsOnlyTheOriginalDamage() throws Exception {
        var previous = configureEnchantmentEnhancement(true);
        try {
            var state = new ElementalState();
            state.putPermanent(FIRE, 1, 100);
            var definitions = Map.of(FIRE, definition(FIRE, true, 10), WATER, definition(WATER, true, 10));
            var result = resolveHit(state, definitions, WATER, 9, 0.5, 0, amplifyingIndex());
            assertEquals(4.5, result.baseDamageAfterResistance());
            assertEquals(4.5, result.reactionPlan().labels().get(0).facts().originalDamage());
            assertEquals(9, result.finalPreArmorDamage());
            assertEquals(0, state.state(FIRE).effectiveAmount(0));
        } finally {
            ElementalPhaseServerConfig.SPEC.setConfig(previous);
        }
    }

    @Test
    void enchantmentEnhancementChangesOriginalDamageWithoutAReaction() throws Exception {
        var previous = configureEnchantmentEnhancement(false);
        try {
            var definitions = Map.of(WATER, definition(WATER, true, 10));
            var reactions = ReactionIndex.build(Map.of());
            var disabledState = new ElementalState();
            var disabled = resolveHit(disabledState, definitions, WATER, 9, 0.5, 0, reactions);
            assertEquals(9, disabled.baseDamageAfterResistance());
            assertEquals(9, disabled.finalPreArmorDamage());
            assertTrue(disabled.reactionPlan().labels().isEmpty());
            assertEquals(1, disabledState.state(WATER).effectiveAmount(0));

            configureEnchantmentEnhancement(true);
            var enabledState = new ElementalState();
            var enabled = resolveHit(enabledState, definitions, WATER, 9, 0.5, 0, reactions);
            assertEquals(4.5, enabled.baseDamageAfterResistance());
            assertEquals(4.5, enabled.finalPreArmorDamage());
            assertTrue(enabled.reactionPlan().labels().isEmpty());
            assertEquals(1, enabledState.state(WATER).effectiveAmount(0));
        } finally {
            ElementalPhaseServerConfig.SPEC.setConfig(previous);
        }
    }

    private static CommentedConfig configureEnchantmentEnhancement(boolean enabled) throws ReflectiveOperationException {
        var field = net.minecraftforge.common.ForgeConfigSpec.class.getDeclaredField("childConfig");
        field.setAccessible(true);
        var previous = (CommentedConfig) field.get(ElementalPhaseServerConfig.SPEC);
        var replacement = CommentedConfig.inMemory();
        ElementalPhaseServerConfig.SPEC.correct(replacement);
        ElementalPhaseServerConfig.SPEC.setConfig(replacement);
        replacement.set("enchantments.enable_enhancement", enabled);
        ElementalPhaseServerConfig.SPEC.afterReload();
        return previous;
    }

    private static CombatPipeline.CombatResult resolveHit(ElementalState state,
            Map<ResourceLocation, ElementDefinition> definitions, ResourceLocation element,
            double damage, double resistance, long now, ReactionIndex reactions) {
        var attack = new ElementAttackContext(element, 1, ElementAttackContext.SourceKind.ENCHANTMENT, element);
        return new CombatPipeline().resolve(new CombatPipeline.CombatInput(damage, resistance, attack, state, now,
                definitions.get(element), reactions, definitions, null, 0, 100, 100, ignored -> true, null));
    }

    private static ReactionIndex amplifyingIndex(ReactionAction... additional) {
        var id = ResourceLocation.fromNamespaceAndPath("test", "reaction");
        var actions = new java.util.ArrayList<ReactionAction>();
        actions.add(new ReactionAction.MainDamageBonus(new ReactionAction.Formula("original_damage",
                DamageFormulaParser.parseReaction("original_damage")), ignored -> 1));
        actions.addAll(List.of(additional));
        var direction = new ReactionDirection(WATER, FIRE, 0.1, new ReactionConsumption(1, 1), List.of(),
                new ReactionDisplay(0xFFFFFF, true), actions);
        return ReactionIndex.build(Map.of(id, new ReactionSpec(id, 0, Set.of(WATER, FIRE), List.of(direction))));
    }

    private static ClassNode readClass(String name) throws Exception {
        try (var stream = ElementApplicationPolicyTest.class.getResourceAsStream("/" + name + ".class")) {
            assertNotNull(stream, "Missing pre-cooldown elemental entry point: " + name);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node;
        }
    }

    private static MethodNode nativeHurt() throws Exception {
        return readClass("net/minecraft/world/entity/LivingEntity").methods.stream()
                .filter(method -> method.name.equals("hurt") && method.desc.equals(
                        "(Lnet/minecraft/world/damagesource/DamageSource;F)Z")).findFirst().orElseThrow();
    }

    private static AnnotationNode annotation(MethodNode method, String descriptor) {
        return java.util.stream.Stream.concat(
                method.visibleAnnotations == null ? java.util.stream.Stream.empty() : method.visibleAnnotations.stream(),
                method.invisibleAnnotations == null ? java.util.stream.Stream.empty() : method.invisibleAnnotations.stream())
                .filter(node -> node.desc.equals(descriptor)).findFirst().orElse(null);
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    public static class NativeCooldownProbe {
        public int invulnerableTime, hurtDuration, hurtTime;
        public float lastHurt, delivered;

        protected void actuallyHurt(DamageSource source, float amount) {
            delivered = amount;
        }
    }

    private static NativeCooldownProbe newNativeTarget() throws Exception {
        var instructions = nativeHurt().instructions.toArray();
        int start = -1, end = -1;
        for (int i = 0; i < instructions.length; i++) {
            if (instructions[i] instanceof FieldInsnNode field) {
                if (field.name.equals("invulnerableTime") && start < 0) start = i - 1;
                if (field.name.equals("DAMAGES_HELMET")) { end = i - 1; break; }
            }
        }
        assertTrue(start >= 0 && end > start);
        var owner = org.objectweb.asm.Type.getInternalName(NativeCooldownProbe.class);
        var name = "com/elementalphase/combat/NativeCooldownFixture";
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, owner, null);
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        var hurt = new MethodNode(Opcodes.ACC_PUBLIC, "hurt",
                "(Lnet/minecraft/world/damagesource/DamageSource;FZ)Z", null, null);
        var labels = new java.util.HashMap<LabelNode, LabelNode>();
        for (var instruction : instructions) if (instruction instanceof LabelNode label) labels.put(label, new LabelNode());
        for (int i = start; i < end; i++) {
            var instruction = instructions[i];
            if (instruction instanceof FrameNode || instruction instanceof LineNumberNode) continue;
            if (instruction instanceof FieldInsnNode field && field.name.equals("BYPASSES_COOLDOWN")) continue;
            if (instruction instanceof MethodInsnNode call && call.owner.equals(
                    "net/minecraft/world/damagesource/DamageSource") && call.name.equals("is")) {
                hurt.instructions.add(new InsnNode(Opcodes.POP));
                hurt.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
                continue;
            }
            var copy = instruction.clone(labels);
            if (copy instanceof FieldInsnNode field && field.owner.equals("net/minecraft/world/entity/LivingEntity")) field.owner = owner;
            if (copy instanceof MethodInsnNode call && call.name.equals("actuallyHurt")) call.owner = owner;
            hurt.instructions.add(copy);
        }
        hurt.instructions.add(new InsnNode(Opcodes.ICONST_1));
        hurt.instructions.add(new InsnNode(Opcodes.IRETURN));
        hurt.accept(writer);
        writer.visitEnd();
        var loader = new ClassLoader(ElementApplicationPolicyTest.class.getClassLoader()) {
            Class<?> fixture() { return defineClass(name.replace('/', '.'), writer.toByteArray(), 0, writer.toByteArray().length); }
        };
        return (NativeCooldownProbe) loader.fixture().getConstructor().newInstance();
    }

    private static boolean nativeHit(NativeCooldownProbe target, double damage, boolean bypass) throws Exception {
        return (boolean) target.getClass().getMethod("hurt", DamageSource.class, float.class, boolean.class)
                .invoke(target, null, (float) damage, bypass);
    }

    private static void resolve(ElementalState state, ElementDefinition definition, double amount,
                                ReactionIndex index) {
        resolveAt(state, definition, amount, index,
                Map.of(FIRE, definition(FIRE, true, 10.0D), WATER, definition), 0L);
    }

    private static CombatPipeline.CombatResult resolveAt(ElementalState state, ElementDefinition definition,
                                double amount, ReactionIndex index,
                                Map<ResourceLocation, ElementDefinition> definitions, long now) {
        var attack = new ElementAttackContext(WATER, amount, ElementAttackContext.SourceKind.INTRINSIC,
                ResourceLocation.fromNamespaceAndPath("test", "source"));
        return new CombatPipeline().resolve(new CombatPipeline.CombatInput(1.0D, 0.0D, attack, state, now,
                definition, index, definitions,
                null, 1.0D, 10.0D, 10.0D, ignored -> true, null));
    }

    private static ReactionIndex reactionIndex() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("test", "reaction");
        ReactionDirection direction = new ReactionDirection(WATER, FIRE, 0.1D,
                new ReactionConsumption(1.0D, 1.0D), List.of(), new ReactionDisplay(0xFFFFFF, true), List.of());
        ReactionSpec reaction = new ReactionSpec(id, 0, Set.of(FIRE, WATER), List.of(direction));
        return ReactionIndex.build(Map.of(id, reaction));
    }

    private static ElementDefinition definition(ResourceLocation id, boolean retain, double maximum) {
        return new ElementDefinition(id,
                new ElementAttachmentPolicy(retain ? ElementAttachmentPolicy.Mode.NORMAL : ElementAttachmentPolicy.Mode.VIRTUAL,
                        2, retain ? 100 : 10, maximum),
                new ElementDisplayDefinition("element.test." + id.getPath(), 0xFFFFFF, true, 0, Optional.empty()));
    }
}
