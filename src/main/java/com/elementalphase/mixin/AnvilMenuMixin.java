package com.elementalphase.mixin;

import com.elementalphase.enchantment.ElementEnchantmentHooks;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin extends ItemCombinerMenu {
    protected AnvilMenuMixin(MenuType<?> menuType, int containerId, Inventory inventory,
                             ContainerLevelAccess access) {
        super(menuType, containerId, inventory, access);
    }

    @Inject(method = "createResult()V", at = @At("HEAD"), cancellable = true)
    private void elementalPhase$validateInputs(CallbackInfo callback) {
        AnvilMenu menu = (AnvilMenu) (Object) this;
        ItemStack left = menu.getSlot(AnvilMenu.INPUT_SLOT).getItem();
        ItemStack right = menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).getItem();
        if (ElementEnchantmentHooks.blocksAnvil(left, right, player.level())) {
            menu.getSlot(AnvilMenu.RESULT_SLOT).set(ItemStack.EMPTY);
            menu.setMaximumCost(0);
            menu.repairItemCountCost = 0;
            menu.broadcastChanges();
            callback.cancel();
        }
    }
}
