package com.elementalphase.state;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ElementRuntimeState {
    private static final double EPSILON = 0.000001D;
    private static final long NO_TIME = Long.MIN_VALUE;

    private double permanentPreset;
    private int restoreDelayTicks;
    private final long order;
    private double permanentCurrent;
    private boolean hasTemporary;
    private boolean virtual;
    private double temporaryAmount;
    private long temporaryStartedAt = NO_TIME;
    private int temporaryDurationTicks;
    private long temporaryExpiresAt = NO_TIME;
    private long recoveryAt = NO_TIME;
    private long cooldownUntil = NO_TIME;
    private ElementSourceSnapshot temporarySource;
    private ElementSourceSnapshot permanentSource;

    private ElementRuntimeState(double permanentPreset, int restoreDelayTicks, long order) {
        this.permanentPreset = permanentPreset;
        this.permanentCurrent = permanentPreset;
        this.restoreDelayTicks = restoreDelayTicks;
        this.order = order;
    }

    public static ElementRuntimeState permanent(double preset, int restoreDelayTicks, long order) {
        if (!validAmount(preset) || restoreDelayTicks < 1) {
            throw new IllegalArgumentException("Invalid permanent element state");
        }
        return new ElementRuntimeState(preset, restoreDelayTicks, order);
    }

    public static ElementRuntimeState temporary(double amount, long now, int duration, int cooldown, long order) {
        return temporary(amount, now, duration, cooldown, order, null);
    }

    public static ElementRuntimeState temporary(double amount, long now, int duration, int cooldown, long order,
                                                ElementSourceSnapshot source) {
        if (!validAmount(amount) || duration < 1 || cooldown < 0) {
            throw new IllegalArgumentException("Invalid temporary element state");
        }
        ElementRuntimeState state = new ElementRuntimeState(0.0D, 1, order);
        state.hasTemporary = true;
        state.temporaryAmount = amount;
        state.temporaryStartedAt = now;
        state.temporaryDurationTicks = duration;
        state.temporaryExpiresAt = deadline(now, duration);
        state.cooldownUntil = deadline(now, cooldown);
        state.temporarySource = source;
        return state;
    }

    public double effectiveAmount(long now) {
        refresh(now);
        return Math.max(permanentCurrent, hasTemporary ? temporaryAmount : 0.0D);
    }

    public boolean virtual() {
        return virtual;
    }

    public void setVirtual(boolean virtual) {
        this.virtual = virtual;
        if (virtual) reconcilePermanent(0.0D, 1);
    }

    public ApplyResult tryApplyTemporary(double amount, long now, int duration, int cooldown) {
        return tryApplyTemporary(amount, now, duration, cooldown, null);
    }

    public ApplyResult tryApplyTemporary(double amount, long now, int duration, int cooldown,
                                         ElementSourceSnapshot source) {
        return tryApplyTemporary(amount, now, duration, cooldown, source, true);
    }

    public ApplyResult tryApplyTemporaryIgnoringCooldown(double amount, long now, int duration,
                                                          ElementSourceSnapshot source) {
        return tryApplyTemporary(amount, now, duration, 0, source, false);
    }

    private ApplyResult tryApplyTemporary(double amount, long now, int duration, int cooldown,
                                           ElementSourceSnapshot source, boolean respectCooldown) {
        if (!validAmount(amount) || duration < 1 || cooldown < 0) {
            return ApplyResult.INVALID_INPUT;
        }
        refresh(now);
        if (respectCooldown && now < cooldownUntil) {
            return ApplyResult.BLOCKED_COOLDOWN;
        }

        double effective = Math.max(permanentCurrent, hasTemporary ? temporaryAmount : 0.0D);
        if (amount < effective) {
            return ApplyResult.IGNORED_LOWER;
        }

        boolean isEqual = Double.compare(amount, effective) == 0;
        hasTemporary = true;
        temporaryAmount = amount;
        temporaryStartedAt = now;
        temporaryDurationTicks = duration;
        temporaryExpiresAt = deadline(now, duration);
        if (respectCooldown) cooldownUntil = deadline(now, cooldown);
        temporarySource = source;
        if (permanentCurrent < permanentPreset) {
            recoveryAt = deadline(now, restoreDelayTicks);
        }
        return isEqual ? ApplyResult.REFRESHED : ApplyResult.APPLIED;
    }

    public ApplyResult addTemporaryFromEffect(double amount, long now, int duration) {
        if (!validAmount(amount) || duration < 1) {
            return ApplyResult.INVALID_INPUT;
        }
        refresh(now);
        double effective = Math.max(permanentCurrent, hasTemporary ? temporaryAmount : 0.0D);
        if (amount < effective) {
            return ApplyResult.IGNORED_LOWER;
        }
        boolean isEqual = Double.compare(amount, effective) == 0;
        hasTemporary = true;
        temporaryAmount = amount;
        temporaryStartedAt = now;
        temporaryDurationTicks = duration;
        temporaryExpiresAt = deadline(now, duration);
        return isEqual ? ApplyResult.REFRESHED : ApplyResult.APPLIED;
    }

    public void setTemporaryFromEffect(double amount, long now, int duration) {
        if (!validAmount(amount) || duration < 1) {
            throw new IllegalArgumentException("Invalid temporary effect state");
        }
        refresh(now);
        hasTemporary = true;
        temporaryAmount = amount;
        temporaryStartedAt = now;
        temporaryDurationTicks = duration;
        temporaryExpiresAt = deadline(now, duration);
    }

    public double consume(double requested, long now) {
        return consumePortions(requested, now).stream().mapToDouble(ElementPortion::amount).sum();
    }

    public void consumeAllActive(long now) {
        consumePortions(effectiveAmount(now), now);
    }

    public List<ElementPortion> consumePortions(double requested, long now) {
        refresh(now);
        if (!Double.isFinite(requested) || requested <= 0.0D) {
            return List.of();
        }

        double temporary = hasTemporary ? temporaryAmount : 0.0D;
        double effective = Math.max(permanentCurrent, temporary);
        double consumed = Math.min(requested, effective);
        double remaining = consumed;
        double nextPermanent = permanentCurrent;
        double nextTemporary = temporary;
        long originalExpiry = temporaryExpiresAt;
        Optional<ElementSourceSnapshot> temporarySourceValue = Optional.ofNullable(temporarySource);
        Optional<ElementSourceSnapshot> permanentSourceValue = Optional.ofNullable(permanentSource);
        List<ElementPortion> portions = new ArrayList<>(3);

        if (nextTemporary > nextPermanent) {
            double amount = Math.min(remaining, nextTemporary - nextPermanent);
            if (amount >= EPSILON) {
                portions.add(new ElementPortion(ElementPortion.Origin.TEMPORARY_ONLY, amount,
                        temporarySourceValue, Optional.empty(), originalExpiry));
                nextTemporary -= amount;
                remaining -= amount;
            }
        } else if (nextPermanent > nextTemporary) {
            double amount = Math.min(remaining, nextPermanent - nextTemporary);
            if (amount >= EPSILON) {
                portions.add(new ElementPortion(ElementPortion.Origin.PERMANENT_ONLY, amount,
                        Optional.empty(), permanentSourceValue, NO_TIME));
                nextPermanent -= amount;
                remaining -= amount;
            }
        }
        if (remaining >= EPSILON && nextPermanent >= EPSILON && nextTemporary >= EPSILON) {
            double amount = Math.min(remaining, Math.min(nextPermanent, nextTemporary));
            portions.add(new ElementPortion(ElementPortion.Origin.OVERLAP, amount,
                    temporarySourceValue, permanentSourceValue, originalExpiry));
            nextPermanent -= amount;
            nextTemporary -= amount;
            remaining -= amount;
        }
        if (remaining >= EPSILON && nextTemporary >= EPSILON) {
            double amount = Math.min(remaining, nextTemporary);
            portions.add(new ElementPortion(ElementPortion.Origin.TEMPORARY_ONLY, amount,
                    temporarySourceValue, Optional.empty(), originalExpiry));
            nextTemporary -= amount;
            remaining -= amount;
        }
        if (remaining >= EPSILON && nextPermanent >= EPSILON) {
            double amount = Math.min(remaining, nextPermanent);
            portions.add(new ElementPortion(ElementPortion.Origin.PERMANENT_ONLY, amount,
                    Optional.empty(), permanentSourceValue, NO_TIME));
            nextPermanent -= amount;
        }

        if (nextPermanent < EPSILON) {
            nextPermanent = 0.0D;
        }
        if (nextTemporary < EPSILON) {
            nextTemporary = 0.0D;
        }

        if (nextPermanent < permanentCurrent && recoveryAt == NO_TIME && permanentPreset > 0.0D) {
            recoveryAt = deadline(now, restoreDelayTicks);
        }
        permanentCurrent = nextPermanent;
        if (hasTemporary) {
            temporaryAmount = nextTemporary;
            if (nextTemporary == 0.0D) {
                hasTemporary = false;
                temporaryStartedAt = NO_TIME;
                temporaryDurationTicks = 0;
                temporaryExpiresAt = NO_TIME;
                temporarySource = null;
            }
        }
        return List.copyOf(portions);
    }

    public void clearCurrentFromEffect(long now) {
        refresh(now);
        if (permanentCurrent > 0.0D && recoveryAt == NO_TIME && permanentPreset > 0.0D) {
            recoveryAt = deadline(now, restoreDelayTicks);
        }
        permanentCurrent = 0.0D;
        hasTemporary = false;
        temporaryAmount = 0.0D;
        temporaryStartedAt = NO_TIME;
        temporaryDurationTicks = 0;
        temporaryExpiresAt = NO_TIME;
        temporarySource = null;
    }

    public void clearAll() {
        permanentPreset = 0.0D;
        permanentCurrent = 0.0D;
        hasTemporary = false;
        temporaryAmount = 0.0D;
        temporaryStartedAt = NO_TIME;
        temporaryDurationTicks = 0;
        temporaryExpiresAt = NO_TIME;
        recoveryAt = NO_TIME;
        temporarySource = null;
        permanentSource = null;
    }

    public void refresh(long now) {
        if (hasTemporary && now >= temporaryExpiresAt) {
            hasTemporary = false;
            temporaryAmount = 0.0D;
            temporaryStartedAt = NO_TIME;
            temporaryDurationTicks = 0;
            temporaryExpiresAt = NO_TIME;
            temporarySource = null;
        }
        if (recoveryAt != NO_TIME && now >= recoveryAt) {
            permanentCurrent = permanentPreset;
            recoveryAt = NO_TIME;
        }
    }

    public boolean active(long now) {
        return effectiveAmount(now) >= EPSILON;
    }

    public long order() {
        return order;
    }

    public long temporaryExpiresAt() {
        return temporaryExpiresAt;
    }

    public long temporaryStartedAt() {
        return temporaryStartedAt;
    }

    public int temporaryDurationTicks() {
        return temporaryDurationTicks;
    }

    public int temporaryRemainingTicks(long now) {
        if (!hasTemporary || temporaryExpiresAt == NO_TIME || now >= temporaryExpiresAt) return 0;
        return (int) Math.min(Integer.MAX_VALUE, temporaryExpiresAt - now);
    }

    public long recoveryAt() {
        return recoveryAt;
    }

    public double permanentPreset() {
        return permanentPreset;
    }

    public double permanentCurrent() {
        return permanentCurrent;
    }

    public double temporaryAmount() {
        return hasTemporary ? temporaryAmount : 0.0D;
    }

    public long cooldownUntil() {
        return cooldownUntil;
    }

    public Optional<ElementSourceSnapshot> temporarySource() {
        return Optional.ofNullable(temporarySource);
    }

    public Optional<ElementSourceSnapshot> permanentSource() {
        return Optional.ofNullable(permanentSource);
    }

    public void restorePortion(ElementPortion portion, long now) {
        if (portion.origin() == ElementPortion.Origin.TRIGGER_POOL) return;
        if (portion.origin() == ElementPortion.Origin.PERMANENT_ONLY || portion.origin() == ElementPortion.Origin.OVERLAP) {
            permanentCurrent = Math.min(permanentPreset, permanentCurrent + portion.amount());
            portion.permanentSource().ifPresent(value -> permanentSource = value);
            if (permanentCurrent + EPSILON >= permanentPreset) recoveryAt = NO_TIME;
        }
        if ((portion.origin() == ElementPortion.Origin.TEMPORARY_ONLY || portion.origin() == ElementPortion.Origin.OVERLAP)
                && portion.originalTemporaryExpiresAt() > now) {
            hasTemporary = true;
            temporaryAmount = Math.min(1_000_000.0D, temporaryAmount + portion.amount());
            temporaryExpiresAt = Math.max(temporaryExpiresAt, portion.originalTemporaryExpiresAt());
            temporaryStartedAt = now;
            temporaryDurationTicks = (int) Math.min(Integer.MAX_VALUE, temporaryExpiresAt - now);
            portion.temporarySource().ifPresent(value -> temporarySource = value);
        }
    }

    public boolean hasPermanentPreset() {
        return permanentPreset >= EPSILON;
    }

    public boolean canDiscard(long now) {
        refresh(now);
        return !hasPermanentPreset() && !hasTemporary && now >= cooldownUntil;
    }

    public void clampAmount(double maximum, long now) {
        if (!Double.isFinite(maximum) || maximum < EPSILON || maximum > 1_000_000.0D) {
            throw new IllegalArgumentException("Invalid element maximum");
        }
        refresh(now);
        permanentPreset = Math.min(permanentPreset, maximum);
        permanentCurrent = Math.min(permanentCurrent, maximum);
        if (hasTemporary) {
            temporaryAmount = Math.min(temporaryAmount, maximum);
        }
    }

    public void reconcilePermanent(double preset, int restoreDelayTicks) {
        if (!Double.isFinite(preset) || preset < 0.0D || preset > 1_000_000.0D || restoreDelayTicks < 1) {
            throw new IllegalArgumentException("Invalid permanent element state");
        }
        boolean full = permanentPreset >= EPSILON && Math.abs(permanentCurrent - permanentPreset) < EPSILON
                && recoveryAt == NO_TIME;
        permanentPreset = preset;
        this.restoreDelayTicks = restoreDelayTicks;
        if (preset < EPSILON) {
            permanentPreset = 0.0D;
            permanentCurrent = 0.0D;
            recoveryAt = NO_TIME;
        } else if (full || permanentCurrent < EPSILON && recoveryAt == NO_TIME) {
            permanentCurrent = preset;
        } else {
            permanentCurrent = Math.min(permanentCurrent, preset);
        }
    }

    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("permanent_preset", permanentPreset);
        tag.putDouble("permanent_current", permanentCurrent);
        tag.putInt("restore_delay", restoreDelayTicks);
        tag.putLong("order", order);
        tag.putBoolean("has_temporary", hasTemporary);
        tag.putDouble("temporary_amount", temporaryAmount);
        tag.putLong("temporary_started_at", temporaryStartedAt);
        tag.putInt("temporary_duration", temporaryDurationTicks);
        tag.putLong("temporary_expires_at", temporaryExpiresAt);
        tag.putLong("recovery_at", recoveryAt);
        if (temporarySource != null) tag.put("temporary_source", writeSource(temporarySource));
        if (permanentSource != null) tag.put("permanent_source", writeSource(permanentSource));
        return tag;
    }

    public static ElementRuntimeState deserializeNBT(CompoundTag tag) {
        double preset = bounded(tag.getDouble("permanent_preset"));
        int delay = Math.max(1, tag.getInt("restore_delay"));
        ElementRuntimeState state = new ElementRuntimeState(preset, delay, tag.getLong("order"));
        state.permanentCurrent = Math.min(preset, bounded(tag.getDouble("permanent_current")));
        state.hasTemporary = tag.getBoolean("has_temporary") && validAmount(tag.getDouble("temporary_amount"));
        state.temporaryAmount = state.hasTemporary ? tag.getDouble("temporary_amount") : 0.0D;
        state.temporaryStartedAt = state.hasTemporary ? tag.getLong("temporary_started_at") : NO_TIME;
        state.temporaryDurationTicks = state.hasTemporary ? Math.max(1, tag.getInt("temporary_duration")) : 0;
        state.temporaryExpiresAt = state.hasTemporary ? tag.getLong("temporary_expires_at") : NO_TIME;
        state.recoveryAt = tag.getLong("recovery_at");
        if (tag.contains("temporary_source", CompoundTag.TAG_COMPOUND)) {
            state.temporarySource = readSource(tag.getCompound("temporary_source"));
        }
        if (tag.contains("permanent_source", CompoundTag.TAG_COMPOUND)) {
            state.permanentSource = readSource(tag.getCompound("permanent_source"));
        }
        return state;
    }

    private static CompoundTag writeSource(ElementSourceSnapshot source) {
        CompoundTag tag = new CompoundTag();
        source.attacker().ifPresent(value -> tag.putUUID("attacker", value));
        source.directEntity().ifPresent(value -> tag.putUUID("direct", value));
        source.playerName().ifPresent(value -> tag.putString("player_name", value));
        tag.putString("source_id", source.sourceId().toString());
        tag.putString("damage_type", source.damageType().toString());
        tag.putString("element", source.element().toString());
        tag.putDouble("strength", source.elementStrength());
        tag.putLong("created_at", source.createdAt());
        return tag;
    }

    private static ElementSourceSnapshot readSource(CompoundTag tag) {
        ResourceLocation source = ResourceLocation.tryParse(tag.getString("source_id"));
        ResourceLocation damage = ResourceLocation.tryParse(tag.getString("damage_type"));
        ResourceLocation element = ResourceLocation.tryParse(tag.getString("element"));
        if (source == null || damage == null || element == null) return null;
        double strength = tag.getDouble("strength");
        if (!Double.isFinite(strength)) return null;
        return new ElementSourceSnapshot(tag.hasUUID("attacker") ? Optional.of(tag.getUUID("attacker")) : Optional.empty(),
                tag.hasUUID("direct") ? Optional.of(tag.getUUID("direct")) : Optional.empty(), source, damage, element,
                Math.max(0.0D, Math.min(1_000_000.0D, strength)), tag.getLong("created_at"),
                tag.contains("player_name", CompoundTag.TAG_STRING) ? Optional.of(tag.getString("player_name")) : Optional.empty());
    }

    private static double bounded(double value) {
        return Double.isFinite(value) ? Math.max(0.0D, Math.min(1_000_000.0D, value)) : 0.0D;
    }

    private static boolean validAmount(double amount) {
        return Double.isFinite(amount) && amount >= EPSILON && amount <= 1_000_000.0D;
    }

    private static long deadline(long now, int ticks) {
        return ticks > 0 && now > Long.MAX_VALUE - ticks ? Long.MAX_VALUE : now + ticks;
    }

    public enum ApplyResult {
        APPLIED,
        REFRESHED,
        BLOCKED_COOLDOWN,
        IGNORED_LOWER,
        NO_AMOUNT,
        INVALID_INPUT,
        UNAVAILABLE;

        public boolean changed() {
            return this == APPLIED || this == REFRESHED;
        }
    }
}
