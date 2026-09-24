package com.elementalphase.capability;

import com.elementalphase.state.ElementalState;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;

import javax.annotation.Nullable;

public final class ElementalStateProvider implements ICapabilitySerializable<CompoundTag> {
    private final LazyOptional<ElementalState> state = LazyOptional.of(ElementalState::new);

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
        return capability == ElementalCapabilities.STATE ? state.cast() : LazyOptional.empty();
    }

    public void invalidate() {
        state.invalidate();
    }

    @Override
    public CompoundTag serializeNBT() {
        return state.resolve().map(ElementalState::serializeNBT).orElseGet(CompoundTag::new);
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        state.resolve().ifPresent(value -> value.deserializeNBT(tag));
    }
}
