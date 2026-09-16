package com.gearsandflesh.market.network;

import com.gearsandflesh.market.service.MarketService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record CancelListingC2S(long listingId) {
    public static void encode(CancelListingC2S message, FriendlyByteBuf buffer) {
        buffer.writeVarLong(message.listingId);
    }

    public static CancelListingC2S decode(FriendlyByteBuf buffer) {
        return new CancelListingC2S(buffer.readVarLong());
    }

    public static void handle(CancelListingC2S message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                MarketNetwork.finishOperation(
                        player,
                        MarketService.cancelListing(player, message.listingId)
                );
            }
        });
        context.setPacketHandled(true);
    }
}
