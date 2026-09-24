package com.elementalphase.integration.kubejs;

import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventHandler;

public final class ElementalPhaseKubeEvents {
    public static final EventGroup GROUP = EventGroup.of("ElementalPhaseEvents");
    public static final EventHandler REGISTER_REACTIONS = GROUP.server("registerReactions", () -> RegisterReactionsEventJS.class);

    private ElementalPhaseKubeEvents() {
    }
}
