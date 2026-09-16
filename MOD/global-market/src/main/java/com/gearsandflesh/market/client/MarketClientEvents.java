package com.gearsandflesh.market.client;

import com.gearsandflesh.market.MarketConstants;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

public final class MarketClientEvents {
    private static final KeyMapping OPEN_MARKET = new KeyMapping(
            "key.gearsandflesh_market.open",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.categories.gearsandflesh_market"
    );

    private MarketClientEvents() {
    }

    @Mod.EventBusSubscriber(
            modid = MarketConstants.MOD_ID,
            value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.MOD
    )
    public static final class ModBusEvents {
        private ModBusEvents() {
        }

        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            event.register(OPEN_MARKET);
        }
    }

    @Mod.EventBusSubscriber(
            modid = MarketConstants.MOD_ID,
            value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.FORGE
    )
    public static final class ForgeBusEvents {
        private ForgeBusEvents() {
        }

        @SubscribeEvent
        public static void clientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            while (OPEN_MARKET.consumeClick()) {
                if (minecraft.player != null && minecraft.screen == null) {
                    ClientMarketState.open();
                }
            }
        }
    }
}
