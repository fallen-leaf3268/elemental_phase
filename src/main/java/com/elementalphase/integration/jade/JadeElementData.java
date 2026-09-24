package com.elementalphase.integration.jade;

import com.elementalphase.data.model.ElementDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class JadeElementData {
    public static final String DATA_KEY = "elemental_phase:elements";

    private JadeElementData() {
    }

    public static List<Entry> collect(Map<ResourceLocation, Double> amounts,
                                      Map<ResourceLocation, ElementDefinition> definitions) {
        return amounts.entrySet().stream()
                .filter(entry -> entry.getValue() != null
                        && Double.isFinite(entry.getValue())
                        && entry.getValue() > 0.0D)
                .filter(entry -> definitions.containsKey(entry.getKey()))
                .filter(entry -> definitions.get(entry.getKey()).display().visibleInJade())
                .map(entry -> {
                    var display = definitions.get(entry.getKey()).display();
                    return new Entry(entry.getKey(), display.translationKey(), entry.getValue(), display.color(),
                            display.order(), display.icon());
                })
                .filter(entry -> !entry.translationKey().isBlank())
                .sorted(Comparator.comparingInt(Entry::order)
                        .thenComparing(entry -> entry.id().toString()))
                .toList();
    }

    public static void write(CompoundTag target, List<Entry> entries) {
        target.remove(DATA_KEY);
        ListTag list = new ListTag();
        for (Entry entry : entries) {
            CompoundTag value = new CompoundTag();
            value.putString("id", entry.id().toString());
            value.putString("translation_key", entry.translationKey());
            value.putDouble("amount", entry.amount());
            value.putInt("color", entry.color());
            value.putInt("order", entry.order());
            entry.icon().ifPresent(icon -> value.putString("icon", icon.toString()));
            list.add(value);
        }
        if (!list.isEmpty()) {
            target.put(DATA_KEY, list);
        }
    }

    public static List<Entry> read(CompoundTag source) {
        List<Entry> result = new ArrayList<>();
        ListTag list = source.getList(DATA_KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag value = list.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(value.getString("id"));
            String translationKey = value.getString("translation_key");
            double amount = value.getDouble("amount");
            int color = value.getInt("color");
            int order = value.getInt("order");
            ResourceLocation icon = value.contains("icon", Tag.TAG_STRING)
                    ? ResourceLocation.tryParse(value.getString("icon")) : null;
            boolean validMetadata = value.contains("color", Tag.TAG_INT) && value.contains("order", Tag.TAG_INT)
                    && color >= 0 && color <= 0xFFFFFF
                    && (!value.contains("icon") || icon != null);
            if (id != null && !translationKey.isBlank() && Double.isFinite(amount) && amount > 0.0D && validMetadata) {
                result.add(new Entry(id, translationKey, amount, color, order, Optional.ofNullable(icon)));
            }
        }
        result.sort(Comparator.comparingInt(Entry::order).thenComparing(entry -> entry.id().toString()));
        return List.copyOf(result);
    }

    public static String formatAmount(double amount) {
        if (!Double.isFinite(amount)) {
            return "0";
        }
        BigDecimal rounded = BigDecimal.valueOf(amount).setScale(1, RoundingMode.HALF_UP);
        if (amount > 0.0D && rounded.signum() == 0) {
            return "<0.1";
        }
        return rounded.stripTrailingZeros().toPlainString();
    }

    public record Entry(ResourceLocation id, String translationKey, double amount, int color, int order,
                        Optional<ResourceLocation> icon) {
    }
}
