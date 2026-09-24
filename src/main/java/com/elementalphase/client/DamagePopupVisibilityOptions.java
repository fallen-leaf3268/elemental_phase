package com.elementalphase.client;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

final class DamagePopupVisibilityOptions {
    private DamagePopupVisibilityOptions() {
    }

    static Visibility resolve(ResourceLocation entityTypeId, boolean showDamage, boolean showReactions,
                              List<? extends String> damageBlacklist,
                              List<? extends String> reactionBlacklist) {
        return new Visibility(
                showDamage && !DamagePopupBlacklist.contains(entityTypeId, damageBlacklist),
                showReactions && !DamagePopupBlacklist.contains(entityTypeId, reactionBlacklist));
    }

    record Visibility(boolean showDamage, boolean showReactions) {
    }
}
