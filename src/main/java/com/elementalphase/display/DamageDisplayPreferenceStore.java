package com.elementalphase.display;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class DamageDisplayPreferenceStore {
    public static final DamageDisplayPreferenceStore INSTANCE = new DamageDisplayPreferenceStore();
    private final ConcurrentMap<UUID, Boolean> reactionPreferences = new ConcurrentHashMap<>();

    public void update(UUID playerId, boolean showReactions) {
        if (playerId != null) {
            reactionPreferences.put(playerId, showReactions);
        }
    }

    public boolean showReactions(UUID playerId) {
        return playerId == null || reactionPreferences.getOrDefault(playerId, true);
    }

    public void remove(UUID playerId) {
        if (playerId != null) {
            reactionPreferences.remove(playerId);
        }
    }

    public void clear() {
        reactionPreferences.clear();
    }
}
