package com.elementalphase.state;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record ElementSourceSnapshot(Optional<UUID> attacker, Optional<UUID> directEntity,
                                    ResourceLocation sourceId, ResourceLocation damageType,
                                    ResourceLocation element, double elementStrength, long createdAt,
                                    Optional<String> playerName) {
    public ElementSourceSnapshot {
        attacker = attacker == null ? Optional.empty() : attacker;
        directEntity = directEntity == null ? Optional.empty() : directEntity;
        playerName = attacker.isEmpty() || playerName == null ? Optional.empty()
                : playerName.filter(name -> !name.isBlank());
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(damageType, "damageType");
        Objects.requireNonNull(element, "element");
        if (!Double.isFinite(elementStrength) || elementStrength < 0.0D || elementStrength > 1_000_000.0D) {
            throw new IllegalArgumentException("Invalid element strength");
        }
    }

    public ElementSourceSnapshot(Optional<UUID> attacker, Optional<UUID> directEntity,
                                 ResourceLocation sourceId, ResourceLocation damageType,
                                 ResourceLocation element, double elementStrength, long createdAt) {
        this(attacker, directEntity, sourceId, damageType, element, elementStrength, createdAt, Optional.empty());
    }
}
