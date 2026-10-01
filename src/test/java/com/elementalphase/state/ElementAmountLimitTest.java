package com.elementalphase.state;

import com.elementalphase.capability.ElementalStateProvider;
import com.elementalphase.data.model.ElementAttachmentPolicy;
import com.elementalphase.data.model.ElementDefinition;
import com.elementalphase.data.model.ElementDisplayDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.capabilities.CapabilityDispatcher;
import net.minecraftforge.common.capabilities.CapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class ElementAmountLimitTest {
    private static final ResourceLocation FIRE = ResourceLocation.fromNamespaceAndPath("test", "fire");

    @Test
    void capabilityCanBeAccessedAfterRepeatedInvalidateAndReviveCycles() throws Exception {
        ElementalStateProvider provider = new ElementalStateProvider();
        CapabilityHost host = new CapabilityHost(provider);
        ElementalState state = capabilityAccess(provider).resolve().orElseThrow();
        state.applyTemporaryIgnoringCooldown(FIRE, 2.0D, 0L, 100, null);

        for (int cycle = 0; cycle < 3; cycle++) {
            LazyOptional<ElementalState> previous = capabilityAccess(provider);
            AtomicInteger invalidations = new AtomicInteger();
            previous.addListener(ignored -> invalidations.incrementAndGet());

            host.invalidateCaps();

            assertFalse(previous.isPresent());
            assertEquals(1, invalidations.get());
            assertFalse(host.getCapability(null).isPresent());
            host.reviveCaps();

            LazyOptional<ElementalState> current = capabilityAccess(provider);
            assertTrue(current.isPresent());
            assertNotSame(previous, current);
            assertSame(state, current.resolve().orElseThrow());
            assertEquals(2.0D, state.state(FIRE).effectiveAmount(0L));
        }
    }

    @Test
    void invalidatingCapabilityDoesNotDiscardSavedOrLoadedEntityData() throws Exception {
        ElementalStateProvider provider = new ElementalStateProvider();
        CapabilityHost host = new CapabilityHost(provider);
        ElementalState state = capabilityAccess(provider).resolve().orElseThrow();
        state.putPermanent(FIRE, 3.0D, 100);
        var saved = provider.serializeNBT();

        host.invalidateCaps();

        assertEquals(saved, provider.serializeNBT());
        state.clear();
        provider.deserializeNBT(saved);
        host.reviveCaps();
        assertEquals(3.0D, capabilityAccess(provider).resolve().orElseThrow()
                .state(FIRE).permanentPreset());
    }

    @SuppressWarnings("unchecked")
    private static LazyOptional<ElementalState> capabilityAccess(ElementalStateProvider provider) throws Exception {
        var field = Arrays.stream(ElementalStateProvider.class.getDeclaredFields())
                .filter(candidate -> candidate.getType() == LazyOptional.class).findFirst().orElseThrow();
        field.setAccessible(true);
        return (LazyOptional<ElementalState>) field.get(provider);
    }

    private static final class CapabilityHost extends CapabilityProvider<CapabilityHost> {
        private CapabilityHost(ElementalStateProvider provider) throws Exception {
            super(CapabilityHost.class, false);
            var dispatcher = new CapabilityDispatcher(Map.of(FIRE, provider), List.of(provider::invalidate));
            var field = CapabilityProvider.class.getDeclaredField("capabilities");
            field.setAccessible(true);
            field.set(this, dispatcher);
        }
    }

    @Test
    void clampsPermanentAndTemporaryLayersWithoutChangingExpiry() {
        ElementalState state = new ElementalState();
        state.putPermanent(FIRE, 10.0D, 200);
        state.applyTemporaryIgnoringCooldown(FIRE, 20.0D, 100L, 100, null);
        long expiry = state.state(FIRE).temporaryExpiresAt();

        state.reconcileElementLimits(Map.of(FIRE, definition(6.0D)), 100L);

        assertEquals(6.0D, state.state(FIRE).permanentPreset());
        assertEquals(6.0D, state.state(FIRE).permanentCurrent());
        assertEquals(6.0D, state.state(FIRE).temporaryAmount());
        assertEquals(6.0D, state.state(FIRE).effectiveAmount(100L));
        assertEquals(expiry, state.state(FIRE).temporaryExpiresAt());
    }

    @Test
    void leavesStateForUnknownElementsUntouched() {
        ElementalState state = new ElementalState();
        state.putPermanent(FIRE, 10.0D, 200);

        state.reconcileElementLimits(Map.of(), 100L);

        assertEquals(10.0D, state.state(FIRE).effectiveAmount(100L));
    }

    private static ElementDefinition definition(double maximum) {
        return new ElementDefinition(FIRE,
                new ElementAttachmentPolicy(ElementAttachmentPolicy.Mode.NORMAL, 2, 100, maximum),
                new ElementDisplayDefinition("element.test.fire", 0xFFFFFF, true, 0, Optional.empty()));
    }

    @Test
    void virtualDurationIsBoundedAndZeroCooldownAllowsImmediateEqualRefresh() {
        ElementalState state = new ElementalState();
        var virtual = virtualDefinition(0);
        assertEquals(ElementRuntimeState.ApplyResult.APPLIED,
                state.applyElement(virtual, 8.0D, 0L, 100, true, null));
        assertEquals(10L, state.state(FIRE).temporaryExpiresAt());
        assertTrue(state.elementApplicationReady(FIRE, 0L));
        assertEquals(ElementRuntimeState.ApplyResult.IGNORED_LOWER,
                state.applyElement(virtual, 4.0D, 1L, 100, true, null));
        assertEquals(10L, state.state(FIRE).temporaryExpiresAt());
        assertEquals(ElementRuntimeState.ApplyResult.REFRESHED,
                state.applyElement(virtual, 8.0D, 1L, 100, true, null));
        assertEquals(11L, state.state(FIRE).temporaryExpiresAt());
    }

    @Test
    void virtualAmountsAreNotSavedAndClearingDoesNotResetCooldown() {
        ResourceLocation water = ResourceLocation.fromNamespaceAndPath("test", "water");
        ElementalState state = new ElementalState();
        state.applyElement(virtualDefinition(20), 8.0D, 0L, 100, true, null);
        state.putPermanent(water, 5.0D, 100);
        ElementalState restored = new ElementalState();
        restored.deserializeNBT(state.serializeNBT());

        assertEquals(null, restored.state(FIRE));
        assertEquals(5.0D, restored.state(water).effectiveAmount(0L));
        state.clearVirtualElements();
        assertEquals(null, state.state(FIRE));
        assertFalse(state.elementApplicationReady(FIRE, 1L));
        assertEquals(5.0D, state.state(water).effectiveAmount(0L));
    }

    @Test
    void loadedEntityKeepsItsStateButRequiresProfileRefreshAfterRestart() {
        ElementalState original = new ElementalState();
        original.putPermanent(FIRE, 5.0D, 100);
        original.setResistance(FIRE, 0.5D);
        original.setIntrinsicAttack(FIRE, 2.0D);
        original.markInitialized(1L);

        var saved = original.serializeNBT();
        saved.putLong("generation", 1L);
        ElementalState restored = new ElementalState();
        restored.deserializeNBT(saved);

        assertTrue(restored.initialized());
        assertNotEquals(1L, restored.appliedGeneration());
        assertEquals(5.0D, restored.state(FIRE).permanentPreset());
        assertEquals(0.5D, restored.resistance(FIRE));
        assertEquals(2.0D, restored.intrinsicAttack().orElseThrow().baseAmount());
    }

    @Test
    void playerIdentitySurvivesSavedElementSourcesAndLegacySourcesStillLoad() {
        ElementSourceSnapshot source = new ElementSourceSnapshot(Optional.of(java.util.UUID.randomUUID()), Optional.empty(),
                ResourceLocation.fromNamespaceAndPath("test", "source"),
                ResourceLocation.fromNamespaceAndPath("test", "damage"), FIRE, 2.0D, 100L);
        var original = ElementRuntimeState.temporary(4.0D, 100L, 100, 0, 1L, source);
        var legacy = original.serializeNBT();
        legacy.put("permanent_source", legacy.getCompound("temporary_source").copy());
        var saved = legacy.copy();
        for (String key : new String[]{"temporary_source", "permanent_source"}) {
            saved.getCompound(key).putString("player_name", "Alice");
        }

        var restored = ElementRuntimeState.deserializeNBT(saved).serializeNBT();

        for (String key : new String[]{"temporary_source", "permanent_source"}) {
            assertEquals("Alice", restored.getCompound(key).getString("player_name"));
        }
        var restoredLegacy = ElementRuntimeState.deserializeNBT(legacy);
        assertEquals(Optional.of(source), restoredLegacy.temporarySource());
        assertEquals(Optional.of(source), restoredLegacy.permanentSource());
    }

    @Test
    void nonFiniteSourceStrengthDoesNotPreventElementStateLoad() {
        ElementSourceSnapshot source = new ElementSourceSnapshot(Optional.empty(), Optional.empty(),
                ResourceLocation.fromNamespaceAndPath("test", "source"),
                ResourceLocation.fromNamespaceAndPath("test", "damage"), FIRE, 2.0D, 100L);
        ElementRuntimeState original = ElementRuntimeState.temporary(4.0D, 100L, 100, 0, 1L, source);
        var saved = original.serializeNBT();
        saved.put("permanent_source", saved.getCompound("temporary_source").copy());

        for (String key : new String[] {"temporary_source", "permanent_source"}) {
            for (double strength : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                var damaged = saved.copy();
                damaged.getCompound(key).putDouble("strength", strength);

                ElementRuntimeState restored = ElementRuntimeState.deserializeNBT(damaged);

                assertEquals(4.0D, restored.effectiveAmount(100L));
                assertEquals(Optional.empty(), key.equals("temporary_source")
                        ? restored.temporarySource() : restored.permanentSource());
                assertEquals(Optional.of(source), key.equals("temporary_source")
                        ? restored.permanentSource() : restored.temporarySource());
            }
        }
    }

    @Test
    void reloadingNormalElementAsVirtualRemovesSavedAmounts() {
        ElementalState state = new ElementalState();
        state.putPermanent(FIRE, 5.0D, 100);
        state.reconcileElementLimits(Map.of(FIRE, virtualDefinition(0)), 0L);

        assertEquals(null, state.state(FIRE));
    }

    @Test
    void propagatedNormalElementKeepsItsOwnDurationBesideVirtualElement() {
        ResourceLocation water = ResourceLocation.fromNamespaceAndPath("test", "water");
        ElementalState state = new ElementalState();
        state.applyElement(virtualDefinition(0), 2.0D, 0L, 100, true, null);
        var normal = new ElementDefinition(water, ElementAttachmentPolicy.DEFAULT,
                new ElementDisplayDefinition("element.test.water", 0xFFFFFF, true, 0, Optional.empty()));
        state.applyElement(normal, 2.0D, 0L, normal.attachment().durationTicks(), true, null);

        assertFalse(state.state(water).virtual());
        assertEquals(100L, state.state(water).temporaryExpiresAt());
        assertEquals(2.0D, state.state(water).effectiveAmount(10L));
        assertEquals(0.0D, state.state(FIRE).effectiveAmount(10L));
    }

    private static ElementDefinition virtualDefinition(int cooldown) {
        return new ElementDefinition(FIRE,
                new ElementAttachmentPolicy(ElementAttachmentPolicy.Mode.VIRTUAL, cooldown, 10, 10.0D),
                new ElementDisplayDefinition("element.test.fire", 0xFFFFFF, true, 0, Optional.empty()));
    }
}
