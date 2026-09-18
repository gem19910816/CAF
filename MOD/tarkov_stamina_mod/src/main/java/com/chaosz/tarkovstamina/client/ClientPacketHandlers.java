package com.chaosz.tarkovstamina.client;

import com.chaosz.tarkovstamina.network.OpenHudScreenPacket;
import com.chaosz.tarkovstamina.network.ResetHudPositionPacket;
import com.chaosz.tarkovstamina.network.StaminaSyncPacket;
import com.chaosz.tarkovstamina.network.StatusScreenPacket;
import com.chaosz.tarkovstamina.ui.StatusScreen;
import net.minecraft.client.Minecraft;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 纯客户端数据包处理器（服务端兼容修复，2026-09-18）。
 *
 * <p>这些处理器引用了 {@code net.minecraft.client} 的类（{@link Minecraft}、
 * {@code Screen} 等），而 Forge 的专用服务器（DEDICATED_SERVER）把这些类标记为
 * {@code @OnlyIn(Dist.CLIENT)}，一旦被加载就会直接崩溃。</p>
 *
 * <p>因此全部客户端数据处理从数据包类（StatusScreenPacket / OpenHudScreenPacket /
 * ResetHudPositionPacket / StaminaSyncPacket）迁移到这里：服务器端永远不会加载
 * 本类（注册入口 StaminaNetwork.register() 里做了 dist 判断），只有客户端才会链接
 * 到这些方法引用。</p>
 */
public final class ClientPacketHandlers {

    private ClientPacketHandlers() {
    }

    public static void handleStaminaSync(StaminaSyncPacket packet, Supplier<NetworkEvent.Context> context) {
        ClientStaminaState.accept(packet);
        context.get().setPacketHandled(true);
    }

    public static void handleStatus(StatusScreenPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> Minecraft.getInstance().setScreen(new StatusScreen(pkt)));
        ctx.get().setPacketHandled(true);
    }

    public static void handleHudOpen(OpenHudScreenPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            HudPositionConfig.load();
            Minecraft.getInstance().setScreen(new HudPositionScreen());
        });
        ctx.get().setPacketHandled(true);
    }

    public static void handleHudReset(ResetHudPositionPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> HudPositionConfig.reset());
        ctx.get().setPacketHandled(true);
    }
}
