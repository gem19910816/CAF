package com.chaosz.bodybag;

import com.chaosz.bodybag.server.TrackingData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(BodyBagMod.MODID)
public class BodyBagMod {

    public static final String MODID = "bodybag";

    public BodyBagMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Register config
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, BodyBagConfig.SPEC, "bodybag-server.toml");

        // Register items
        ModItems.ITEMS.register(modEventBus);

        // Register server-side tracking cleanup
        MinecraftForge.EVENT_BUS.register(TrackingData.class);
    }
}