package com.chaosz.tarkovstamina.backpack.client;

import com.chaosz.tarkovstamina.backpack.BackpackNetwork;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;
import top.theillusivec4.curios.api.client.CuriosRendererRegistry;
import top.theillusivec4.curios.api.CuriosApi;

import com.chaosz.tarkovstamina.backpack.BackpackRegistration;
import com.chaosz.tarkovstamina.backpack.item.MilitaryBackpackItem;

public final class BackpackClientRegistration {
    private static KeyMapping openBackpackKey;

    private BackpackClientRegistration() {}

    public static boolean isBackpackKey(int keyCode, int scanCode) {
        return openBackpackKey != null && openBackpackKey.matches(keyCode, scanCode);
    }

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(BackpackClientRegistration::onClientSetup);
        modEventBus.addListener(BackpackClientRegistration::onRegisterKeyMappings);
        MinecraftForge.EVENT_BUS.addListener(BackpackClientRegistration::onClientTick);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(BackpackRegistration.BACKPACK_MENU.get(), BackpackScreen::new);
            // 只注册自己的渲染器，绝不调用 CuriosRendererRegistry.load()！
            // load() 会实例化所有注册的 renderer（包括 superbwarfare 的 parachute），
            // 缺失模型会直接导致客户端崩溃。
            CuriosRendererRegistry.register(
                    BackpackRegistration.MILITARY_BACKPACK.get(),
                    () -> new BackpackCurioRenderer());
            CuriosRendererRegistry.register(
                    BackpackRegistration.SATCHEL.get(),
                    () -> new SatchelCurioRenderer());
            CuriosRendererRegistry.register(
                    BackpackRegistration.SCHOOL_BAG.get(),
                    () -> new BackpackCurioRenderer());
            CuriosRendererRegistry.register(
                    BackpackRegistration.HIKING_BACKPACK.get(),
                    () -> new BackpackCurioRenderer());
        });
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        openBackpackKey = new KeyMapping(
                "key.caf.backpack.open",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                "key.category.caf");
        event.register(openBackpackKey);
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            if (openBackpackKey != null && openBackpackKey.consumeClick()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.screen instanceof BackpackScreen) {
                    mc.setScreen(null);
                } else if (mc.screen == null && mc.player != null) {
                    if (hasBackpack(mc)) {
                        BackpackNetwork.sendOpenBackpackPacket();
                    }
                }
            }
        }
    }

    private static boolean hasBackpack(Minecraft mc) {
        if (mc.player.getMainHandItem().getItem() instanceof MilitaryBackpackItem
                || mc.player.getOffhandItem().getItem() instanceof MilitaryBackpackItem) {
            return true;
        }
        return CuriosApi.getCuriosInventory(mc.player).resolve()
                .flatMap(inv -> inv.findFirstCurio(stack -> stack.getItem() instanceof MilitaryBackpackItem))
                .isPresent();
    }
}
