package com.elementalphase.integration.kubejs;

import com.elementalphase.data.ElementDataManager;
import com.mojang.logging.LogUtils;
import dev.latvian.mods.kubejs.KubeJSPlugin;
import org.slf4j.Logger;

public final class ElementalPhaseKubeJsPlugin extends KubeJSPlugin {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void registerEvents() {
        ElementalPhaseKubeEvents.GROUP.register();
    }

    @Override
    public void onServerReload() {
        KubeJsHooks.install(base -> {
            RegisterReactionsEventJS event = new RegisterReactionsEventJS(base);
            if (ElementalPhaseKubeEvents.REGISTER_REACTIONS.hasListeners()) {
                ElementalPhaseKubeEvents.REGISTER_REACTIONS.post(event);
            }
            event.errors().forEach(error -> LOGGER.error("KubeJS reaction skipped: {}", error));
            return event.overlay();
        });
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            ElementDataManager.rebuildOverlay(server.registryAccess()).errors()
                    .forEach(error -> LOGGER.error("KubeJS reaction skipped: {}", error.message()));
        }
    }
}
