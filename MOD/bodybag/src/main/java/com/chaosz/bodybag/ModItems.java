package com.chaosz.bodybag;

import com.chaosz.bodybag.item.BodyBagItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BodyBagMod.MODID);

    public static final RegistryObject<Item> BODY_BAG = ITEMS.register("body_bag",
            () -> new BodyBagItem(new Item.Properties().stacksTo(1)));

    /**
     * Add the body bag to the vanilla "Tools & Utilities" creative tab.
     * Fired on the MOD event bus.
     */
    @Mod.EventBusSubscriber(modid = BodyBagMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class CreativeTabEvents {

        @SubscribeEvent
        public static void onBuildTabContents(BuildCreativeModeTabContentsEvent event) {
            if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
                event.accept(BODY_BAG.get());
            }
        }
    }
}