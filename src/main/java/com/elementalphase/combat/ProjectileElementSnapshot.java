package com.elementalphase.combat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public record ProjectileElementSnapshot(boolean captured, double strength, Optional<Candidate> enchantment,
                                        Optional<Candidate> intrinsic) {
    public static final String DATA_KEY = "elemental_phase:attack";
    private static final int VERSION = 3;

    public ProjectileElementSnapshot {
        enchantment = enchantment == null ? Optional.empty() : enchantment;
        intrinsic = intrinsic == null ? Optional.empty() : intrinsic;
        if (!Double.isFinite(strength) || strength < 0.0D || strength > 1_000_000.0D) {
            throw new IllegalArgumentException("Invalid projectile strength");
        }
    }

    public void write(CompoundTag tag) {
        tag.putInt("version", VERSION);
        tag.putBoolean("captured", captured);
        tag.putDouble("strength", strength);
        enchantment.ifPresent(candidate -> tag.put("enchantment", candidate.write()));
        intrinsic.ifPresent(candidate -> tag.put("intrinsic", candidate.write()));
    }

    public void writeTo(CompoundTag persistentData) {
        CompoundTag value = new CompoundTag();
        write(value);
        persistentData.put(DATA_KEY, value);
    }

    public static Optional<ProjectileElementSnapshot> readFrom(CompoundTag persistentData) {
        return persistentData.contains(DATA_KEY) ? read(persistentData.getCompound(DATA_KEY)) : Optional.empty();
    }

    public static Optional<ProjectileElementSnapshot> read(CompoundTag tag) {
        if (tag.getInt("version") != VERSION || !tag.getBoolean("captured")) {
            return Optional.empty();
        }
        double strength = tag.getDouble("strength");
        if (!Double.isFinite(strength) || strength < 0.0D || strength > 1_000_000.0D) {
            return Optional.empty();
        }
        Optional<Candidate> enchantment = tag.contains("enchantment") ? Candidate.read(tag.getCompound("enchantment")) : Optional.empty();
        Optional<Candidate> intrinsic = tag.contains("intrinsic") ? Candidate.read(tag.getCompound("intrinsic")) : Optional.empty();
        if ((tag.contains("enchantment") && enchantment.isEmpty()) || (tag.contains("intrinsic") && intrinsic.isEmpty())) {
            return Optional.empty();
        }
        return Optional.of(new ProjectileElementSnapshot(true, strength, enchantment, intrinsic));
    }

    public record Candidate(ResourceLocation element, double baseAmount, ResourceLocation sourceId,
                            Optional<ResourceLocation> enchantmentId) {
        public Candidate {
            enchantmentId = enchantmentId == null ? Optional.empty() : enchantmentId;
            if (element == null || sourceId == null || !Double.isFinite(baseAmount) || baseAmount <= 0.0D
                    || baseAmount > 1_000_000.0D) {
                throw new IllegalArgumentException("Invalid projectile candidate");
            }
        }

        private CompoundTag write() {
            CompoundTag tag = new CompoundTag();
            tag.putString("element", element.toString());
            tag.putDouble("amount", baseAmount);
            tag.putString("source_id", sourceId.toString());
            enchantmentId.ifPresent(id -> tag.putString("enchantment_id", id.toString()));
            return tag;
        }

        private static Optional<Candidate> read(CompoundTag tag) {
            ResourceLocation element = ResourceLocation.tryParse(tag.getString("element"));
            ResourceLocation source = ResourceLocation.tryParse(tag.getString("source_id"));
            ResourceLocation enchantment = tag.contains("enchantment_id")
                    ? ResourceLocation.tryParse(tag.getString("enchantment_id")) : null;
            double amount = tag.getDouble("amount");
            if (element == null || source == null || (tag.contains("enchantment_id") && enchantment == null)
                    || !Double.isFinite(amount) || amount <= 0.0D || amount > 1_000_000.0D) {
                return Optional.empty();
            }
            return Optional.of(new Candidate(element, amount, source, Optional.ofNullable(enchantment)));
        }
    }
}
