package com.elementalphase.capability;

import com.elementalphase.state.ElementalState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.common.util.LazyOptional;

public final class ElementalCapabilities {
    public static final Capability<ElementalState> STATE = CapabilityManager.get(new CapabilityToken<>() {
    });

    private ElementalCapabilities() {
    }

    public static void register(RegisterCapabilitiesEvent event) {
        event.register(ElementalState.class);
    }

    public static LazyOptional<ElementalState> get(LivingEntity entity) {
        return entity.getCapability(STATE);
    }
}
