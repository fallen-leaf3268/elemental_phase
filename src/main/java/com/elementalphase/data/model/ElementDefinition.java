package com.elementalphase.data.model;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record ElementDefinition(ResourceLocation id, ElementAttachmentPolicy attachment, ElementDisplayDefinition display) {
    public ElementDefinition {
        Objects.requireNonNull(id, "id");
        attachment = Objects.requireNonNull(attachment, "attachment");
        display = Objects.requireNonNull(display, "display");
    }
}
