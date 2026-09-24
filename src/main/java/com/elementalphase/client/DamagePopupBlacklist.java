package com.elementalphase.client;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class DamagePopupBlacklist {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> WARNED_INVALID_ENTRIES = ConcurrentHashMap.newKeySet();

    private DamagePopupBlacklist() {
    }

    static boolean contains(ResourceLocation entityTypeId, List<? extends String> entries) {
        if (entityTypeId == null || entries == null) {
            return false;
        }
        for (String entry : entries) {
            ResourceLocation parsed = ResourceLocation.tryParse(entry);
            if (parsed == null) {
                if (WARNED_INVALID_ENTRIES.add(entry)) {
                    LOGGER.warn("Ignoring invalid damage popup blacklist entity id: {}", entry);
                }
                continue;
            }
            if (parsed.equals(entityTypeId)) {
                return true;
            }
        }
        return false;
    }
}
