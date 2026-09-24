package com.elementalphase.combat;

import com.elementalphase.data.ElementDataSnapshot;
import com.elementalphase.data.model.AttackSourceDefinition;
import com.elementalphase.registry.ModAttributes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class AttackElementResolver {
    public Optional<ElementAttackContext> resolve(DamageSource source, LivingEntity target, ElementDataSnapshot snapshot) {
        LivingEntity attacker = source.getEntity() instanceof LivingEntity living ? living : null;
        boolean directMelee = attacker != null && source.getDirectEntity() == attacker
                && source.typeHolder().unwrapKey().map(ResourceKey::location)
                .map(AttackElementResolver::isMeleeDamageType).orElse(false);
        List<Candidate> enchantments = directMelee
                ? enchantmentCandidates(MeleeWeaponResolver.resolve(attacker), snapshot)
                : List.of();
        List<Candidate> intrinsic = directMelee ? intrinsicCandidates(attacker) : List.of();
        List<Candidate> damageId = damageTypeCandidates(source, snapshot, AttackSourceDefinition.SourceKind.DAMAGE_TYPE_ID);
        List<Candidate> damageTag = damageTypeCandidates(source, snapshot, AttackSourceDefinition.SourceKind.DAMAGE_TYPE_TAG);
        Candidate selected = choose(enchantments, intrinsic, damageId, damageTag);
        if (selected == null) {
            return Optional.empty();
        }
        double strength = attacker == null ? 1.0D : attacker.getAttributeValue(ModAttributes.ELEMENT_STRENGTH.get());
        if (!Double.isFinite(strength) || strength < 0.0D) {
            strength = 0.0D;
        }
        double amount = Math.min(1_000_000.0D, selected.baseAmount() * strength);
        return amount < 0.000001D ? Optional.empty() : Optional.of(new ElementAttackContext(selected.element(), amount,
                selected.sourceKind(), selected.mappingId(), selected.application(), strength));
    }

    public Candidate choose(List<Candidate> enchantments, List<Candidate> intrinsic,
                            List<Candidate> damageTypeIds, List<Candidate> damageTypeTags) {
        Candidate candidate = best(enchantments, true);
        if (candidate != null) {
            return candidate;
        }
        candidate = best(intrinsic, false);
        if (candidate != null) {
            return candidate;
        }
        candidate = best(damageTypeIds, false);
        return candidate != null ? candidate : best(damageTypeTags, false);
    }

    static boolean isMeleeDamageType(ResourceLocation type) {
        String path = type.getPath();
        return type.getNamespace().equals("minecraft") && (path.equals("player_attack")
                || path.equals("mob_attack") || path.equals("mob_attack_no_aggro"));
    }

    public ProjectileElementSnapshot captureProjectile(ItemStack weapon, LivingEntity attacker, ElementDataSnapshot snapshot) {
        Candidate enchantment = best(enchantmentCandidates(weapon, snapshot), true);
        Candidate intrinsic = best(intrinsicCandidates(attacker), false);
        double strength = attacker.getAttributeValue(ModAttributes.ELEMENT_STRENGTH.get());
        if (!Double.isFinite(strength) || strength < 0.0D) {
            strength = 0.0D;
        }
        return new ProjectileElementSnapshot(true, strength, toSnapshotCandidate(enchantment), toSnapshotCandidate(intrinsic));
    }

    public Optional<ElementAttackContext> resolveProjectile(DamageSource source, Projectile projectile,
                                                            ElementDataSnapshot snapshot) {
        ProjectileElementSnapshot captured = ProjectileElementSnapshot.readFrom(projectile.getPersistentData())
                .orElse(new ProjectileElementSnapshot(true, 1.0D, Optional.empty(), Optional.empty()));
        Optional<ElementAttackContext> enchantment = contextFromSnapshot(captured.enchantment(), captured.strength(), snapshot,
                ElementAttackContext.SourceKind.ENCHANTMENT);
        if (enchantment.isPresent()) {
            return enchantment;
        }
        Optional<ElementAttackContext> intrinsic = contextFromSnapshot(captured.intrinsic(), captured.strength(), snapshot,
                ElementAttackContext.SourceKind.INTRINSIC);
        if (intrinsic.isPresent()) {
            return intrinsic;
        }
        Candidate damage = choose(List.of(), List.of(),
                damageTypeCandidates(source, snapshot, AttackSourceDefinition.SourceKind.DAMAGE_TYPE_ID),
                damageTypeCandidates(source, snapshot, AttackSourceDefinition.SourceKind.DAMAGE_TYPE_TAG));
        if (damage == null) return Optional.empty();
        double amount = Math.min(1_000_000.0D, damage.baseAmount() * captured.strength());
        return amount < 0.000001D ? Optional.empty() : Optional.of(new ElementAttackContext(damage.element(),
                amount, damage.sourceKind(), damage.mappingId(), damage.application(), captured.strength()));
    }

    private static Optional<ProjectileElementSnapshot.Candidate> toSnapshotCandidate(Candidate candidate) {
        return candidate == null ? Optional.empty() : Optional.of(new ProjectileElementSnapshot.Candidate(candidate.element(),
                candidate.baseAmount(), candidate.mappingId(), candidate.enchantmentId(), candidate.application()));
    }

    private static Optional<ElementAttackContext> contextFromSnapshot(Optional<ProjectileElementSnapshot.Candidate> candidate,
                                                                        double strength, ElementDataSnapshot snapshot,
                                                                        ElementAttackContext.SourceKind kind) {
        return candidate.filter(value -> snapshot.elements().containsKey(value.element()))
                .filter(value -> value.baseAmount() * strength >= 0.000001D)
                .map(value -> new ElementAttackContext(value.element(), Math.min(1_000_000.0D,
                        value.baseAmount() * strength), kind, value.sourceId(), value.application(), strength));
    }

    private static List<Candidate> enchantmentCandidates(ItemStack item, ElementDataSnapshot snapshot) {
        List<Candidate> candidates = new ArrayList<>();
        for (var entry : item.getAllEnchantments().entrySet()) {
            Enchantment enchantment = entry.getKey();
            ResourceLocation enchantmentId = BuiltInRegistries.ENCHANTMENT.getKey(enchantment);
            if (enchantmentId == null) {
                continue;
            }
            for (AttackSourceDefinition definition : snapshot.attackSources()) {
                if (definition.kind() == AttackSourceDefinition.SourceKind.ENCHANTMENT_ID
                        && definition.selector().equals(enchantmentId.toString())
                        || definition.kind() == AttackSourceDefinition.SourceKind.ENCHANTMENT_TAG
                        && inEnchantmentTag(enchantmentId, ResourceLocation.parse(definition.selector()))) {
                    candidates.add(new Candidate(definition.id(), Optional.of(enchantmentId), definition.element(),
                            definition.baseAmount(), definition.priority(), ElementAttackContext.SourceKind.ENCHANTMENT,
                            definition.application()));
                }
            }
        }
        return candidates;
    }

    private static boolean inEnchantmentTag(ResourceLocation enchantmentId, ResourceLocation tagId) {
        return BuiltInRegistries.ENCHANTMENT.getHolder(ResourceKey.create(Registries.ENCHANTMENT, enchantmentId))
                .map(holder -> holder.is(TagKey.create(Registries.ENCHANTMENT, tagId)))
                .orElse(false);
    }

    private static List<Candidate> intrinsicCandidates(LivingEntity attacker) {
        return com.elementalphase.capability.ElementalCapabilities.get(attacker)
                .resolve()
                .flatMap(state -> state.intrinsicAttack().map(attack -> new Candidate(
                        attack.element(), Optional.empty(), attack.element(), attack.baseAmount(), 0,
                        ElementAttackContext.SourceKind.INTRINSIC, Optional.empty())))
                .map(List::of)
                .orElseGet(List::of);
    }

    private static List<Candidate> damageTypeCandidates(DamageSource source, ElementDataSnapshot snapshot,
                                                         AttackSourceDefinition.SourceKind expectedKind) {
        List<Candidate> candidates = new ArrayList<>();
        Optional<ResourceKey<DamageType>> typeKey = source.typeHolder().unwrapKey();
        for (AttackSourceDefinition definition : snapshot.attackSources()) {
            if (definition.kind() != expectedKind) {
                continue;
            }
            ResourceLocation selector = ResourceLocation.parse(definition.selector());
            boolean matches = expectedKind == AttackSourceDefinition.SourceKind.DAMAGE_TYPE_ID
                    ? typeKey.map(ResourceKey::location).filter(selector::equals).isPresent()
                    : source.typeHolder().is(TagKey.create(Registries.DAMAGE_TYPE, selector));
            if (matches) {
                candidates.add(new Candidate(definition.id(), Optional.empty(), definition.element(), definition.baseAmount(),
                        definition.priority(), expectedKind == AttackSourceDefinition.SourceKind.DAMAGE_TYPE_ID
                        ? ElementAttackContext.SourceKind.DAMAGE_TYPE_ID : ElementAttackContext.SourceKind.DAMAGE_TYPE_TAG,
                        definition.application()));
            }
        }
        return candidates;
    }

    private static Candidate best(List<Candidate> candidates, boolean enchantment) {
        Comparator<Candidate> comparator = Comparator.comparingInt(Candidate::priority).reversed()
                .thenComparing(candidate -> enchantment
                        ? candidate.enchantmentId().map(ResourceLocation::toString).orElse("")
                        : "")
                .thenComparing(candidate -> candidate.mappingId().toString());
        return candidates.stream().min(comparator).orElse(null);
    }

    public record Candidate(ResourceLocation mappingId, Optional<ResourceLocation> enchantmentId, ResourceLocation element,
                            double baseAmount, int priority, ElementAttackContext.SourceKind sourceKind,
                            Optional<AttackSourceDefinition.Application> application) {
        public Candidate {
            enchantmentId = enchantmentId == null ? Optional.empty() : enchantmentId;
            application = application == null ? Optional.empty() : application;
        }

        public Candidate(ResourceLocation mappingId, Optional<ResourceLocation> enchantmentId, ResourceLocation element,
                         double baseAmount, int priority, ElementAttackContext.SourceKind sourceKind) {
            this(mappingId, enchantmentId, element, baseAmount, priority, sourceKind, Optional.empty());
        }
    }
}
