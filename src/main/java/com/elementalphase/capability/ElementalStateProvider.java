package com.elementalphase.capability;

import com.elementalphase.state.ElementalState;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;

import javax.annotation.Nullable;

public final class ElementalStateProvider implements ICapabilitySerializable<CompoundTag> {
    private final ElementalState state = new ElementalState();
    private LazyOptional<ElementalState> access = LazyOptional.of(() -> state);

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
        return capability == ElementalCapabilities.STATE ? access.cast() : LazyOptional.empty();
    }

    public void invalidate() {
        access.invalidate();
        access = LazyOptional.of(() -> state);
    }

    @Override
    public CompoundTag serializeNBT() {
        return state.serializeNBT();
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        state.deserializeNBT(tag);
    }
}
