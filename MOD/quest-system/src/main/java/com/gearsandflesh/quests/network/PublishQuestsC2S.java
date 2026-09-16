package com.gearsandflesh.quests.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Admin asks the server to push the current quest list to every online player. */
public record PublishQuestsC2S() {
    public static void encode(PublishQuestsC2S message, FriendlyByteBuf buffer) {
    }

    public static PublishQuestsC2S decode(FriendlyByteBuf buffer) {
        return new PublishQuestsC2S();
    }

    public static void handle(PublishQuestsC2S message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                QuestNetwork.handlePublish(player);
            }
        });
        context.setPacketHandled(true);
    }
}
