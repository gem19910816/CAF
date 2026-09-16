package com.gearsandflesh.quests.network;

import com.gearsandflesh.quests.data.QuestDefinition;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record SaveQuestC2S(QuestDefinition definition) {
    public static void encode(SaveQuestC2S message, FriendlyByteBuf buffer) { message.definition.write(buffer); }
    public static SaveQuestC2S decode(FriendlyByteBuf buffer) { return new SaveQuestC2S(QuestDefinition.read(buffer)); }
    public static void handle(SaveQuestC2S message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> { ServerPlayer player = context.getSender(); if (player != null) QuestNetwork.handleSave(player, message.definition); });
        context.setPacketHandled(true);
    }
}
