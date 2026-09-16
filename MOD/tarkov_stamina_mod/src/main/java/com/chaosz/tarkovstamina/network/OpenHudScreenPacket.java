package com.chaosz.tarkovstamina.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 打开 HUD 位置调整界面的包（服务端 → 客户端）
 */
public record OpenHudScreenPacket() {
    public static void encode(OpenHudScreenPacket p, FriendlyByteBuf buf) {
    }

    public static OpenHudScreenPacket decode(FriendlyByteBuf buf) {
        return new OpenHudScreenPacket();
    }

    public static void handle(OpenHudScreenPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            com.chaosz.tarkovstamina.client.HudPositionConfig.load();
            net.minecraft.client.Minecraft.getInstance().setScreen(
                    new com.chaosz.tarkovstamina.client.HudPositionScreen());
        });
        ctx.get().setPacketHandled(true);
    }
}