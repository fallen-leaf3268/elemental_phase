package com.elementalphase.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.core.particles.SimpleParticleType;
import org.jetbrains.annotations.Nullable;

import java.util.function.BooleanSupplier;

final class DamageIndicatorParticleProvider implements ParticleProvider<SimpleParticleType> {
    private final ParticleProvider<SimpleParticleType> vanillaProvider;
    private final BooleanSupplier disabled;

    DamageIndicatorParticleProvider(ParticleProvider<SimpleParticleType> vanillaProvider,
                                    BooleanSupplier disabled) {
        this.vanillaProvider = vanillaProvider;
        this.disabled = disabled;
    }

    @Nullable
    @Override
    public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                   double x, double y, double z,
                                   double xSpeed, double ySpeed, double zSpeed) {
        if (disabled.getAsBoolean()) {
            return null;
        }
        return vanillaProvider.createParticle(type, level, x, y, z, xSpeed, ySpeed, zSpeed);
    }
}
