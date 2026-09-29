package com.elementalphase.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class ElementalPhaseServerConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ENCHANTMENT_ENHANCEMENT_ENABLED;
    public static final ForgeConfigSpec.DoubleValue ENCHANTMENT_BASE_ATTACHMENT_AMOUNT;
    public static final ForgeConfigSpec.DoubleValue MAX_RADIUS;
    public static final ForgeConfigSpec.IntValue MAX_TARGETS;

    static {
        var builder = new ForgeConfigSpec.Builder();
        builder.push("enchantments");
        ENCHANTMENT_ENHANCEMENT_ENABLED = builder.comment("是否启用元素附魔强化。默认false；开启后附魔武器的原伤害按对应元素抗性结算，关闭时仅附着元素并尝试反应。")
                .define("enable_enhancement", false);
        ENCHANTMENT_BASE_ATTACHMENT_AMOUNT = builder.comment("元素附魔的基础附着量，默认1，范围0.000001..1000000，支持小数。实际量还会乘攻击者元素强度，并受元素自身上限约束。")
                .defineInRange("base_attachment_amount", 1.0D, 0.000001D, 1_000_000.0D);
        builder.pop();
        builder.push("reactions");
        MAX_RADIUS = builder.comment("范围半径上限，单位：格；默认32。数值或公式结果超限时截断。提高上限会扩大扫描范围并增加服务端计算开销。")
                .defineInRange("max_radius", 32.0D, Double.MIN_VALUE, Double.MAX_VALUE);
        MAX_TARGETS = builder.comment("每次范围组件的总目标上限，默认64。省略组件上限时跟随本值，超过本值时截断。攻击者也占名额；提高上限会增加执行开销。")
                .defineInRange("max_targets", 64, 1, Integer.MAX_VALUE);
        builder.pop();
        SPEC = builder.build();
    }

    private ElementalPhaseServerConfig() {}

    public static boolean enchantmentEnhancementEnabled() {
        return SPEC.isLoaded() ? ENCHANTMENT_ENHANCEMENT_ENABLED.get()
                : ENCHANTMENT_ENHANCEMENT_ENABLED.getDefault();
    }

    public static double enchantmentBaseAmount() {
        return SPEC.isLoaded() ? ENCHANTMENT_BASE_ATTACHMENT_AMOUNT.get()
                : ENCHANTMENT_BASE_ATTACHMENT_AMOUNT.getDefault();
    }
}
