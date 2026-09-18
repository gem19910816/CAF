package com.chaosz.tarkovstamina.network;

import com.chaosz.tarkovstamina.TarkovStamina;
import com.chaosz.tarkovstamina.client.ClientPacketHandlers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLEnvironment;
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

    /**
     * 服务端兼容修复（2026-09-18）：数据包处理器引用了 {@code net.minecraft.client}
     * 的类（打开 Screen 等），专用服务器一旦链接这些处理器就会在加载时崩溃
     * （RuntimeDistCleaner: Attempted to load class .../Screen for invalid dist
     * DEDICATED_SERVER）。因此客户端用真实处理器，服务器端注册 no-op 处理器。
     * 两端的通道 ID、编码器、解码器完全一致，联机不受影响。
     */
    public static void register() {
        CHANNEL.messageBuilder(StaminaSyncPacket.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(StaminaSyncPacket::encode)
                .decoder(StaminaSyncPacket::decode)
                .consumerMainThread(FMLEnvironment.dist.isClient()
                        ? ClientPacketHandlers::handleStaminaSync
                        : (pkt, ctx) -> ctx.get().setPacketHandled(true))
                .add();
        CHANNEL.messageBuilder(StatusScreenPacket.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(StatusScreenPacket::encode)
                .decoder(StatusScreenPacket::decode)
                .consumerMainThread(FMLEnvironment.dist.isClient()
                        ? ClientPacketHandlers::handleStatus
                        : (pkt, ctx) -> ctx.get().setPacketHandled(true))
                .add();
        CHANNEL.messageBuilder(OpenHudScreenPacket.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OpenHudScreenPacket::encode)
                .decoder(OpenHudScreenPacket::decode)
                .consumerMainThread(FMLEnvironment.dist.isClient()
                        ? ClientPacketHandlers::handleHudOpen
                        : (pkt, ctx) -> ctx.get().setPacketHandled(true))
                .add();
        CHANNEL.messageBuilder(ResetHudPositionPacket.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ResetHudPositionPacket::encode)
                .decoder(ResetHudPositionPacket::decode)
                .consumerMainThread(FMLEnvironment.dist.isClient()
                        ? ClientPacketHandlers::handleHudReset
                        : (pkt, ctx) -> ctx.get().setPacketHandled(true))
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
