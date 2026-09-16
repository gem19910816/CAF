package com.chaosz.tarkovstamina.network;

import com.chaosz.tarkovstamina.TarkovStamina;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Objects;

public final class StaminaNetwork {
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            Objects.requireNonNull(ResourceLocation.tryBuild(TarkovStamina.MOD_ID, "main")),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private StaminaNetwork() {
    }

    public static void register() {
        CHANNEL.messageBuilder(StaminaSyncPacket.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(StaminaSyncPacket::encode)
                .decoder(StaminaSyncPacket::decode)
                .consumerMainThread(StaminaSyncPacket::handle)
                .add();
        CHANNEL.messageBuilder(StatusScreenPacket.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(StatusScreenPacket::encode)
                .decoder(StatusScreenPacket::decode)
                .consumerMainThread(StatusScreenPacket::handle)
                .add();
        CHANNEL.messageBuilder(OpenHudScreenPacket.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OpenHudScreenPacket::encode)
                .decoder(OpenHudScreenPacket::decode)
                .consumerMainThread(OpenHudScreenPacket::handle)
                .add();
        CHANNEL.messageBuilder(ResetHudPositionPacket.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ResetHudPositionPacket::encode)
                .decoder(ResetHudPositionPacket::decode)
                .consumerMainThread(ResetHudPositionPacket::handle)
                .add();
    }

    public static void send(ServerPlayer player, StaminaSyncPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void sendStatus(ServerPlayer player, StatusScreenPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void sendHudOpen(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenHudScreenPacket());
    }

    public static void sendHudReset(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ResetHudPositionPacket());
    }
}