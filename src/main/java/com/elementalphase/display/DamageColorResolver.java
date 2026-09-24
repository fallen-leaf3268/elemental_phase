package com.elementalphase.display;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;

import java.util.Set;
import java.util.function.Predicate;

public final class DamageColorResolver {
    private static final int WHITE = 0xFFFFFF;
    private static final int TRIDENT = 0x00FF9D;
    private static final int SONIC_BOOM = 0x074550;
    private static final int FIRE = 0xFF7700;
    private static final int DROWNING = 0x1898E3;
    private static final int EXPLOSION = 0xFFBB29;
    private static final int MAGIC = 0x844CE7;
    private static final int DRAGON_BREATH = 0xE600FF;
    private static final int FREEZING = 0x09D2FF;
    private static final int LIGHTNING = 0xFFF200;
    private static final int THORNS = 0x0FA209;
    private static final int WITHER = 0x666666;
    private static final int WIND = 0xBEF3FF;
    private static final int BLEEDING = 0x810A0A;

    private static final ResourceLocation IS_FIRE = minecraft("is_fire");
    private static final ResourceLocation IS_EXPLOSION = minecraft("is_explosion");
    private static final ResourceLocation IS_FREEZING = minecraft("is_freezing");
    private static final ResourceLocation IS_LIGHTNING = minecraft("is_lightning");
    private static final ResourceLocation IS_DROWNING = minecraft("is_drowning");
    private static final ResourceLocation WITCH_RESISTANT_TO = minecraft("witch_resistant_to");

    private static final Set<String> FIRE_IDS = Set.of("in_fire", "on_fire", "lava", "hot_floor", "fireball",
            "unattributed_fireball");
    private static final Set<String> EXPLOSION_IDS = Set.of("explosion", "player_explosion", "bad_respawn_point");
    private static final Set<String> MAGIC_IDS = Set.of("magic", "indirect_magic");
    private static final Set<String> THORNS_IDS = Set.of("thorns", "cactus", "sweet_berry_bush", "sting");
    private static final Set<String> WITHER_IDS = Set.of("wither", "wither_skull");
    private static final Set<String> WIND_IDS = Set.of("wind", "wind_charge", "wind_burst");

    private DamageColorResolver() {
    }

    public static int resolve(DamageSource source) {
        ResourceLocation id = source.typeHolder().unwrapKey().map(ResourceKey::location)
                .orElseGet(() -> ResourceLocation.fromNamespaceAndPath("minecraft", source.getMsgId()));
        return resolveInternal(id, tag -> source.is(TagKey.create(Registries.DAMAGE_TYPE, tag)));
    }

    public static int resolve(ResourceLocation id, Predicate<TagKey<DamageType>> tags) {
        return resolveInternal(id, tag -> tags.test(TagKey.create(null, tag)));
    }

    private static int resolveInternal(ResourceLocation id, Predicate<ResourceLocation> tags) {
        if (isMinecraft(id, "trident")) {
            return TRIDENT;
        }
        if (isMinecraft(id, "sonic_boom")) {
            return SONIC_BOOM;
        }
        if (isMinecraft(id, "drown")) {
            return DROWNING;
        }
        if (isMinecraft(id, "dragon_breath")) {
            return DRAGON_BREATH;
        }
        if (isMinecraft(id, "freeze")) {
            return FREEZING;
        }
        if (isMinecraft(id, "lightning_bolt")) {
            return LIGHTNING;
        }
        if (isMinecraft(id, FIRE_IDS)) {
            return FIRE;
        }
        if (isMinecraft(id, EXPLOSION_IDS)) {
            return EXPLOSION;
        }
        if (isMinecraft(id, MAGIC_IDS)) {
            return MAGIC;
        }
        if (isMinecraft(id, THORNS_IDS)) {
            return THORNS;
        }
        if (isMinecraft(id, WITHER_IDS)) {
            return WITHER;
        }
        if (isMinecraft(id, WIND_IDS)) {
            return WIND;
        }
        if (isAttributesLib(id, "bleeding")) {
            return BLEEDING;
        }
        if (tags.test(IS_FIRE)) {
            return FIRE;
        }
        if (tags.test(IS_EXPLOSION)) {
            return EXPLOSION;
        }
        if (tags.test(IS_FREEZING)) {
            return FREEZING;
        }
        if (tags.test(IS_LIGHTNING)) {
            return LIGHTNING;
        }
        if (tags.test(IS_DROWNING)) {
            return DROWNING;
        }
        if (tags.test(WITCH_RESISTANT_TO)) {
            return MAGIC;
        }
        return WHITE;
    }

    private static boolean isMinecraft(ResourceLocation id, String path) {
        return "minecraft".equals(id.getNamespace()) && path.equals(id.getPath());
    }

    private static boolean isMinecraft(ResourceLocation id, Set<String> paths) {
        return "minecraft".equals(id.getNamespace()) && paths.contains(id.getPath());
    }

    private static boolean isAttributesLib(ResourceLocation id, String path) {
        return "attributeslib".equals(id.getNamespace()) && path.equals(id.getPath());
    }

    private static ResourceLocation minecraft(String path) {
        return ResourceLocation.fromNamespaceAndPath("minecraft", path);
    }
}
