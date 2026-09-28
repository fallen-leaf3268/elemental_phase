package com.elementalphase.display;

import com.elementalphase.data.model.ReactionSpec;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class DamagePopupText {
    private static Map<ResourceLocation, String> reactionNames = Map.of();

    private DamagePopupText() {
    }

    public static List<String> translationKeys(List<ResourceLocation> reactionIds) {
        return reactionIds.stream().map(DamagePopupText::translationKey).toList();
    }

    public static String translationKey(ResourceLocation id) {
        return reactionNames.getOrDefault(id, ReactionSpec.defaultTranslationKey(id));
    }

    public static String translationKey(ResourceLocation id, Map<ResourceLocation, ReactionSpec> reactions) {
        ReactionSpec reaction = reactions.get(id);
        return reaction == null ? ReactionSpec.defaultTranslationKey(id) : reaction.translationKey();
    }

    public static void replaceReactionNames(Map<ResourceLocation, String> names) {
        reactionNames = Map.copyOf(names);
    }

    public static String formatDamage(double damage) {
        return BigDecimal.valueOf(damage).setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    public static Component component(double damage, List<ResourceLocation> reactionIds) {
        return component(damage, reactionIds, true, true).orElseGet(Component::empty);
    }

    public static Optional<Component> component(double damage, List<ResourceLocation> reactionIds,
                                                boolean showDamage, boolean showReactions) {
        MutableComponent text = Component.empty();
        boolean hasText = false;
        if (showDamage) {
            text = text.append(formatDamage(damage));
            hasText = true;
        }
        if (showReactions) {
            for (ResourceLocation reactionId : reactionIds) {
                if (hasText) {
                    text = text.append(" ");
                }
                text = text.append(Component.translatable(translationKey(reactionId)));
                hasText = true;
            }
        }
        return hasText ? Optional.of(text) : Optional.empty();
    }
}
