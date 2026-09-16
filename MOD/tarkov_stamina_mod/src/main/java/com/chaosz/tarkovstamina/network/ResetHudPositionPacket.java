package com.chaosz.tarkovstamina.network;

import com.chaosz.tarkovstamina.client.HudPositionConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 重置 HUD 体力条位置到默认（0,0）的包（服务端 → 客户端）
 */
public record ResetHudPositionPacket() {
    public static void encode(ResetHudPositionPacket p, FriendlyByteBuf buf) {
    }

    public static ResetHudPositionPacket decode(FriendlyByteBuf buf) {
        return new ResetHudPositionPacket();
    }

    public static void handle(ResetHudPositionPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            HudPositionConfig.reset();
        });
        ctx.get().setPacketHandled(true);
    }
}