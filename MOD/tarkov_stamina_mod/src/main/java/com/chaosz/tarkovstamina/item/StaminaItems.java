package com.chaosz.tarkovstamina.item;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 物品注册
 * <p>
 * 注册到 {@code caf} 命名空间，与现有 KJS 物品 ID 完全一致。
 * 所有物品通过 {@link DeferredRegister} 延迟创建。
 * </p>
 */
public final class StaminaItems {
    public static final DeferredRegister<Item> REGISTRY =
            DeferredRegister.create(ForgeRegistries.ITEMS, "caf");

    // ── 体力核心物品 ──
    public static final RegistryObject<Item> SPECIAL_STRENGTH_INJECTION = REGISTRY
            .register("special_strength_injection",
                    () -> new Item(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<Item> BODY_MONITOR = REGISTRY
            .register("body_monitor",
                    () -> new Item(new Item.Properties().stacksTo(1)));

    // ── 生存物品 ──
    public static final RegistryObject<Item> SHIT = REGISTRY
            .register("shit",
                    () -> new Item(new Item.Properties().stacksTo(64)));

    // ── 功能物品 ──
    public static final RegistryObject<Item> LAPTOP = REGISTRY
            .register("bijibendiannao",
                    () -> new Item(new Item.Properties().stacksTo(1)));

    private StaminaItems() {
    }
}