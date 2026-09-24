package com.elementalphase.integration.bettercombat;

import com.elementalphase.combat.MeleeWeaponResolver;
import net.bettercombat.api.AttackHand;
import net.bettercombat.api.EntityPlayer_BetterCombat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class BetterCombatCompat {
    private BetterCombatCompat() {
    }

    public static ItemStack currentWeapon(Player player, ItemStack fallback) {
        if (player instanceof EntityPlayer_BetterCombat betterCombat) {
            AttackHand current = betterCombat.getCurrentAttack();
            return MeleeWeaponResolver.preferCurrent(fallback,
                    current == null ? null : current.itemStack());
        }
        return fallback;
    }
}
