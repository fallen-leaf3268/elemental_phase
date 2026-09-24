package com.elementalphase.combat;

import com.elementalphase.data.ElementDataSnapshot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ProjectileSnapshotManager {
    private final Map<UUID, PendingSnapshot> pending = new HashMap<>();

    public void capture(LivingEntity shooter, ItemStack weapon, long gameTime, AttackElementResolver resolver,
                        ElementDataSnapshot snapshot) {
        pending.put(shooter.getUUID(), new PendingSnapshot(gameTime, resolver.captureProjectile(weapon, shooter, snapshot)));
    }

    public void apply(Projectile projectile, long gameTime, boolean loadedFromDisk, AttackElementResolver resolver,
                      ElementDataSnapshot snapshot) {
        if (ProjectileElementSnapshot.readFrom(projectile.getPersistentData()).isPresent()) {
            return;
        }
        if (loadedFromDisk) {
            emptySnapshot().writeTo(projectile.getPersistentData());
            return;
        }
        if (projectile.getOwner() instanceof LivingEntity owner) {
            PendingSnapshot value = pending.get(owner.getUUID());
            boolean supported = projectile instanceof FireworkRocketEntity
                    || projectile instanceof AbstractArrow && !(projectile instanceof ThrownTrident);
            if (value != null && shouldUsePending(false, supported, value.gameTime(), gameTime)) {
                value.snapshot().writeTo(projectile.getPersistentData());
                return;
            }
            ItemStack weapon = projectile instanceof ThrownTrident trident ? tridentItem(trident)
                    : fallbackWeapon(owner);
            resolver.captureProjectile(weapon, owner, snapshot).writeTo(projectile.getPersistentData());
            return;
        }
        emptySnapshot().writeTo(projectile.getPersistentData());
    }

    public void clearExpired(long gameTime) {
        pending.entrySet().removeIf(entry -> entry.getValue().gameTime() < gameTime);
    }

    public void clear() {
        pending.clear();
    }

    static boolean shouldUsePending(boolean loadedFromDisk, boolean supportedProjectile,
                                    long capturedAt, long gameTime) {
        return !loadedFromDisk && supportedProjectile && capturedAt == gameTime;
    }

    private static ItemStack tridentItem(ThrownTrident trident) {
        CompoundTag tag = new CompoundTag();
        trident.addAdditionalSaveData(tag);
        return tag.contains("Trident", CompoundTag.TAG_COMPOUND)
                ? ItemStack.of(tag.getCompound("Trident")) : ItemStack.EMPTY;
    }

    private static ItemStack fallbackWeapon(LivingEntity owner) {
        if (owner instanceof Player) {
            return ItemStack.EMPTY;
        }
        ItemStack useItem = owner.getUseItem();
        if (isRangedWeapon(useItem)) {
            return useItem.copy();
        }
        ItemStack mainHand = owner.getMainHandItem();
        return isRangedWeapon(mainHand) ? mainHand.copy() : ItemStack.EMPTY;
    }

    private static boolean isRangedWeapon(ItemStack stack) {
        return stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem;
    }

    private static ProjectileElementSnapshot emptySnapshot() {
        return new ProjectileElementSnapshot(true, 1.0D, java.util.Optional.empty(), java.util.Optional.empty());
    }

    private record PendingSnapshot(long gameTime, ProjectileElementSnapshot snapshot) {
    }
}
