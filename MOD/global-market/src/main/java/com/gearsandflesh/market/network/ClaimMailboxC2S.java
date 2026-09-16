package com.gearsandflesh.market.network;

import com.gearsandflesh.market.service.MarketService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ClaimMailboxC2S() {
    public static void encode(ClaimMailboxC2S message, FriendlyByteBuf buffer) {
    }

    public static ClaimMailboxC2S decode(FriendlyByteBuf buffer) {
        return new ClaimMailboxC2S();
    }

    public static void handle(ClaimMailboxC2S message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                MarketNetwork.finishOperation(player, MarketService.claimMailbox(player));
            }
        });
        context.setPacketHandled(true);
    }
}
