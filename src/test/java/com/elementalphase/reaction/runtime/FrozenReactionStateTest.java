package com.elementalphase.reaction.runtime;

import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.Vec3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrozenReactionStateTest {
    @Test
    void constrainsActualMovementBeforeItIsAppliedToAnyLivingEntity() throws Exception {
        try (var stream = getClass().getResourceAsStream("/com/elementalphase/mixin/EntityMovementMixin.class")) {
            org.junit.jupiter.api.Assertions.assertNotNull(stream);
            var node = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(stream).accept(node, 0);
            var mixin = node.invisibleAnnotations.stream().filter(annotation -> annotation.desc.equals(
                    "Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElseThrow();
            assertTrue(mixin.values.toString().contains("Lnet/minecraft/world/entity/Entity;"));
            var method = node.methods.stream().filter(candidate -> candidate.visibleAnnotations != null
                    && candidate.visibleAnnotations.stream().anyMatch(annotation -> annotation.desc.equals(
                    "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;"))).findFirst().orElseThrow();
            var injection = method.visibleAnnotations.stream().filter(annotation -> annotation.desc.equals(
                    "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;")).findFirst().orElseThrow();
            assertTrue(injection.values.toString().contains(
                    "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"));
            assertTrue(injection.values.contains("argsOnly"));
            assertTrue(injection.values.contains(Boolean.TRUE));
            var at = (org.objectweb.asm.tree.AnnotationNode) injection.values.get(injection.values.indexOf("at") + 1);
            assertTrue(at.values.contains("HEAD"));
            assertTrue(java.util.Arrays.stream(method.instructions.toArray())
                    .filter(instruction -> instruction instanceof org.objectweb.asm.tree.MethodInsnNode)
                    .map(instruction -> (org.objectweb.asm.tree.MethodInsnNode) instruction)
                    .anyMatch(call -> call.owner.equals(
                            "com/elementalphase/reaction/runtime/ReactionRuntimeController")
                            && call.name.equals("constrainMovement")));
        }
        try (var stream = getClass().getResourceAsStream("/elemental_phase.mixins.json")) {
            org.junit.jupiter.api.Assertions.assertNotNull(stream);
            var config = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(
                    stream, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(config.getAsJsonArray("mixins").asList().stream()
                    .anyMatch(value -> value.getAsString().equals("EntityMovementMixin")));
        }
    }

    @Test
    void frozenMovementPreservesVerticalMotionAndEndsAtTheStateDeadline() {
        var state = FrozenReactionState.create(100, 20);
        for (double vertical : new double[]{-0.8, 0.0, 0.42}) {
            var movement = new Vec3(0.3, vertical, -0.6);
            assertEquals(new Vec3(0, vertical, 0), state.constrainMovement(movement, 100L));
            assertEquals(new Vec3(0, vertical, 0), state.constrainMovement(movement, 120L));
            org.junit.jupiter.api.Assertions.assertSame(movement, state.constrainMovement(movement, 99L));
            org.junit.jupiter.api.Assertions.assertSame(movement, state.constrainMovement(movement, 121L));
        }
    }

    @Test
    void refreshComparesRemainingDurationAndRestartsAcceptedState() {
        var state = FrozenReactionState.create(0, 100);
        org.junit.jupiter.api.Assertions.assertSame(state, state.refresh(80, 10));
        assertEquals(80, state.refresh(80, 20).startedAt());
        assertEquals(100, state.refresh(80, 20).expiresAt());
        assertEquals(110, state.refresh(80, 30).expiresAt());
        assertEquals(2147483727L, state.refresh(80, Integer.MAX_VALUE).expiresAt());
    }

    @Test
    void usesInclusiveExpirationAndRefreshesFromCurrentTick() {
        var state = FrozenReactionState.create(100L, 20);

        assertTrue(state.active(100L));
        assertTrue(state.active(120L));
        assertFalse(state.active(121L));
        assertEquals(20, state.remainingTicks(100L));
        assertEquals(0, state.remainingTicks(120L));
        assertEquals(0, state.remainingTicks(121L));

        var refreshed = state.refresh(110L, 30);
        assertEquals(110L, refreshed.startedAt());
        assertEquals(140L, refreshed.expiresAt());
    }

    @Test
    void saturatesDeadlineOnOverflow() {
        var state = FrozenReactionState.create(Long.MAX_VALUE - 5L, 20);

        assertEquals(Long.MAX_VALUE, state.expiresAt());
        assertTrue(state.active(Long.MAX_VALUE));
    }
}
