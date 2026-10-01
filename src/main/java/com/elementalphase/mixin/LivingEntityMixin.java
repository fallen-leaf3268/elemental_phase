package com.elementalphase.mixin;

import com.elementalphase.event.CommonEvents;
import com.elementalphase.reaction.runtime.ReactionRuntimeController;
import com.elementalphase.client.FrozenReactionRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
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

@Mixin(Entity.class)
abstract class EntityMovementMixin {
    @ModifyVariable(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Vec3 elementalPhase$constrainFrozenMovement(Vec3 movement) {
        return ReactionRuntimeController.constrainMovement((Entity) (Object) this, movement);
    }
}

@Mixin(EntityRenderDispatcher.class)
abstract class FrozenRenderOffsetMixin {
    @Inject(method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"))
    private void elementalPhase$beginRender(Entity entity, double x, double y, double z, float yaw, float partialTick,
                                            PoseStack poseStack, MultiBufferSource buffers, int light, CallbackInfo callback) {
        FrozenReactionRenderer.beginRender(entity);
    }

    @ModifyVariable(method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("STORE"), ordinal = 0)
    private Vec3 elementalPhase$captureRenderOffset(Vec3 offset, Entity entity, double x, double y, double z,
                                                    float yaw, float partialTick, PoseStack poseStack,
                                                    MultiBufferSource buffers, int light) {
        return FrozenReactionRenderer.captureRenderOffset(offset, entity);
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"))
    private void elementalPhase$endRender(Entity entity, double x, double y, double z, float yaw, float partialTick,
                                          PoseStack poseStack, MultiBufferSource buffers, int light, CallbackInfo callback) {
        FrozenReactionRenderer.endRender(entity);
    }
}
