package com.gearsandflesh.market.data;

import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Locale;

public enum MarketCategory {
    ALL("全部"),
    WEAPON("武器"),
    AMMO("弹药"),
    ARMOR("装备"),
    MEDICAL("医疗"),
    FOOD("食物"),
    MATERIAL("材料"),
    OTHER("其他");

    private final String displayName;

    MarketCategory(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean matches(ItemStack stack) {
        return this == ALL || classify(stack) == this;
    }

    public static MarketCategory classify(ItemStack stack) {
        if (stack.getItem() instanceof ArmorItem) {
            return ARMOR;
        }
        if (stack.isEdible()) {
            return FOOD;
        }

        var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        String value = key == null ? "" : key.toString().toLowerCase(Locale.ROOT);
        if (containsAny(value, "ammo", "bullet", "magazine", "shell", "cartridge", "round")) {
            return AMMO;
        }
        if (containsAny(value, "gun", "rifle", "pistol", "shotgun", "smg", "sniper", "weapon", "sword", "knife", "machete", "bow", "crossbow")) {
            return WEAPON;
        }
        if (containsAny(value, "medical", "medkit", "bandage", "first_aid", "syringe", "stim", "painkiller", "medicine")) {
            return MEDICAL;
        }
        if (containsAny(value, "ingot", "nugget", "ore", "dust", "plate", "sheet", "rod", "wire", "gem", "material")) {
            return MATERIAL;
        }
        return OTHER;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
