package com.gearsandflesh.quests.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Claims every finished, unclaimed quest for the sender in one shot. */
public record ClaimAllC2S() {
    public static void encode(ClaimAllC2S message, FriendlyByteBuf buffer) {
    }

    public static ClaimAllC2S decode(FriendlyByteBuf buffer) {
        return new ClaimAllC2S();
    }

    public static void handle(ClaimAllC2S message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                QuestNetwork.handleClaimAll(player);
            }
        });
        context.setPacketHandled(true);
    }
}
