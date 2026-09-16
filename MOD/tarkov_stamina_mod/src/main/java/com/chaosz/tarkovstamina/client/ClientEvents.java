package com.chaosz.tarkovstamina.client;

import com.chaosz.tarkovstamina.TarkovStamina;
import com.chaosz.tarkovstamina.entity.ShitballEntity;
import com.chaosz.tarkovstamina.item.StaminaEntities;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = TarkovStamina.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientEvents {
    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            EntityRenderers.register(StaminaEntities.SHITBALL.get(), ThrownItemRenderer::new);
            HudPositionConfig.load(); // 加载上次保存的 HUD 位置（否则重启后偏移重置为 0,0）
        });
    }
}