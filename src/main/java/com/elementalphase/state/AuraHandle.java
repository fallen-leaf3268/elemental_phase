package com.elementalphase.state;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

public interface AuraHandle {
    ResourceLocation element();
    double amount();
    long order();
    List<ElementPortion> consume(double amount);
}
