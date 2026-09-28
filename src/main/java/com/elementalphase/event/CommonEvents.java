package com.elementalphase.event;

import com.elementalphase.ElementalPhase;
import com.elementalphase.capability.ElementalCapabilities;
import com.elementalphase.capability.ElementalStateProvider;
import com.elementalphase.combat.AttackElementResolver;
import com.elementalphase.combat.CombatPipeline;
import com.elementalphase.combat.ProjectileSnapshotManager;
import com.elementalphase.combat.QueuedHitExecution;
import com.elementalphase.combat.ReactionExecutionScheduler;
import com.elementalphase.command.ElementalPhaseCommands;
import com.elementalphase.data.ElementDataManager;
import com.elementalphase.data.ElementReloadListener;
import com.elementalphase.data.ElementDataRuntimeValidator;
import com.elementalphase.data.model.ReactionCondition;
import com.elementalphase.effect.ReactionActionExecutor;
import com.elementalphase.display.DamageDisplayCoordinator;
import com.elementalphase.display.DamageDisplayPreferenceStore;
import com.elementalphase.display.ReactionDamageContext;
import com.elementalphase.network.ModNetwork;
import com.elementalphase.integration.damagenumber.DamageNumberCompat;
import com.elementalphase.profile.EntityProfileResolver;
import com.elementalphase.reaction.runtime.ReactionRuntimeController;
import com.elementalphase.state.ElementSourceSnapshot;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.player.ArrowLooseEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.util.Optional;

@Mod.EventBusSubscriber(modid = ElementalPhase.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CommonEvents {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation STATE_ID = ResourceLocation.fromNamespaceAndPath(ElementalPhase.MOD_ID, "state");
    private static final EntityProfileResolver PROFILE_RESOLVER = new EntityProfileResolver();
    private static final AttackElementResolver ATTACK_RESOLVER = new AttackElementResolver();
    private static final CombatPipeline COMBAT_PIPELINE = new CombatPipeline();
    private static final ReactionExecutionScheduler REACTION_SCHEDULER =
            new ReactionExecutionScheduler(new ReactionActionExecutor());
    private static final ProjectileSnapshotManager PROJECTILE_SNAPSHOTS = new ProjectileSnapshotManager();
    private static final DamageDisplayCoordinator DAMAGE_DISPLAY = new DamageDisplayCoordinator(ModNetwork::sendDamagePopup);

    private CommonEvents() {
    }

    @SubscribeEvent
    public static void attachCapabilities(AttachCapabilitiesEvent<Entity> event) {
        if (!(event.getObject() instanceof LivingEntity)) {
            return;
        }
        ElementalStateProvider provider = new ElementalStateProvider();
        event.addCapability(STATE_ID, provider);
        event.addListener(provider::invalidate);
    }

    @SubscribeEvent
    public static void addReloadListener(AddReloadListenerEvent event) {
        event.addListener(new ElementReloadListener(event.getServerResources()));
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        ElementalPhaseCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void commitAfterTags(TagsUpdatedEvent event) {
        if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            return;
        }
        commitStaged(ServerLifecycleHooks.getCurrentServer());
    }

    @SubscribeEvent
    public static void commitInitialSnapshot(ServerAboutToStartEvent event) {
        commitStaged(event.getServer());
    }

    @SubscribeEvent
    public static void clearServerState(ServerStoppedEvent event) {
        ElementDataManager.clearServerState();
        PROJECTILE_SNAPSHOTS.clear();
        REACTION_SCHEDULER.clear();
        DAMAGE_DISPLAY.clear();
        DamageDisplayPreferenceStore.INSTANCE.clear();
        DamageNumberCompat.clear();
        ReactionRuntimeController.INSTANCE.clear(event.getServer());
    }

    @SubscribeEvent
    public static void clearPlayerPreferences(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!event.getEntity().level().isClientSide()) {
            DamageDisplayPreferenceStore.INSTANCE.remove(event.getEntity().getUUID());
            ReactionRuntimeController.INSTANCE.remove(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void initializeEntity(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof Projectile projectile) {
            PROJECTILE_SNAPSHOTS.apply(projectile, event.getLevel().getGameTime(), event.loadedFromDisk(),
                    ATTACK_RESOLVER, ElementDataManager.snapshot());
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity entity)) {
            return;
        }
        ElementalCapabilities.get(entity).ifPresent(state -> {
            PROFILE_RESOLVER.initializeIfNeeded(entity, state, ElementDataManager.snapshot());
        });
    }

    @SubscribeEvent
    public static void captureArrowShot(ArrowLooseEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        captureProjectileWeapon(event.getEntity(), event.getBow());
    }

    public static void captureProjectileWeapon(LivingEntity shooter, net.minecraft.world.item.ItemStack weapon) {
        if (!shooter.level().isClientSide()) {
            PROJECTILE_SNAPSHOTS.capture(shooter, weapon, shooter.level().getGameTime(), ATTACK_RESOLVER,
                    ElementDataManager.snapshot());
        }
    }

    @SubscribeEvent
    public static void syncElementCatalog(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            ModNetwork.sendElementCatalog(player);
        }
    }


    @SubscribeEvent
    public static void resetPlayerAfterDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        ReactionRuntimeController.INSTANCE.remove(event.getEntity());
        ElementalCapabilities.get(event.getEntity()).ifPresent(state ->
                PROFILE_RESOLVER.apply(event.getEntity(), state, ElementDataManager.snapshot()));
    }

    @SubscribeEvent
    public static void tickReactionRuntime(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.level instanceof net.minecraft.server.level.ServerLevel level) {
            ReactionRuntimeController.INSTANCE.tickLevelEnd(level, level.getGameTime());
        }
    }

    @SubscribeEvent
    public static void syncTrackedReactionRuntime(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                && event.getTarget() instanceof LivingEntity target) {
            ReactionRuntimeController.INSTANCE.syncTo(player, target);
        }
    }

    @SubscribeEvent
    public static void removeReactionRuntimeEntity(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof LivingEntity living) {
            ReactionRuntimeController.INSTANCE.remove(living);
            ElementalCapabilities.get(living).ifPresent(state -> state.clearVirtualElements());
        }
    }

    @SubscribeEvent
    public static void removeDeadReactionRuntimeEntity(LivingDeathEvent event) {
        if (!event.getEntity().level().isClientSide()) ReactionRuntimeController.INSTANCE.remove(event.getEntity());
    }

    @SubscribeEvent
    public static void clearExpiredProjectileSnapshots(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            PROJECTILE_SNAPSHOTS.clearExpired(event.getServer().overworld().getGameTime());
            REACTION_SCHEDULER.drain(event.getServer());
            DAMAGE_DISPLAY.drain(event.getServer());
            DamageNumberCompat.clearBefore(event.getServer().getTickCount() + 1L);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
    public static void applyElementalCombat(LivingHurtEvent event) {
        if (ReactionDamageContext.current().isPresent() && !ReactionDamageContext.allowsElementApplication()) {
            return;
        }
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide() || !target.isAlive() || !Float.isFinite(event.getAmount()) || event.getAmount() <= 0.0F) {
            return;
        }
        var snapshot = ElementDataManager.snapshot();
        ElementalCapabilities.get(target).ifPresent(state -> {
            PROFILE_RESOLVER.initializeIfNeeded(target, state, snapshot);
            var direct = event.getSource().getDirectEntity();
            var resolvedAttack = direct instanceof Projectile projectile
                    ? ATTACK_RESOLVER.resolveProjectile(event.getSource(), projectile, snapshot)
                    : ATTACK_RESOLVER.resolve(event.getSource(), target, snapshot);
            resolvedAttack.ifPresent(attack -> {
                var element = snapshot.elements().get(attack.element());
                if (element == null) {
                    return;
                }
                ResourceLocation damageType = event.getSource().typeHolder().unwrapKey()
                        .map(key -> key.location()).orElse(ResourceLocation.withDefaultNamespace("generic"));
                ElementSourceSnapshot sourceSnapshot = new ElementSourceSnapshot(
                        Optional.ofNullable(event.getSource().getEntity()).map(Entity::getUUID),
                        Optional.ofNullable(direct).map(Entity::getUUID), attack.sourceId(), damageType,
                        attack.element(), attack.elementStrength(), target.level().getGameTime());
                if (ReactionDamageContext.current().isPresent() && !ReactionDamageContext.allowsReactions()) {
                    state.applyElement(element, attack.mountAmount(), target.level().getGameTime(),
                            element.attachment().durationTicks(), true, sourceSnapshot);
                    event.setAmount((float) com.elementalphase.combat.ResistancePolicy.apply(
                            event.getAmount(), state.resistance(attack.element())));
                    return;
                }
                var reservation = REACTION_SCHEDULER.reserve(target.getServer());
                if (reservation.isEmpty()) {
                    LOGGER.warn("Elemental reaction execution queue is full; skipping elemental processing for this hit");
                    return;
                }
                LivingEntity attacker = event.getSource().getEntity() instanceof LivingEntity living ? living : null;
                com.elementalphase.reaction.ReactionChainGuard guard = ReactionDamageContext.chainGuard()
                        .orElseGet(com.elementalphase.reaction.ReactionChainGuard::new);
                try {
                    var result = COMBAT_PIPELINE.resolve(new CombatPipeline.CombatInput(event.getAmount(),
                            state.resistance(attack.element()), attack, state, target.level().getGameTime(), element,
                            snapshot.reactionIndex(), snapshot.elements(), sourceSnapshot,
                            attacker instanceof Player player ? player.experienceLevel : 0.0D,
                            target.getHealth(), target.getMaxHealth(),
                            condition -> conditionMatches(condition, event.getSource(), attacker, target,
                                    event.getAmount()), guard));
                    event.setAmount((float) result.finalPreArmorDamage());
                    DAMAGE_DISPLAY.recordReactionAttack(target, event.getSource(), result.reactionPlan());
                    if (result.reactionPlan().actions().isEmpty()) {
                        REACTION_SCHEDULER.release(reservation.get());
                    } else {
                        REACTION_SCHEDULER.commit(reservation.get(), new QueuedHitExecution(
                                 (net.minecraft.server.level.ServerLevel) target.level(), attacker, target,
                                 event.getSource(), result.reactionPlan(), snapshot, target.level().getGameTime(), guard));
                    }
                } catch (RuntimeException exception) {
                    REACTION_SCHEDULER.release(reservation.get());
                    throw exception;
                }
            });
        });
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void displayFinalDamage(LivingDamageEvent event) {
        // LOWEST 只捕获事件；tick END 在实际扣血完成后结算并发送飘字。
        DAMAGE_DISPLAY.onFinalDamage(event);
    }

    private static void commitStaged(MinecraftServer server) {
        if (server == null) {
            return;
        }
        var staged = ElementDataManager.takeStaged(server.getServerResources().managers());
        if (staged == null) {
            return;
        }
        var report = ElementDataRuntimeValidator.validate(staged, server.registryAccess());
        var overlayReport = ElementDataManager.replace(report.snapshot(), server.registryAccess());
        for (var level : server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (entity instanceof LivingEntity living) {
                    ElementalCapabilities.get(living).ifPresent(state ->
                            PROFILE_RESOLVER.apply(living, state, ElementDataManager.snapshot()));
                }
            }
        }
        ReactionRuntimeController.INSTANCE.clear(server);
        ElementDataManager.snapshot().elements().keySet().stream()
                .filter(element -> com.elementalphase.registry.ModEnchantments.forElement(element).isEmpty())
                .sorted(java.util.Comparator.comparing(ResourceLocation::toString))
                .forEach(element -> LOGGER.warn("Element {} has no registered attachment enchantment; "
                        + "restart the game or server after adding its data pack", element));
        server.getPlayerList().getPlayers().forEach(ModNetwork::sendElementCatalog);
        for (var error : report.errors()) {
            LOGGER.error("Skipping elemental data {}: {}", error.resource(), error.message());
        }
        for (var error : overlayReport.errors()) {
            LOGGER.error("Skipping KubeJS elemental data {}: {}", error.resource(), error.message());
        }
        LOGGER.info("Loaded elemental data: {} elements, {} reactions, {} profiles; {} files skipped",
                report.snapshot().elements().size(), report.snapshot().reactions().size(),
                report.snapshot().entityProfiles().size(),
                report.errors().size() + overlayReport.errors().size());
    }

    public static boolean conditionMatches(ReactionCondition condition,
                                            net.minecraft.world.damagesource.DamageSource source,
                                            LivingEntity attacker, LivingEntity target, double damage) {
        boolean result;
        if (condition instanceof ReactionCondition.AttackerPresent value) {
            result = (attacker != null) == value.value();
        } else if (condition instanceof ReactionCondition.AttackerEntity value) {
            result = attacker != null && entityMatches(attacker, value.entity(), value.tag());
        } else if (condition instanceof ReactionCondition.TargetEntity value) {
            result = entityMatches(target, value.entity(), value.tag());
        } else if (condition instanceof ReactionCondition.DamageType value) {
            if (value.damageType().isPresent()) {
                ResourceLocation expected = value.damageType().orElseThrow();
                result = source.typeHolder().unwrapKey().map(key -> key.location().equals(expected)).orElse(false);
            } else {
                TagKey<net.minecraft.world.damagesource.DamageType> expected =
                        TagKey.create(Registries.DAMAGE_TYPE, value.tag().orElseThrow());
                result = source.typeHolder().is(expected);
            }
        } else if (condition instanceof ReactionCondition.MinimumDamage value) {
            result = damage >= value.value();
        } else if (condition instanceof ReactionCondition.TargetState value) {
            boolean active = switch (value.state()) {
                case ON_FIRE -> target.isOnFire();
                case IN_WATER -> target.isInWater();
                case FROZEN -> target.level() instanceof net.minecraft.server.level.ServerLevel level
                        && ReactionRuntimeController.INSTANCE.isFrozen(level, target, level.getGameTime());
            };
            result = active == value.value();
        } else {
            result = false;
        }
        return condition.inverted() ? !result : result;
    }

    private static boolean entityMatches(LivingEntity entity, Optional<ResourceLocation> type,
                                         Optional<ResourceLocation> tag) {
        return type.map(id -> net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).equals(id))
                .orElseGet(() -> entity.getType().builtInRegistryHolder()
                        .is(TagKey.create(Registries.ENTITY_TYPE, tag.orElseThrow())));
    }
}
