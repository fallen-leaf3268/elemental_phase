package com.elementalphase.data.model;

public record ElementAttachmentPolicy(boolean retainAfterAttack, int cooldownTicks, int durationTicks,
                                      double maxAmount) {
    public static final ElementAttachmentPolicy DEFAULT = new ElementAttachmentPolicy(true, 2, 100, 1_000_000.0D);
}
