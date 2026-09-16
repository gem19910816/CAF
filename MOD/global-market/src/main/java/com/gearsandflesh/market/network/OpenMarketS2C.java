package com.gearsandflesh.market.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record OpenMarketS2C(boolean adminMode) {
    public static void encode(OpenMarketS2C message, FriendlyByteBuf buffer) {
        buffer.writeBoolean(message.adminMode);
    }

    public static OpenMarketS2C decode(FriendlyByteBuf buffer) {
        return new OpenMarketS2C(buffer.readBoolean());
    }

    public static void handle(OpenMarketS2C message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> com.gearsandflesh.market.client.ClientMarketState.open(message.adminMode)
        ));
        context.setPacketHandled(true);
    }
}
