package com.elementalphase.mixin;

import com.elementalphase.event.CommonEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Unique
    private CommonEvents.PreparedElementalHit elementalPhase$pendingHit;

    @ModifyVariable(method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;invulnerableTime:I",
                    opcode = Opcodes.GETFIELD, ordinal = 0), argsOnly = true, ordinal = 0)
    private float elementalPhase$prepareHit(float amount, DamageSource source, float originalAmount) {
        var hit = CommonEvents.prepareElementalHit((LivingEntity) (Object) this, source, amount);
        elementalPhase$pendingHit = hit;
        return hit.amount();
    }

    @Inject(method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;actuallyHurt(Lnet/minecraft/world/damagesource/DamageSource;F)V"),
            require = 2)
    private void elementalPhase$recordMainDamage(DamageSource source, float amount,
                                                CallbackInfoReturnable<Boolean> callback) {
        var hit = elementalPhase$pendingHit;
        elementalPhase$pendingHit = null;
        CommonEvents.recordElementalHitDamage((LivingEntity) (Object) this, source, hit);
    }

    @Inject(method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z", at = @At("RETURN"))
    private void elementalPhase$clearPreparedHit(DamageSource source, float amount,
                                                CallbackInfoReturnable<Boolean> callback) {
        elementalPhase$pendingHit = null;
    }
}
