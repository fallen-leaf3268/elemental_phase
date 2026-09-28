package com.elementalphase.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class ElementalPhaseServerConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.DoubleValue MAX_RADIUS;
    public static final ForgeConfigSpec.IntValue MAX_TARGETS;

    static {
        var builder = new ForgeConfigSpec.Builder();
        builder.push("reactions");
        MAX_RADIUS = builder.comment("范围半径上限，单位：格；默认32。数值或公式结果超限时截断。提高上限会扩大扫描范围并增加服务端计算开销。")
                .defineInRange("max_radius", 32.0D, Double.MIN_VALUE, Double.MAX_VALUE);
        MAX_TARGETS = builder.comment("每次范围组件的总目标上限，默认64。省略组件上限时跟随本值，超过本值时截断。攻击者也占名额；提高上限会增加执行开销。")
                .defineInRange("max_targets", 64, 1, Integer.MAX_VALUE);
        builder.pop();
        SPEC = builder.build();
    }

    private ElementalPhaseServerConfig() {}
}
