package com.elementalphase.display;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

@FunctionalInterface
public interface DamagePopupSink {
    void send(LivingEntity target, DamageSource source, double damage, int color,
              List<ResourceLocation> reactionIds);
}
