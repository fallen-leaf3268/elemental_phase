package com.elementalphase.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class ElementalPhaseClientConfig {
    static final double MIN_FONT_SCALE = 0.5D;
    static final double MAX_FONT_SCALE = 3.0D;
    static final double MIN_HEIGHT_RATIO = 0.0D;
    static final double MAX_HEIGHT_RATIO = 1.5D;
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue MAX_POPUPS;
    public static final ForgeConfigSpec.DoubleValue MAX_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue FONT_SCALE;
    public static final ForgeConfigSpec.DoubleValue HEIGHT_RATIO;
    public static final ForgeConfigSpec.BooleanValue SHOW_REACTIONS;
    public static final ForgeConfigSpec.BooleanValue SHOW_DAMAGE;
    public static final ForgeConfigSpec.BooleanValue DISABLE_VANILLA_DAMAGE_INDICATOR;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> DAMAGE_BLACKLIST;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> REACTION_BLACKLIST;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("伤害飘字设置。")
                .push("damage_popup");

        builder.comment("文字内容、大小和显示位置。")
                .push("display");
        SHOW_DAMAGE = builder.comment("是否显示本模组的伤害数字。默认：true。")
                .define("show_damage", true);
        SHOW_REACTIONS = builder.comment("是否显示本模组的元素反应名称。默认：true。")
                .define("show_reactions", true);
        DISABLE_VANILLA_DAMAGE_INDICATOR = builder.comment("是否禁用原版伤害心形粒子（minecraft:damage_indicator）。默认：true。")
                .define("disable_vanilla_damage_indicator", true);
        FONT_SCALE = builder.comment("伤害文字缩放。默认：1.0；有效范围：0.5–3.0。")
                .defineInRange("font_scale", 1.0D, MIN_FONT_SCALE, MAX_FONT_SCALE);
        HEIGHT_RATIO = builder.comment("文字在实体包围盒上的高度比例：0 为脚部，0.5 为中部，1 为头部。默认：0.6；有效范围：0.0–1.5。")
                .defineInRange("height_ratio", 0.6D, MIN_HEIGHT_RATIO, MAX_HEIGHT_RATIO);
        builder.pop();

        builder.comment("飘字数量和显示距离限制。")
                .push("performance");
        MAX_POPUPS = builder.comment("同时保留的最大飘字数量。默认：96；有效范围：16–256。")
                .defineInRange("max_count", 96, 16, 256);
        MAX_DISTANCE = builder.comment("飘字的最大显示距离（方块）。默认：64.0；有效范围：8.0–256.0。")
                .defineInRange("max_distance", 64.0D, 8.0D, 256.0D);
        builder.pop();

        builder.comment("按实体注册 ID 分别隐藏伤害数字或反应名称。")
                .push("blacklists");
        DAMAGE_BLACKLIST = builder.comment("仅隐藏本模组的伤害数字，不影响反应名称。实体 ID 格式示例：minecraft:zombie。")
                .defineListAllowEmpty("damage_entities",
                List.of("dummmmmmy:target_dummy"), value -> value instanceof String);
        REACTION_BLACKLIST = builder.comment("仅隐藏本模组的反应名称，不影响伤害数字或其他模组的显示。实体 ID 格式示例：minecraft:zombie。")
                .defineListAllowEmpty("reaction_entities",
                List.of("dummmmmmy:target_dummy"), value -> value instanceof String);
        builder.pop();
        builder.pop();
        SPEC = builder.build();
    }

    private ElementalPhaseClientConfig() {
    }
}
