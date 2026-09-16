package com.gearsandflesh.quests.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record DeleteQuestC2S(String id) {
    public DeleteQuestC2S { id = id == null ? "" : id; }
    public static void encode(DeleteQuestC2S message, FriendlyByteBuf buffer) { buffer.writeUtf(message.id, 32); }
    public static DeleteQuestC2S decode(FriendlyByteBuf buffer) { return new DeleteQuestC2S(buffer.readUtf(32)); }
    public static void handle(DeleteQuestC2S message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> { ServerPlayer player = context.getSender(); if (player != null) QuestNetwork.handleDelete(player, message.id); });
        context.setPacketHandled(true);
    }
}
