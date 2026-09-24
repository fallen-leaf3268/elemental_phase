package com.elementalphase.state;

import java.util.Optional;

public record ElementPortion(Origin origin, double amount,
                             Optional<ElementSourceSnapshot> temporarySource,
                             Optional<ElementSourceSnapshot> permanentSource,
                             long originalTemporaryExpiresAt) {
    public ElementPortion {
        if (origin == null || !Double.isFinite(amount) || amount <= 0.0D || amount > 1_000_000.0D) {
            throw new IllegalArgumentException("Invalid element portion");
        }
        temporarySource = temporarySource == null ? Optional.empty() : temporarySource;
        permanentSource = permanentSource == null ? Optional.empty() : permanentSource;
    }

    public enum Origin {
        TEMPORARY_ONLY,
        OVERLAP,
        PERMANENT_ONLY,
        TRIGGER_POOL
    }
}
