package com.chaosz.tarkovstamina.backpack;

import net.minecraft.resources.ResourceLocation;

/**
 * CAF 军用背包 —— 已整合进 CAF 核心。
 * 统一使用 {@code caf} 命名空间。
 */
public final class CafBackpack {
    public static final String NAMESPACE = "caf";

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, path);
    }

    public static final String ITEM_ID = "military_backpack";
    public static final String MENU_ID = "military_backpack_menu";

    private CafBackpack() {
    }
}