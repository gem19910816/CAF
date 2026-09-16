package com.gearsandflesh.quests.client;

import com.gearsandflesh.quests.QuestConstants;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

public final class QuestClientEvents {
    private static final KeyMapping OPEN = new KeyMapping(
            "key.gearsandflesh_quests.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J,
            "key.categories.gearsandflesh_quests");

    @Mod.EventBusSubscriber(modid = QuestConstants.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        @SubscribeEvent public static void registerKeys(RegisterKeyMappingsEvent event) { event.register(OPEN); }

        @SubscribeEvent
        public static void registerOverlays(net.minecraftforge.client.event.RegisterGuiOverlaysEvent event) {
            event.registerAboveAll("quest_tracker",
                    (net.minecraftforge.client.gui.overlay.ForgeGui gui, net.minecraft.client.gui.GuiGraphics graphics,
                     float partialTick, int screenWidth, int screenHeight) ->
                            ClientQuestState.renderTracker(graphics));
        }
    }

    @Mod.EventBusSubscriber(modid = QuestConstants.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class ForgeBus {
        @SubscribeEvent public static void clientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Minecraft minecraft = Minecraft.getInstance();
            while (OPEN.consumeClick()) {
                if (minecraft.player != null && minecraft.screen == null) ClientQuestState.open();
            }
        }
    }
}
