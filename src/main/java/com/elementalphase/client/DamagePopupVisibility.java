package com.elementalphase.client;

public final class DamagePopupVisibility {
    private DamagePopupVisibility() {
    }

    public static boolean shouldRender(int targetId, int localPlayerId, boolean firstPerson) {
        return !firstPerson || targetId != localPlayerId;
    }

    public static boolean shouldRenderAlpha(int alpha) {
        return alpha >= 4;
    }
}
