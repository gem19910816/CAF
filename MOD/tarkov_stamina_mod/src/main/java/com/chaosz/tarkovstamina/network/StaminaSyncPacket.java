package com.chaosz.tarkovstamina.network;

import com.chaosz.tarkovstamina.client.ClientStaminaState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

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

    public static void handle(StaminaSyncPacket packet, Supplier<NetworkEvent.Context> context) {
        ClientStaminaState.accept(packet);
        context.get().setPacketHandled(true);
    }
}
