package com.november.mcphone.feature.caf.net;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 客户端 → 服务端：玩家在手机里点了「生存档案」。
 * 包体故意没字段：服务端从连接上下文取玩家，带玩家 ID 等于给伪造客户端开后门。
 */
public record OpenCafStatusPacket() {

    public static void encode(OpenCafStatusPacket msg, FriendlyByteBuf buf) {
    }

    public static OpenCafStatusPacket decode(FriendlyByteBuf buf) {
        return new OpenCafStatusPacket();
    }
}
