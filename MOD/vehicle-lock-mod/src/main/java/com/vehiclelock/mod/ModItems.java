package com.vehiclelock.mod;

import com.vehiclelock.mod.item.FuelNozzleItem;
import com.vehiclelock.mod.item.KeychainItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 本模组的物品注册。
 * 车辆钥匙使用官方 superbwarfare:vehicle_key（附加 NBT），不自行注册钥匙物品。
 */
public class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, VehicleLockMod.MOD_ID);

    public static final RegistryObject<Item> KEYCHAIN = ITEMS.register("key_chain", KeychainItem::new);

    public static final RegistryObject<Item> FUEL_NOZZLE =
            ITEMS.register("fuel_nozzle", () -> new FuelNozzleItem(new Item.Properties()));

    public static final RegistryObject<Item> GAS_PUMP_ITEM =
            ITEMS.register("gas_pump", () -> new BlockItem(ModBlocks.GAS_PUMP.get(), new Item.Properties()));
}
