package com.elementalphase.combat;

import com.elementalphase.integration.bettercombat.BetterCombatCompat;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.util.function.Predicate;

public final class MeleeWeaponResolver {
    private MeleeWeaponResolver() {
    }

    public static ItemStack resolve(LivingEntity attacker) {
        ItemStack fallback = attacker.getMainHandItem();
        if (attacker instanceof Player player && ModList.get().isLoaded("bettercombat")) {
            return BetterCombatCompat.currentWeapon(player, fallback);
        }
        return fallback;
    }

    public static ItemStack preferCurrent(ItemStack fallback, ItemStack current) {
        return preferCurrent(fallback, current, item -> item == null || item.isEmpty());
    }

    static <T> T preferCurrent(T fallback, T current, Predicate<T> unusable) {
        return unusable.test(current) ? fallback : current;
    }
}
