package com.chaosz.tarkovstamina.network;

import net.minecraft.network.FriendlyByteBuf;

public record StaminaSyncPacket(float stamina, float maximum, int cooldown, int maximumCooldown,
                                boolean sprinting, boolean exhausted, boolean hidden) {

    public static void encode(StaminaSyncPacket packet, FriendlyByteBuf buffer) {
        buffer.writeFloat(packet.stamina);
        buffer.writeFloat(packet.maximum);
        buffer.writeVarInt(packet.cooldown);
        buffer.writeVarInt(packet.maximumCooldown);
        buffer.writeBoolean(packet.sprinting);
        buffer.writeBoolean(packet.exhausted);
        buffer.writeBoolean(packet.hidden);
    }

    public static StaminaSyncPacket decode(FriendlyByteBuf buffer) {
        return new StaminaSyncPacket(
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readBoolean()
        );
    }

    // 服务端兼容修复（2026-09-18）：为保持四个数据包处理风格统一，handle 迁移到
    // com.chaosz.tarkovstamina.client.ClientPacketHandlers#handleStaminaSync。
}
