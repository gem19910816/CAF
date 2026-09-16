package com.vehiclelock.mod;

import com.mojang.blaze3d.platform.InputConstants;
import com.vehiclelock.mod.network.VehicleLockNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side: keybind (Y) for approving vehicle access requests.
 */
@Mod.EventBusSubscriber(modid = VehicleLockMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ClientSetup {

    public static final KeyMapping APPROVE_KEY = new KeyMapping(
            "key.vehiclelock.approve",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Y,
            "key.categories.vehiclelock"
    );

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(APPROVE_KEY);
        MinecraftForge.EVENT_BUS.addListener(ClientSetup::onClientTick);
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        while (APPROVE_KEY.consumeClick()) {
            VehicleLockNetwork.sendApproveRequest();
        }
    }
}