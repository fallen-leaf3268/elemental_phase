package com.elementalphase.mixin;

import com.elementalphase.event.CommonEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CrossbowItem.class)
public abstract class CrossbowItemMixin {
    @Inject(method = "performShooting(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/ItemStack;FF)V",
            at = @At("HEAD"))
    private static void elementalPhase$captureWeapon(Level level, LivingEntity shooter, InteractionHand hand,
                                                   ItemStack weapon, float velocity, float inaccuracy,
                                                   CallbackInfo callback) {
        if (!level.isClientSide) {
            CommonEvents.captureProjectileWeapon(shooter, weapon);
        }
    }
}
