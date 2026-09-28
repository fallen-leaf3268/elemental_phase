package com.elementalphase.combat;

import com.elementalphase.data.ElementDataManager;
import com.elementalphase.data.ElementDataSnapshot;
import com.elementalphase.enchantment.ElementEnchantmentData;
import com.elementalphase.registry.ModAttributes;
import com.elementalphase.registry.ModEnchantments;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public final class AttackElementResolver {
    private final Set<String> warnedConflicts = new HashSet<>();
    private long conflictGeneration = Long.MIN_VALUE;

    public Optional<ElementAttackContext> resolve(DamageSource source, LivingEntity target, ElementDataSnapshot snapshot) {
        var damage = damageSelection(source, snapshot);
        if (damage.conflicted()) return Optional.empty();
        LivingEntity attacker = source.getEntity() instanceof LivingEntity living ? living : null;
        boolean directMelee = attacker != null && source.getDirectEntity() == attacker
                && source.typeHolder().unwrapKey().map(ResourceKey::location)
                .map(AttackElementResolver::isMeleeDamageType).orElse(false);
        double strength = strength(attacker);
        if (directMelee) {
            var enchantment = context(enchantmentCandidate(MeleeWeaponResolver.resolve(attacker), snapshot),
                    strength, snapshot, ElementAttackContext.SourceKind.ENCHANTMENT);
            if (enchantment.isPresent()) return enchantment;
            var intrinsic = context(intrinsicCandidate(attacker), strength, snapshot, ElementAttackContext.SourceKind.INTRINSIC);
            if (intrinsic.isPresent()) return intrinsic;
        }
        return context(damage.element().map(AttackElementResolver::damageCandidate), strength, snapshot,
                ElementAttackContext.SourceKind.DAMAGE_TYPE_TAG);
    }

    static boolean isMeleeDamageType(ResourceLocation type) {
        String path = type.getPath();
        return type.getNamespace().equals("minecraft") && (path.equals("player_attack")
                || path.equals("mob_attack") || path.equals("mob_attack_no_aggro"));
    }

    public ProjectileElementSnapshot captureProjectile(ItemStack weapon, LivingEntity attacker, ElementDataSnapshot snapshot) {
        return new ProjectileElementSnapshot(true, strength(attacker),
                enchantmentCandidate(weapon, snapshot), intrinsicCandidate(attacker));
    }

    public Optional<ElementAttackContext> resolveProjectile(DamageSource source, Projectile projectile,
                                                            ElementDataSnapshot snapshot) {
        var damage = damageSelection(source, snapshot);
        if (damage.conflicted()) return Optional.empty();
        ProjectileElementSnapshot captured = ProjectileElementSnapshot.readFrom(projectile.getPersistentData())
                .orElse(new ProjectileElementSnapshot(true, 1.0D, Optional.empty(), Optional.empty()));
        var enchantment = context(captured.enchantment(), captured.strength(), snapshot, ElementAttackContext.SourceKind.ENCHANTMENT);
        if (enchantment.isPresent()) return enchantment;
        var intrinsic = context(captured.intrinsic(), captured.strength(), snapshot, ElementAttackContext.SourceKind.INTRINSIC);
        if (intrinsic.isPresent()) return intrinsic;
        return context(damage.element().map(AttackElementResolver::damageCandidate), captured.strength(), snapshot,
                ElementAttackContext.SourceKind.DAMAGE_TYPE_TAG);
    }

    private ElementDamageTags.Selection damageSelection(DamageSource source, ElementDataSnapshot snapshot) {
        var result = ElementDamageTags.select(snapshot.elements().keySet(),
                tag -> source.is(TagKey.create(Registries.DAMAGE_TYPE, tag)));
        if (result.conflicted()) {
            long generation = ElementDataManager.generation();
            if (generation != conflictGeneration) {
                conflictGeneration = generation;
                warnedConflicts.clear();
            }
            String type = source.typeHolder().unwrapKey().map(key -> key.location().toString()).orElse(source.getMsgId());
            if (warnedConflicts.add(type)) {
                com.mojang.logging.LogUtils.getLogger().warn(
                        "Skipping elemental processing for damage type {}: conflicting element damage tags {}",
                        type, result.matches().stream().map(ElementDamageTags::tagId).toList());
            }
        }
        return result;
    }

    private static Optional<ProjectileElementSnapshot.Candidate> enchantmentCandidate(
            ItemStack item, ElementDataSnapshot snapshot) {
        return ElementEnchantmentData.activeElement(item, snapshot.elements().keySet())
                .map(element -> new ProjectileElementSnapshot.Candidate(element, 1.0D,
                        ModEnchantments.enchantmentId(element), Optional.of(ModEnchantments.enchantmentId(element))));
    }

    private static Optional<ProjectileElementSnapshot.Candidate> intrinsicCandidate(LivingEntity attacker) {
        return com.elementalphase.capability.ElementalCapabilities.get(attacker).resolve()
                .flatMap(state -> state.intrinsicAttack().map(attack -> new ProjectileElementSnapshot.Candidate(
                        attack.element(), attack.baseAmount(), attack.element(), Optional.empty())));
    }

    private static ProjectileElementSnapshot.Candidate damageCandidate(ResourceLocation element) {
        return new ProjectileElementSnapshot.Candidate(element, 1.0D, ElementDamageTags.tagId(element), Optional.empty());
    }

    static Optional<ElementAttackContext> context(Optional<ProjectileElementSnapshot.Candidate> candidate,
                                                 double strength, ElementDataSnapshot snapshot,
                                                 ElementAttackContext.SourceKind kind) {
        return candidate.filter(value -> snapshot.elements().containsKey(value.element()))
                .filter(value -> Double.isFinite(strength) && strength >= 0.0D && strength <= 1_000_000.0D)
                .flatMap(value -> {
                    double amount = Math.min(snapshot.elements().get(value.element()).attachment().maxAmount(),
                            value.baseAmount() * strength);
                    return amount < 0.000001D ? Optional.empty() : Optional.of(new ElementAttackContext(
                            value.element(), amount, kind, value.sourceId(), strength));
                });
    }

    private static double strength(LivingEntity attacker) {
        double value = attacker == null ? 1.0D : attacker.getAttributeValue(ModAttributes.ELEMENT_STRENGTH.get());
        return Double.isFinite(value) && value >= 0.0D ? Math.min(1_000_000.0D, value) : 0.0D;
    }
}
