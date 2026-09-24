package com.elementalphase.data;

import com.elementalphase.integration.kubejs.KubeJsHooks;
import java.util.Objects;
import java.util.WeakHashMap;

import net.minecraft.server.ReloadableServerResources;
import net.minecraft.core.RegistryAccess;

public final class ElementDataManager {
    private static volatile ElementDataSnapshot snapshot = ElementDataSnapshot.empty();
    private static volatile ElementDataSnapshot baseSnapshot = ElementDataSnapshot.empty();
    private static volatile long generation;
    private static final WeakHashMap<ReloadableServerResources, ElementDataParser.ParseReport> STAGED = new WeakHashMap<>();

    private ElementDataManager() {
    }

    public static ElementDataSnapshot snapshot() {
        return snapshot;
    }

    public static long generation() {
        return generation;
    }

    public static synchronized ElementDataParser.ParseReport replace(ElementDataSnapshot next, RegistryAccess registries) {
        baseSnapshot = Objects.requireNonNull(next, "next");
        ElementDataParser.ParseReport report = ElementDataRuntimeValidator.validate(
                new ElementDataParser.ParseReport(KubeJsHooks.apply(baseSnapshot), java.util.List.of()), registries);
        snapshot = report.snapshot();
        generation++;
        return report;
    }

    public static synchronized ElementDataParser.ParseReport rebuildOverlay(RegistryAccess registries) {
        ElementDataParser.ParseReport report = ElementDataRuntimeValidator.validate(
                new ElementDataParser.ParseReport(KubeJsHooks.apply(baseSnapshot), java.util.List.of()), registries);
        snapshot = report.snapshot();
        generation++;
        return report;
    }

    public static ElementDataSnapshot baseSnapshot() {
        return baseSnapshot;
    }

    public static synchronized void stage(ReloadableServerResources resources, ElementDataParser.ParseReport report) {
        STAGED.put(Objects.requireNonNull(resources, "resources"), Objects.requireNonNull(report, "report"));
    }

    public static synchronized ElementDataParser.ParseReport takeStaged(ReloadableServerResources resources) {
        return STAGED.remove(resources);
    }

    public static synchronized void clearServerState() {
        snapshot = ElementDataSnapshot.empty();
        baseSnapshot = ElementDataSnapshot.empty();
        STAGED.clear();
        generation++;
    }
}
