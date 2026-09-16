package com.gearsandflesh.market.client;

import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;

/** Optional, reflection-only adapter for Rarity Core. */
final class RarityCompat {
    private static final int[] FALLBACK_COLORS = {
            0xA9B3BA, 0xD6D8DA, 0x79C985, 0x63A9E8, 0xB487E8, 0xE4A24B, 0xEB6572
    };
    private static final Method GET_RARITY;
    private static final Method GET_COLOR;

    static {
        Method rarity = null;
        Method color = null;
        try {
            Class<?> api = Class.forName("org.yanbwe.raritycore.api.RarityCoreAPI", false,
                    RarityCompat.class.getClassLoader());
            rarity = api.getMethod("getNormalizedRarity", ItemStack.class);
            color = api.getMethod("getRarityRgbColor", int.class);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Rarity Core is optional. The market stays functional without it.
        }
        GET_RARITY = rarity;
        GET_COLOR = color;
    }

    private RarityCompat() {
    }

    static int rarity(ItemStack stack) {
        if (GET_RARITY != null) {
            try {
                Object value = GET_RARITY.invoke(null, stack);
                if (value instanceof Number number) {
                    return Math.max(1, Math.min(7, number.intValue()));
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Fall through to a deterministic compatibility value.
            }
        }
        return 1;
    }

    static int color(int rarity) {
        int normalized = Math.max(1, Math.min(7, rarity));
        if (GET_COLOR != null) {
            try {
                Object value = GET_COLOR.invoke(null, normalized);
                if (value instanceof Number number) {
                    return 0xFF000000 | (number.intValue() & 0x00FFFFFF);
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Use the restrained local palette below.
            }
        }
        return 0xFF000000 | FALLBACK_COLORS[normalized - 1];
    }
}
