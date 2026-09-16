package com.chaosz.tarkovstamina.profession;

import com.chaosz.tarkovstamina.network.StaminaNetwork;
import com.chaosz.tarkovstamina.network.StatusScreenPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 技能终端兼容：右键 caf:bijibendiannao 打开 /caf 面板
 */
public final class SkillTerminalHandler {
    private static final ResourceLocation LAPTOP_ID = ResourceLocation.tryBuild("caf", "bijibendiannao");

    private SkillTerminalHandler() {
    }

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickItem event) {
        if (event.getSide().isClient()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var stack = event.getItemStack();
        if (stack.isEmpty()) return;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (!LAPTOP_ID.equals(id)) return;
        StaminaNetwork.sendStatus(player, StatusScreenPacket.from(player));
        event.setCanceled(true);
    }
}