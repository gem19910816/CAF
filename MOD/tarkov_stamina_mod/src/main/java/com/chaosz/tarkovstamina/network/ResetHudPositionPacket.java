package com.chaosz.tarkovstamina.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 重置 HUD 体力条位置到默认（0,0）的包（服务端 → 客户端）
 */
public record ResetHudPositionPacket() {

    public static void encode(ResetHudPositionPacket p, FriendlyByteBuf buf) {
    }

    public static ResetHudPositionPacket decode(FriendlyByteBuf buf) {
        return new ResetHudPositionPacket();
    }

    // 服务端兼容修复（2026-09-18）：原 handle() 引用 client/HudPositionConfig，
    // 其实现引用 net.minecraft.client 的类，专用服务器上有加载风险。处理逻辑已迁移到
    // com.chaosz.tarkovstamina.client.ClientPacketHandlers#handleHudReset。
}
