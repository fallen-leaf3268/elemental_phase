package com.elementalphase.integration.damagenumber;

import com.elementalphase.data.ElementDataManager;
import com.elementalphase.display.DamageDisplayPreferenceStore;
import com.elementalphase.display.DamagePopupText;
import com.elementalphase.display.PendingMainDamageTracker.ReactionLabel;
import com.elementalphase.display.PendingMainDamageTracker.Appearance;
import com.elementalphase.reaction.ReactionOutcome;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.util.List;
import java.util.function.Consumer;

public final class DamageNumberCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MOD_ID = "damage_number";
    private static final DamageNumberReactionTracker<DamageSource> REACTIONS = new DamageNumberReactionTracker<>();
    private static DamageNumberEventAdapter adapter;
    private static boolean initialized;

    private DamageNumberCompat() {
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        if (!ModList.get().isLoaded(MOD_ID)) {
            return;
        }
        var resolved = DamageNumberEventAdapter.create(DamageNumberCompat.class.getClassLoader());
        if (resolved.isEmpty()) {
            LOGGER.warn("Damage Number is installed, but its DamageEmitEvent API is unavailable; integration disabled");
            return;
        }
        adapter = resolved.get();
        register(adapter.eventType().asSubclass(Event.class), event -> handle(adapter, event));
    }

    public static void record(DamageSource source, long tick, Appearance appearance) {
        if (adapter == null || !(source.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        REACTIONS.record(source, player.getUUID(), tick, appearance);
    }

    public static void clearBefore(long tick) {
        REACTIONS.clearBefore(tick);
    }

    public static void clear() {
        REACTIONS.clear();
    }

    public static ReactionLabel label(ReactionOutcome.TriggeredReaction reaction) {
        return new ReactionLabel(reaction.id(), reaction.displayColor());
    }

    public static Appearance appearance(ReactionOutcome.TriggeredReaction reaction) {
        return new Appearance(reaction.displayColor(), reaction.showName() ? List.of(label(reaction)) : List.of());
    }

    private static void handle(DamageNumberEventAdapter eventAdapter, Object event) {
        Object sourceValue = eventAdapter.source(event);
        Object playerValue = eventAdapter.player(event);
        if (!(sourceValue instanceof DamageSource source) || !(playerValue instanceof ServerPlayer player)) {
            return;
        }
        long tick = player.getServer().getTickCount();
        REACTIONS.consume(source, player.getUUID(), tick)
                .ifPresent(appearance -> eventAdapter.apply(event, appearance.color(),
                        DamageDisplayPreferenceStore.INSTANCE.showReactions(player.getUUID())
                                ? reactionComponent(appearance.visibleLabels()) : Component.empty()));
    }

    static Component reactionComponent(List<ReactionLabel> labels) {
        var reactions = ElementDataManager.snapshot().reactions();
        MutableComponent component = Component.empty();
        for (ReactionLabel label : labels) {
            component.append(" ").append(Component.translatable(DamagePopupText.translationKey(label.id(), reactions))
                    .withStyle(style -> style.withColor(label.color())));
        }
        return component;
    }

    private static <T extends Event> void register(Class<T> eventType, Consumer<T> listener) {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, eventType, listener);
    }
}
