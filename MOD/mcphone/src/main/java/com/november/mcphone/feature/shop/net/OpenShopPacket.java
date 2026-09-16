package com.november.mcphone.feature.shop.net;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 客户端 → 服务端：玩家在手机里点了「商店」。
 * 包体故意没字段：服务端从连接上下文取玩家，带玩家 ID 等于给伪造客户端开后门。
 */
public record OpenShopPacket() {

    public static void encode(OpenShopPacket msg, FriendlyByteBuf buf) {
    }

    public static OpenShopPacket decode(FriendlyByteBuf buf) {
        return new OpenShopPacket();
    }
}
