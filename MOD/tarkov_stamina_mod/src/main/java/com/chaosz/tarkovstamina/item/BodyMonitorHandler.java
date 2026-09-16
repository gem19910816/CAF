package com.chaosz.tarkovstamina.item;

import com.chaosz.tarkovstamina.network.StaminaNetwork;
import com.chaosz.tarkovstamina.network.StatusScreenPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 身体监测终端
 * <p>
 * 右键 {@code caf:body_monitor} 发送 StatusScreenPacket 打开 /caf 面板
 * </p>
 */
public final class BodyMonitorHandler {
    private static final ResourceLocation BODY_MONITOR_ID = ResourceLocation.tryBuild("caf", "body_monitor");

    private BodyMonitorHandler() {
    }

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickItem event) {
        if (event.getSide().isClient()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var stack = event.getItemStack();
        if (stack.isEmpty()) return;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (!BODY_MONITOR_ID.equals(id)) return;
        StaminaNetwork.sendStatus(player, StatusScreenPacket.from(player));
        event.setCanceled(true);
    }
}