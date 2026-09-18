package com.chaosz.tarkovstamina.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 打开 HUD 位置调整界面的包（服务端 → 客户端）
 */
public record OpenHudScreenPacket() {

    public static void encode(OpenHudScreenPacket p, FriendlyByteBuf buf) {
    }

    public static OpenHudScreenPacket decode(FriendlyByteBuf buf) {
        return new OpenHudScreenPacket();
    }

    // 服务端兼容修复（2026-09-18）：原 handle() 引用 net.minecraft.client 的类，
    // 专用服务器加载本类即崩溃。处理逻辑已迁移到
    // com.chaosz.tarkovstamina.client.ClientPacketHandlers#handleHudOpen。
}
