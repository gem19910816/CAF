package com.gearsandflesh.quests.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Claims one pool instance (daily/weekly/special) of a quest. */
public record ClaimQuestC2S(String id, String group) {
    public ClaimQuestC2S {
        id = id == null ? "" : id;
        group = group == null || group.isBlank() ? "DAILY" : group;
    }
    public static void encode(ClaimQuestC2S message, FriendlyByteBuf buffer) {
        buffer.writeUtf(message.id, 32);
        buffer.writeUtf(message.group, 16);
    }
    public static ClaimQuestC2S decode(FriendlyByteBuf buffer) { return new ClaimQuestC2S(buffer.readUtf(32), buffer.readUtf(16)); }
    public static void handle(ClaimQuestC2S message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> { ServerPlayer player = context.getSender(); if (player != null) QuestNetwork.handleClaim(player, message.id, message.group); });
        context.setPacketHandled(true);
    }
}
