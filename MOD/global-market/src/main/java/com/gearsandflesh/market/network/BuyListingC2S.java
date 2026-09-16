package com.gearsandflesh.market.network;

import com.gearsandflesh.market.service.MarketService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record BuyListingC2S(long listingId) {
    public static void encode(BuyListingC2S message, FriendlyByteBuf buffer) {
        buffer.writeVarLong(message.listingId);
    }

    public static BuyListingC2S decode(FriendlyByteBuf buffer) {
        return new BuyListingC2S(buffer.readVarLong());
    }

    public static void handle(BuyListingC2S message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                MarketNetwork.finishOperation(
                        player,
                        MarketService.buyListing(player, message.listingId)
                );
            }
        });
        context.setPacketHandled(true);
    }
}
