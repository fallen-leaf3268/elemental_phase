package com.elementalphase.display;

import com.elementalphase.api.ElementalPhaseApi;
import com.elementalphase.data.model.ReactionDamageDefinition;
import com.elementalphase.display.PendingMainDamageTracker.ReactionLabel;
import com.elementalphase.display.PendingMainDamageTracker.Appearance;
import com.elementalphase.reaction.ReactionOutcome;
import com.elementalphase.reaction.ReactionPlan;
import com.elementalphase.reaction.ReactionOutcome.TriggeredReaction;
import com.elementalphase.integration.damagenumber.DamageNumberCompat;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingDamageEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class DamageDisplayCoordinator {
    private static final int MAX_QUEUED_REACTIONS = 4096;
    private static final int MAX_AREA_TARGETS = 64;
    private static final double MIN_ATTACHMENT_AMOUNT = 0.000001D;
    private final DamagePopupSink sink;
    private final PendingMainDamageTracker<DamageSource> mainDamage = new PendingMainDamageTracker<>();
    private final Deque<PendingAdditionalDamage> additionalDamage = new ArrayDeque<>();
    private final PendingFinalDamageQueue<LivingDamageEvent, PendingDamagePopup> finalDamage = new PendingFinalDamageQueue<>();

    public DamageDisplayCoordinator(DamagePopupSink sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    public void recordReactionAttack(LivingEntity target, DamageSource source, ReactionOutcome outcome) {
        if (!(target.level() instanceof ServerLevel level) || ReactionDamageContext.current().isPresent()) {
            return;
        }
        long tick = level.getServer().getTickCount();
        var amplifying = outcome.reactions().stream()
                .filter(reaction -> reaction.mode() == ReactionDamageDefinition.Mode.AMPLIFY).toList();
        List<ReactionLabel> labels = stableDistinct(amplifying.stream().filter(TriggeredReaction::showName)
                .map(reaction -> new ReactionLabel(reaction.id(), reaction.displayColor()))
                .toList());
        var appearance = new Appearance(amplifying.isEmpty() ? DamageColorResolver.resolve(source)
                : amplifying.get(0).displayColor(), labels);
        mainDamage.record(source, target.getId(), tick, appearance);
        if (!amplifying.isEmpty()) DamageNumberCompat.record(source, tick, appearance);
        for (TriggeredReaction reaction : outcome.additionalReactions()) {
            if (additionalDamage.size() >= MAX_QUEUED_REACTIONS) {
                break;
            }
            if (Double.isFinite(reaction.damage()) && reaction.damage() > 0.0D) {
                additionalDamage.addLast(new PendingAdditionalDamage(target, level, source, reaction,
                        target.position(), tick));
            }
        }
    }

    public void recordReactionAttack(LivingEntity target, DamageSource source, ReactionPlan plan) {
        if (!(target.level() instanceof ServerLevel level) || ReactionDamageContext.current().isPresent()) return;
        long tick = level.getServer().getTickCount();
        var appearance = appearance(plan, DamageColorResolver.resolve(source));
        mainDamage.record(source, target.getId(), tick, appearance);
        if (!plan.mainDamageLabels().isEmpty()) DamageNumberCompat.record(source, tick, appearance);
    }

    public void onFinalDamage(LivingDamageEvent event) {
        LivingEntity target = event.getEntity();
        if (!(target.level() instanceof ServerLevel level)) {
            return;
        }
        var internal = ReactionDamageContext.currentFor(event.getSource());
        Optional<Appearance> appearance = ReactionDamageContext.current().isPresent() ? Optional.empty()
                : mainDamage.consume(event.getSource(), target.getId(), level.getServer().getTickCount());
        if (internal.isPresent()) {
            TriggeredReaction reaction = internal.get();
            finalDamage.record(event, new PendingDamagePopup(target, event.getSource(),
                    reaction.displayColor(), reaction.showName() ? List.of(reaction.id()) : List.of()));
        } else if (appearance.isPresent()) {
            var saved = appearance.orElseThrow();
            finalDamage.record(event, new PendingDamagePopup(target, event.getSource(),
                    saved.color(), saved.visibleLabels().stream().map(ReactionLabel::id).toList()));
        } else {
            finalDamage.record(event, new PendingDamagePopup(target, event.getSource(),
                    DamageColorResolver.resolve(event.getSource()), List.of()));
        }
    }

    public void drain(MinecraftServer server) {
        long tick = server.getTickCount();
        int count = additionalDamage.size();
        for (int i = 0; i < count; i++) {
            PendingAdditionalDamage pending = additionalDamage.pollFirst();
            if (pending == null) {
                break;
            }
            LivingEntity target = pending.target();
            if (pending.level().getServer() != server || pending.tick() < tick - 1L
                    || pending.tick() > tick) {
                continue;
            }
            TriggeredReaction reaction = pending.reaction();
            if (!Double.isFinite(reaction.damage()) || reaction.damage() <= 0.0D) {
                continue;
            }
            var damageType = reaction.damageType().orElse(ReactionDamageDefinition.DEFAULT_DAMAGE_TYPE);
            var holder = pending.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                    .getHolder(ResourceKey.create(Registries.DAMAGE_TYPE, damageType));
            if (holder.isEmpty()) {
                continue;
            }
            DamageSource source = new DamageSource(holder.get(), pending.source().getDirectEntity(),
                    pending.source().getEntity(), pending.source().sourcePositionRaw());
            float damage = (float) Math.min(reaction.damage(), 1_000_000.0D);
            if (reaction.area().isPresent()) {
                applyAreaDamage(pending, source, damage, tick);
            } else if (validTarget(target, pending.level())) {
                applyReactionDamage(target, source, reaction, damage, tick);
            }
        }
        mainDamage.clearBefore(tick + 1L);
        for (PendingFinalDamageQueue.Outcome<PendingDamagePopup> outcome : finalDamage.drainOutcomes(event ->
                new PendingFinalDamageQueue.FinalState(event.isCanceled(), event.getAmount()))) {
            PendingDamagePopup popup = outcome.payload();
            if (!outcome.canceled() && outcome.damage() > 0.0D) {
                sink.send(popup.target(), popup.source(), outcome.damage(), popup.color(), popup.reactionIds());
            }
        }
    }

    private static void applyAreaDamage(PendingAdditionalDamage pending, DamageSource source, float damage, long tick) {
        var area = pending.reaction().area().orElseThrow();
        ServerLevel level = pending.level();
        LivingEntity attacker = pending.source().getEntity() instanceof LivingEntity living ? living : null;
        double radiusSquared = area.radius() * area.radius();
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class,
                new AABB(pending.center(), pending.center()).inflate(area.radius()));
        List<LivingEntity> targets = AreaDamageTargetSelector.select(pending.target(), attacker, candidates,
                entity -> validTarget(entity, level),
                entity -> entity.position().distanceToSqr(pending.center()),
                LivingEntity::getId, radiusSquared, MAX_AREA_TARGETS);
        for (LivingEntity target : targets) {
            DamageSource targetSource = new DamageSource(source.typeHolder(), pending.source().getDirectEntity(),
                    pending.source().getEntity(), pending.source().sourcePositionRaw());
            boolean accepted = applyReactionDamage(target, targetSource, pending.reaction(), damage, tick);
            if (target != pending.target() && accepted && target.isAlive()) {
                applySpread(target, area.spreadElement(), area.attachmentAmount());
            }
        }
        if (area.attachAttacker() && attacker != null && attacker != pending.target()
                && validTarget(attacker, level)
                && attacker.position().distanceToSqr(pending.center()) <= radiusSquared) {
            applySpread(attacker, area.spreadElement(), area.attachmentAmount());
        }
    }

    private static boolean applyReactionDamage(LivingEntity target, DamageSource source, TriggeredReaction reaction,
                                               float damage, long tick) {
        DamageNumberCompat.record(source, tick, DamageNumberCompat.appearance(reaction));
        return ReactionDamageContext.call(reaction, source, () -> target.hurt(source, damage));
    }

    private static void applySpread(LivingEntity target, net.minecraft.resources.ResourceLocation element,
                                    double amount) {
        if (Double.isFinite(amount) && amount >= MIN_ATTACHMENT_AMOUNT) {
            ElementalPhaseApi.applyTemporary(target, element, amount);
        }
    }

    private static boolean validTarget(LivingEntity target, ServerLevel level) {
        return target.level() == level && !target.isRemoved() && target.isAlive();
    }

    static List<ReactionLabel> visibleLabels(List<ReactionPlan.Label> labels) {
        return stableDistinct(labels.stream().filter(ReactionPlan.Label::visible)
                .map(value -> new ReactionLabel(value.reactionId(), value.color())).toList());
    }

    public static Appearance appearance(ReactionPlan plan, int fallbackColor) {
        var labels = plan.mainDamageLabels();
        return new Appearance(labels.isEmpty() ? fallbackColor : labels.get(0).color(), visibleLabels(labels));
    }

    private static List<ReactionLabel> stableDistinct(List<ReactionLabel> labels) {
        return List.copyOf(new LinkedHashSet<>(labels));
    }

    public void clear() {
        mainDamage.clear();
        additionalDamage.clear();
        finalDamage.clear();
    }

    private record PendingAdditionalDamage(LivingEntity target, ServerLevel level, DamageSource source,
                                           TriggeredReaction reaction, Vec3 center, long tick) {
    }

    private record PendingDamagePopup(LivingEntity target, DamageSource source, int color,
                                      List<net.minecraft.resources.ResourceLocation> reactionIds) {
        private PendingDamagePopup {
            reactionIds = List.copyOf(reactionIds);
        }
    }
}
