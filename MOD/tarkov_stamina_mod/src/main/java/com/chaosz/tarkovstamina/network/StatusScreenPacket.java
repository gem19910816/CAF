package com.chaosz.tarkovstamina.network;

import com.chaosz.tarkovstamina.StaminaSystem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public record StatusScreenPacket(
        float stamina, float maximum, int cooldown,
        int injectionCount, int exerciseLevel, double exerciseProgress,
        int woodcutCount, int stonecutCount, int mechanicCount,
        int fishLevel, int fishExp,
        int depressionLevel, boolean isSick,
        int monsterKills, long calmUntil,
        boolean isSmokeAddicted, boolean isAlcoholAddicted,
        long timeSinceLastPoop
) {

    public static void encode(StatusScreenPacket p, FriendlyByteBuf buf) {
        buf.writeFloat(p.stamina); buf.writeFloat(p.maximum); buf.writeVarInt(p.cooldown);
        buf.writeVarInt(p.injectionCount); buf.writeVarInt(p.exerciseLevel); buf.writeDouble(p.exerciseProgress);
        buf.writeVarInt(p.woodcutCount); buf.writeVarInt(p.stonecutCount); buf.writeVarInt(p.mechanicCount);
        buf.writeVarInt(p.fishLevel); buf.writeVarInt(p.fishExp);
        buf.writeVarInt(p.depressionLevel); buf.writeBoolean(p.isSick);
        buf.writeVarInt(p.monsterKills); buf.writeLong(p.calmUntil);
        buf.writeBoolean(p.isSmokeAddicted); buf.writeBoolean(p.isAlcoholAddicted);
        buf.writeLong(p.timeSinceLastPoop);
    }

    public static StatusScreenPacket decode(FriendlyByteBuf buf) {
        return new StatusScreenPacket(
                buf.readFloat(), buf.readFloat(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readDouble(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readBoolean(),
                buf.readVarInt(), buf.readLong(),
                buf.readBoolean(), buf.readBoolean(),
                buf.readLong());
    }

    // 服务端兼容修复（2026-09-18）：原 handle() 引用 net.minecraft.client 的类，
    // 专用服务器加载本类即崩溃。处理逻辑已迁移到
    // com.chaosz.tarkovstamina.client.ClientPacketHandlers#handleStatus。

    public static StatusScreenPacket from(ServerPlayer player) {
        CompoundTag s = StaminaSystem.state(player, true);
        CompoundTag pd = player.getPersistentData();
        return new StatusScreenPacket(
                s.getFloat("stamina"), StaminaSystem.maximumFor(player, s), s.getInt("cooldown"),
                s.getInt("injectionCount"), s.getInt("exerciseLevel"), s.getDouble("exerciseProgress"),
                pd.getInt("woodcutCount"), pd.getInt("stonecutCount"), pd.getInt("mechanicCount"),
                pd.getInt("fishLevel"), pd.getInt("fishExp"),
                s.getInt("depressionLevel"), s.getBoolean("isSick"),
                s.getInt("monsterKills"), s.getLong("calmUntil"),
                s.getBoolean("isSmokeAddicted"), s.getBoolean("isAlcoholAddicted"),
                s.getLong("timeSinceLastPoop"));
    }
}
