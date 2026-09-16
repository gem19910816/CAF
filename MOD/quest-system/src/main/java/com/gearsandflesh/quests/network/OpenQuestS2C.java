package com.gearsandflesh.quests.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record OpenQuestS2C(boolean adminMode) {
    public static void encode(OpenQuestS2C message, FriendlyByteBuf buffer) { buffer.writeBoolean(message.adminMode); }
    public static OpenQuestS2C decode(FriendlyByteBuf buffer) { return new OpenQuestS2C(buffer.readBoolean()); }
    public static void handle(OpenQuestS2C message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.gearsandflesh.quests.client.ClientQuestState.open(message.adminMode)));
        context.setPacketHandled(true);
    }
}
