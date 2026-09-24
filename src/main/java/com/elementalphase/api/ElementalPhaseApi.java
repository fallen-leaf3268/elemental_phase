package com.elementalphase.api;

import com.elementalphase.capability.ElementalCapabilities;
import com.elementalphase.data.ElementDataManager;
import com.elementalphase.data.ElementDataSnapshot;
import com.elementalphase.data.model.ElementDefinition;
import com.elementalphase.profile.EntityProfileResolver;
import com.elementalphase.state.ElementRuntimeState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

public final class ElementalPhaseApi {
    private static final EntityProfileResolver PROFILE_RESOLVER = new EntityProfileResolver();

    private ElementalPhaseApi() {
    }

    public static OptionalDouble getEffectiveAmount(LivingEntity entity, ResourceLocation element) {
        validateServerThread(entity);
        if (!ElementDataManager.snapshot().elements().containsKey(element)) {
            return OptionalDouble.empty();
        }
        return ElementalCapabilities.get(entity).resolve()
                .map(state -> {
                    initialize(entity, state);
                    ElementRuntimeState runtime = state.state(element);
                    return OptionalDouble.of(runtime == null ? 0.0D : runtime.effectiveAmount(entity.level().getGameTime()));
                })
                .orElseGet(OptionalDouble::empty);
    }

    public static Map<ResourceLocation, Double> getActiveElements(LivingEntity entity) {
        validateServerThread(entity);
        return ElementalCapabilities.get(entity).resolve().map(state -> {
            initialize(entity, state);
            long now = entity.level().getGameTime();
            Map<ResourceLocation, Double> active = new LinkedHashMap<>();
            for (ResourceLocation id : state.orderedActiveElements(now)) {
                ElementRuntimeState runtime = state.state(id);
                if (runtime != null && runtime.effectiveAmount(now) > 0.0D) {
                    active.put(id, runtime.effectiveAmount(now));
                }
            }
            return Map.copyOf(active);
        }).orElseGet(Map::of);
    }

    public static double getResistance(LivingEntity entity, ResourceLocation element) {
        validateServerThread(entity);
        return ElementalCapabilities.get(entity).resolve().map(state -> {
            initialize(entity, state);
            return state.resistance(element);
        }).orElse(0.0D);
    }

    public static ElementRuntimeState.ApplyResult applyTemporary(LivingEntity entity, ResourceLocation element, double amount) {
        return applyTemporary(entity, element, amount, true);
    }

    public static ElementRuntimeState.ApplyResult applyTemporary(LivingEntity entity, ResourceLocation element, double amount,
                                                                   boolean respectAttachmentCooldown) {
        return applyTemporary(entity, element, amount, respectAttachmentCooldown, null);
    }

    public static ElementRuntimeState.ApplyResult applyTemporary(LivingEntity entity, ResourceLocation element, double amount,
                                                                   boolean respectAttachmentCooldown,
                                                                   com.elementalphase.state.ElementSourceSnapshot source) {
        validateServerThread(entity);
        var definition = ElementDataManager.snapshot().elements().get(element);
        Optional<ElementRuntimeState.ApplyResult> rejection = temporaryApplicationRejection(definition, amount);
        if (rejection.isPresent()) {
            return rejection.get();
        }
        double acceptedAmount = Math.min(amount, definition.attachment().maxAmount());
        return ElementalCapabilities.get(entity).resolve().map(state -> {
            initialize(entity, state);
            ElementRuntimeState.ApplyResult result = respectAttachmentCooldown
                    ? state.applyTemporary(element, acceptedAmount, entity.level().getGameTime(), definition.attachment().durationTicks(),
                    definition.attachment().cooldownTicks(), source)
                    : state.applyTemporaryIgnoringCooldown(element, acceptedAmount, entity.level().getGameTime(),
                    definition.attachment().durationTicks(), source);
            return result;
        }).orElse(ElementRuntimeState.ApplyResult.UNAVAILABLE);
    }

    public static boolean removeElement(LivingEntity entity, ResourceLocation element) {
        validateServerThread(entity);
        if (!ElementDataManager.snapshot().elements().containsKey(element)) {
            return false;
        }
        return ElementalCapabilities.get(entity).resolve().map(state -> {
            initialize(entity, state);
            if (state.state(element) == null) {
                return false;
            }
            state.remove(element);
            return true;
        }).orElse(false);
    }

    public static ElementDataSnapshot dataSnapshot() {
        return ElementDataManager.snapshot();
    }

    static Optional<ElementRuntimeState.ApplyResult> temporaryApplicationRejection(ElementDefinition definition,
                                                                                    double amount) {
        if (definition == null || !Double.isFinite(amount) || amount < 0.000001D) {
            return Optional.of(ElementRuntimeState.ApplyResult.INVALID_INPUT);
        }
        return definition.application().fromReaction()
                ? Optional.empty()
                : Optional.of(ElementRuntimeState.ApplyResult.UNAVAILABLE);
    }

    private static void initialize(LivingEntity entity, com.elementalphase.state.ElementalState state) {
        PROFILE_RESOLVER.initializeIfNeeded(entity, state, ElementDataManager.snapshot());
    }

    private static void validateServerThread(LivingEntity entity) {
        if (entity == null || entity.level().isClientSide() || entity.getServer() == null || !entity.getServer().isSameThread()) {
            throw new IllegalStateException("Elemental Phase API requires the logical server thread");
        }
    }
}
