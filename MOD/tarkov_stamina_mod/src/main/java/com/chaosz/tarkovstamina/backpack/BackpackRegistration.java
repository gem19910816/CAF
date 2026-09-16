package com.chaosz.tarkovstamina.backpack;

import com.chaosz.tarkovstamina.backpack.client.BackpackClientRegistration;
import com.chaosz.tarkovstamina.backpack.item.MilitaryBackpackItem;
import com.chaosz.tarkovstamina.backpack.menu.BackpackMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * CAF 军用背包注册（namespace: caf）
 */
public final class BackpackRegistration {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, CafBackpack.NAMESPACE);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, CafBackpack.NAMESPACE);

    public static final RegistryObject<Item> MILITARY_BACKPACK =
            ITEMS.register(CafBackpack.ITEM_ID,
                    () -> new MilitaryBackpackItem(new Item.Properties().stacksTo(1), 12, 9));

    public static final RegistryObject<Item> SATCHEL =
            ITEMS.register("satchel",
                    () -> new MilitaryBackpackItem(new Item.Properties().stacksTo(1), 9, 3));

    public static final RegistryObject<Item> SCHOOL_BAG =
            ITEMS.register("school_bag",
                    () -> new MilitaryBackpackItem(new Item.Properties().stacksTo(1), 9, 4));

    public static final RegistryObject<Item> HIKING_BACKPACK =
            ITEMS.register("hiking_backpack",
                    () -> new MilitaryBackpackItem(new Item.Properties().stacksTo(1), 9, 5));

    @SuppressWarnings("unchecked")
    public static final RegistryObject<MenuType<BackpackMenu>> BACKPACK_MENU =
            (RegistryObject<MenuType<BackpackMenu>>) (Object) MENUS.register(CafBackpack.MENU_ID,
                    () -> IForgeMenuType.create(BackpackMenu::new));

    private BackpackRegistration() {
    }

    /** 在 TarkovStamina 构造中调用 */
    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        MENUS.register(modEventBus);
        BackpackNetwork.register();

        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> BackpackClientRegistration.init(modEventBus));
    }
}