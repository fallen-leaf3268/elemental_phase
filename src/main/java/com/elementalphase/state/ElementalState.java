package com.elementalphase.state;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.elementalphase.data.model.ElementDefinition;

public final class ElementalState {
    private static final double EPSILON = 0.000001D;

    private final Map<ResourceLocation, ElementRuntimeState> states = new HashMap<>();
    private final Map<ResourceLocation, Double> resistances = new HashMap<>();
    private final Map<ResourceLocation, VirtualCooldown> virtualCooldowns = new HashMap<>();
    private final Map<ApplicationCooldownKey, Long> applicationCooldowns = new HashMap<>();
    private long nextOrder;
    private IntrinsicAttack intrinsicAttack;
    private boolean initialized;
    private long appliedGeneration = Long.MIN_VALUE;

    public ElementRuntimeState state(ResourceLocation id) {
        return states.get(id);
    }

    public ElementRuntimeState.ApplyResult applyTemporary(ResourceLocation id, double amount, long now, int duration, int cooldown) {
        return applyTemporary(id, amount, now, duration, cooldown, null);
    }

    public ElementRuntimeState.ApplyResult applyTemporary(ResourceLocation id, double amount, long now, int duration,
                                                           int cooldown, ElementSourceSnapshot source) {
        if (id == null || !Double.isFinite(amount) || amount < EPSILON || amount > 1_000_000.0D || duration < 1 || cooldown < 0) {
            return ElementRuntimeState.ApplyResult.INVALID_INPUT;
        }
        if (!elementApplicationReady(id, now)) {
            return ElementRuntimeState.ApplyResult.BLOCKED_COOLDOWN;
        }
        ElementRuntimeState state = states.get(id);
        if (state != null && state.canDiscard(now)) {
            states.remove(id);
            state = null;
        }
        if (state == null) {
            states.put(id, ElementRuntimeState.temporary(amount, now, duration, 0, nextOrder++, source));
            startElementApplicationCooldown(id, now, cooldown);
            return ElementRuntimeState.ApplyResult.APPLIED;
        }
        ElementRuntimeState.ApplyResult result = state.tryApplyTemporary(amount, now, duration, 0, source);
        if (result.changed()) {
            startElementApplicationCooldown(id, now, cooldown);
        }
        return result;
    }

    public ElementRuntimeState.ApplyResult applyTemporaryIgnoringCooldown(ResourceLocation id, double amount, long now,
                                                                           int duration, ElementSourceSnapshot source) {
        if (id == null || !Double.isFinite(amount) || amount < EPSILON || amount > 1_000_000.0D || duration < 1) {
            return ElementRuntimeState.ApplyResult.INVALID_INPUT;
        }
        ElementRuntimeState state = states.get(id);
        if (state != null && state.canDiscard(now)) {
            states.remove(id);
            state = null;
        }
        if (state == null) {
            states.put(id, ElementRuntimeState.temporary(amount, now, duration, 0, nextOrder++, source));
            return ElementRuntimeState.ApplyResult.APPLIED;
        }
        return state.tryApplyTemporaryIgnoringCooldown(amount, now, duration, source);
    }

    public ElementRuntimeState.ApplyResult applyReactionElement(ResourceLocation id, double amount, long now,
                                                                int duration, ElementSourceSnapshot source) {
        return applyTemporaryIgnoringCooldown(id, amount, now, duration, source);
    }

    public boolean tryApplicationCooldown(ApplicationCooldownKey key, long now, int ticks) {
        if (key == null || ticks < 0) {
            return false;
        }
        if (!applicationCooldownReady(key, now)) {
            return false;
        }
        startApplicationCooldown(key, now, ticks);
        return true;
    }

    public boolean applicationCooldownReady(ApplicationCooldownKey key, long now) {
        if (key == null) {
            return false;
        }
        Long until = applicationCooldowns.get(key);
        return until == null || now >= until;
    }

    public void startApplicationCooldown(ApplicationCooldownKey key, long now, int ticks) {
        if (key == null || ticks < 0) {
            throw new IllegalArgumentException("Invalid application cooldown");
        }
        if (ticks == 0) {
            applicationCooldowns.remove(key);
        } else {
            applicationCooldowns.put(key, deadline(now, ticks));
        }
    }

    public ElementRuntimeState.ApplyResult tryTriggerVirtual(ResourceLocation id, double amount, long now, int cooldown) {
        if (id == null || !Double.isFinite(amount) || amount < EPSILON || amount > 1_000_000.0D || cooldown < 0) {
            return ElementRuntimeState.ApplyResult.INVALID_INPUT;
        }
        if (!elementApplicationReady(id, now)) return ElementRuntimeState.ApplyResult.BLOCKED_COOLDOWN;
        return ElementRuntimeState.ApplyResult.APPLIED;
    }

    public boolean elementApplicationReady(ResourceLocation id, long now) {
        if (id == null) return false;
        VirtualCooldown activeCooldown = virtualCooldowns.get(id);
        if (activeCooldown == null) return true;
        if (activeCooldown.active(now)) return false;
        virtualCooldowns.remove(id);
        return true;
    }

    public long elementApplicationCooldownUntil(ResourceLocation id, long now) {
        return elementApplicationReady(id, now) ? Long.MIN_VALUE : virtualCooldowns.get(id).until();
    }

    public void startElementApplicationCooldown(ResourceLocation id, long now, int ticks) {
        if (id == null || ticks < 0) throw new IllegalArgumentException("Invalid element application cooldown");
        if (ticks == 0) {
            virtualCooldowns.remove(id);
            return;
        }
        boolean overflowed = now > Long.MAX_VALUE - ticks;
        virtualCooldowns.put(id, new VirtualCooldown(overflowed ? Long.MAX_VALUE : now + ticks, overflowed));
    }

    public void putPermanent(ResourceLocation id, double preset, int restoreDelayTicks) {
        if (id == null || !Double.isFinite(preset) || preset < EPSILON || preset > 1_000_000.0D || restoreDelayTicks < 1) {
            throw new IllegalArgumentException("Invalid permanent element");
        }
        states.put(id, ElementRuntimeState.permanent(preset, restoreDelayTicks, nextOrder++));
    }

    public List<ResourceLocation> orderedActiveElements(long now) {
        Iterator<Map.Entry<ResourceLocation, ElementRuntimeState>> iterator = states.entrySet().iterator();
        List<Map.Entry<ResourceLocation, ElementRuntimeState>> active = new ArrayList<>();
        while (iterator.hasNext()) {
            Map.Entry<ResourceLocation, ElementRuntimeState> entry = iterator.next();
            ElementRuntimeState value = entry.getValue();
            value.refresh(now);
            if (value.active(now)) {
                active.add(entry);
            } else if (value.canDiscard(now)) {
                iterator.remove();
            }
        }
        active.sort(Comparator.comparingLong(entry -> entry.getValue().order()));
        return active.stream().map(Map.Entry::getKey).toList();
    }

    public List<AuraHandle> auraHandles(long now) {
        return auraHandles(now, Map.of());
    }

    public List<AuraHandle> auraHandles(long now, Map<ResourceLocation, ElementDefinition> definitions) {
        List<AuraHandle> handles = new ArrayList<>();
        for (ResourceLocation id : orderedActiveElements(now)) {
            ElementRuntimeState runtime = states.get(id);
            handles.add(new AuraHandle() {
                @Override
                public ResourceLocation element() {
                    return id;
                }

                @Override
                public double amount() {
                    return runtime.effectiveAmount(now);
                }

                @Override
                public long order() {
                    return runtime.order();
                }

                @Override
                public List<ElementPortion> consume(double amount) {
                    return runtime.consumePortions(amount, now);
                }

            });
        }
        handles.sort(Comparator.comparingLong(AuraHandle::order)
                .thenComparing(value -> value.element().toString()));
        return List.copyOf(handles);
    }

    public double resistance(ResourceLocation id) {
        return resistances.getOrDefault(id, 0.0D);
    }

    public List<ResourceLocation> knownElements(long now) {
        orderedActiveElements(now);
        virtualCooldowns.entrySet().removeIf(entry -> !entry.getValue().active(now));
        return java.util.stream.Stream.of(states.keySet(), resistances.keySet(), virtualCooldowns.keySet())
                .flatMap(java.util.Collection::stream)
                .distinct()
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .toList();
    }

    public void setResistance(ResourceLocation id, double value) {
        if (id == null || !Double.isFinite(value) || value < -1.0D || value > 1.0D) {
            throw new IllegalArgumentException("Invalid element resistance");
        }
        resistances.put(id, value);
    }

    public void replaceResistances(Map<ResourceLocation, Double> values) {
        resistances.clear();
        values.forEach(this::setResistance);
    }

    public void reconcilePermanent(Map<ResourceLocation, com.elementalphase.data.model.EntityProfileDefinition.PermanentElement> values) {
        for (Map.Entry<ResourceLocation, ElementRuntimeState> entry : states.entrySet()) {
            if (entry.getValue().hasPermanentPreset() && !values.containsKey(entry.getKey())) {
                entry.getValue().reconcilePermanent(0.0D, 1);
            }
        }
        values.forEach((id, value) -> {
            ElementRuntimeState current = states.get(id);
            if (current == null) {
                putPermanent(id, value.amount(), value.restoreDelayTicks());
            } else {
                current.reconcilePermanent(value.amount(), value.restoreDelayTicks());
            }
        });
    }

    public void reconcileElementLimits(Map<ResourceLocation, ElementDefinition> definitions, long now) {
        definitions.forEach((id, definition) -> {
            ElementRuntimeState state = states.get(id);
            if (state != null) {
                state.clampAmount(definition.attachment().maxAmount(), now);
            }
        });
    }

    public Optional<IntrinsicAttack> intrinsicAttack() {
        return Optional.ofNullable(intrinsicAttack);
    }

    public void setIntrinsicAttack(ResourceLocation element, double baseAmount) {
        if (element == null || !Double.isFinite(baseAmount) || baseAmount < EPSILON || baseAmount > 1_000_000.0D) {
            throw new IllegalArgumentException("Invalid intrinsic attack");
        }
        intrinsicAttack = new IntrinsicAttack(element, baseAmount);
    }

    public void clearIntrinsicAttack() {
        intrinsicAttack = null;
    }

    public void remove(ResourceLocation id) {
        ElementRuntimeState state = states.get(id);
        if (state != null) {
            state.clearAll();
        }
    }

    public ElementRuntimeState.ApplyResult applyEffect(ResourceLocation id, String operation, double amount, long now, int duration) {
        return applyEffect(id, operation, amount, now, duration, 0, 1_000_000.0D, false);
    }

    public ElementRuntimeState.ApplyResult applyEffect(ResourceLocation id, String operation, double amount, long now,
                                                       int duration, int cooldown, double maximum,
                                                       boolean respectCooldown) {
        if (id == null || operation == null || duration < 1) {
            return ElementRuntimeState.ApplyResult.INVALID_INPUT;
        }
        ElementRuntimeState state = states.get(id);
        return switch (operation) {
            case "add" -> {
                if (!Double.isFinite(amount) || amount < EPSILON || !Double.isFinite(maximum) || maximum < EPSILON) {
                    yield ElementRuntimeState.ApplyResult.INVALID_INPUT;
                }
                if (respectCooldown && !elementApplicationReady(id, now)) {
                    yield ElementRuntimeState.ApplyResult.BLOCKED_COOLDOWN;
                }
                double current = state == null ? 0.0D : state.effectiveAmount(now);
                double next = Math.min(maximum, current + amount);
                if (state == null) states.put(id, ElementRuntimeState.temporary(next, now, duration, 0, nextOrder++));
                else state.setTemporaryFromEffect(next, now, duration);
                if (respectCooldown) startElementApplicationCooldown(id, now, cooldown);
                yield ElementRuntimeState.ApplyResult.APPLIED;
            }
            case "set" -> {
                if (!Double.isFinite(amount) || amount < EPSILON || !Double.isFinite(maximum) || maximum < EPSILON) {
                    yield ElementRuntimeState.ApplyResult.INVALID_INPUT;
                }
                if (respectCooldown && !elementApplicationReady(id, now)) {
                    yield ElementRuntimeState.ApplyResult.BLOCKED_COOLDOWN;
                }
                double accepted = Math.min(amount, maximum);
                if (state == null) {
                    states.put(id, ElementRuntimeState.temporary(accepted, now, duration, 0, nextOrder++));
                } else {
                    state.setTemporaryFromEffect(accepted, now, duration);
                }
                if (respectCooldown) startElementApplicationCooldown(id, now, cooldown);
                yield ElementRuntimeState.ApplyResult.APPLIED;
            }
            case "remove" -> {
                if (!Double.isFinite(amount) || amount < EPSILON || amount > 1_000_000.0D) {
                    yield ElementRuntimeState.ApplyResult.INVALID_INPUT;
                }
                if (state == null || state.consume(amount, now) < EPSILON) {
                    yield ElementRuntimeState.ApplyResult.NO_AMOUNT;
                }
                yield ElementRuntimeState.ApplyResult.APPLIED;
            }
            default -> ElementRuntimeState.ApplyResult.INVALID_INPUT;
        };
    }

    public void clear() {
        states.clear();
        resistances.clear();
        virtualCooldowns.clear();
        applicationCooldowns.clear();
        nextOrder = 0L;
        intrinsicAttack = null;
        initialized = false;
        appliedGeneration = Long.MIN_VALUE;
    }

    public boolean initialized() {
        return initialized;
    }

    public void markInitialized(long generation) {
        initialized = true;
        appliedGeneration = generation;
    }

    public long appliedGeneration() {
        return appliedGeneration;
    }

    public CompoundTag serializeNBT() {
        CompoundTag root = new CompoundTag();
        root.putInt("version", 2);
        root.putLong("next_order", nextOrder);
        root.putBoolean("initialized", initialized);
        root.putLong("generation", appliedGeneration);
        ListTag elements = new ListTag();
        states.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
                .forEach(entry -> {
                    CompoundTag value = entry.getValue().serializeNBT();
                    value.putString("id", entry.getKey().toString());
                    elements.add(value);
                });
        root.put("elements", elements);
        ListTag resistanceValues = new ListTag();
        resistances.forEach((id, value) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", id.toString());
            entry.putDouble("value", value);
            resistanceValues.add(entry);
        });
        root.put("resistances", resistanceValues);
        if (intrinsicAttack != null) {
            CompoundTag attack = new CompoundTag();
            attack.putString("element", intrinsicAttack.element().toString());
            attack.putDouble("amount", intrinsicAttack.baseAmount());
            root.put("intrinsic", attack);
        }
        return root;
    }

    public void deserializeNBT(CompoundTag root) {
        clear();
        if (root.getInt("version") != 2) return;
        ListTag elements = root.getList("elements", Tag.TAG_COMPOUND);
        for (int i = 0; i < elements.size(); i++) {
            CompoundTag value = elements.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(value.getString("id"));
            if (id != null) states.put(id, ElementRuntimeState.deserializeNBT(value));
        }
        ListTag resistanceValues = root.getList("resistances", Tag.TAG_COMPOUND);
        for (int i = 0; i < resistanceValues.size(); i++) {
            CompoundTag value = resistanceValues.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(value.getString("id"));
            double amount = value.getDouble("value");
            if (id != null && Double.isFinite(amount) && amount >= -1.0D && amount <= 1.0D) resistances.put(id, amount);
        }
        if (root.contains("intrinsic", Tag.TAG_COMPOUND)) {
            CompoundTag value = root.getCompound("intrinsic");
            ResourceLocation element = ResourceLocation.tryParse(value.getString("element"));
            double amount = value.getDouble("amount");
            if (element != null && Double.isFinite(amount) && amount >= EPSILON) intrinsicAttack = new IntrinsicAttack(element, amount);
        }
        long maximumOrder = states.values().stream().map(ElementRuntimeState::order)
                .mapToLong(Long::longValue).max().orElse(-1L);
        nextOrder = Math.max(root.getLong("next_order"), maximumOrder + 1L);
        initialized = root.getBoolean("initialized");
        appliedGeneration = root.getLong("generation");
    }

    public record IntrinsicAttack(ResourceLocation element, double baseAmount) {
    }

    private record VirtualCooldown(long until, boolean overflowed) {
        private boolean active(long now) {
            return now < until || overflowed && now == Long.MAX_VALUE;
        }
    }

    private static long deadline(long now, int ticks) {
        return ticks > 0 && now > Long.MAX_VALUE - ticks ? Long.MAX_VALUE : now + ticks;
    }

}
