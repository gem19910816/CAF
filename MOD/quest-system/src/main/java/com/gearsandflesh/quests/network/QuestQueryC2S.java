package com.gearsandflesh.quests.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record QuestQueryC2S(boolean adminMode) {
    public static void encode(QuestQueryC2S message, FriendlyByteBuf buffer) { buffer.writeBoolean(message.adminMode); }
    public static QuestQueryC2S decode(FriendlyByteBuf buffer) { return new QuestQueryC2S(buffer.readBoolean()); }
    public static void handle(QuestQueryC2S message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) QuestNetwork.sendSnapshot(player, message.adminMode && player.createCommandSourceStack().hasPermission(2));
        });
        context.setPacketHandled(true);
    }
}
