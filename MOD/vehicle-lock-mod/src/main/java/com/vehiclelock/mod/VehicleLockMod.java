package com.vehiclelock.mod;

import com.mojang.logging.LogUtils;
import com.vehiclelock.mod.network.VehicleLockNetwork;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

@Mod(VehicleLockMod.MOD_ID)
public class VehicleLockMod {
    public static final String MOD_ID = "vehiclelock";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);

    public static final RegistryObject<CreativeModeTab> VEHICLELOCK_TAB =
            CREATIVE_TABS.register("vehiclelock", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.vehiclelock"))
                    .icon(() -> new ItemStack(ModItems.GAS_PUMP_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(ModItems.GAS_PUMP_ITEM.get());
                        output.accept(ModItems.FUEL_NOZZLE.get());
                        output.accept(ModItems.KEYCHAIN.get());
                    })
                    .build());

    public VehicleLockMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModItems.ITEMS.register(modEventBus);
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlocks.BLOCK_ENTITIES.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
        modEventBus.addListener(this::onCommonSetup);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        VehicleLockNetwork.register();
    }
}
