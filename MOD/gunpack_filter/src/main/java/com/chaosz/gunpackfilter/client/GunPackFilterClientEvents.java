package com.chaosz.gunpackfilter.client;

import com.chaosz.gunpackfilter.GunPackFilterMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端事件入口。整个类只会在客户端被加载。
 */
@Mod.EventBusSubscriber(modid = GunPackFilterMod.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GunPackFilterClientEvents {

    private GunPackFilterClientEvents() {
    }

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        GunPackFilterManager.INSTANCE.onScreenInit(event);
    }

    @SubscribeEvent
    public static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        GunPackFilterManager.INSTANCE.onScreenRenderPre(event);
    }

    @SubscribeEvent
    public static void onPlayerLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        GunPackFilterManager.INSTANCE.onLogout();
    }
}
