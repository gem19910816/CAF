package com.gearsandflesh.market.network;

import com.gearsandflesh.market.service.MarketService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record CreateListingC2S(int slot, int count, long price) {
    public static void encode(CreateListingC2S message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.slot);
        buffer.writeVarInt(message.count);
        buffer.writeVarLong(message.price);
    }

    public static CreateListingC2S decode(FriendlyByteBuf buffer) {
        return new CreateListingC2S(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarLong());
    }

    public static void handle(CreateListingC2S message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                MarketNetwork.finishOperation(
                        player,
                        MarketService.createListing(player, message.slot, message.count, message.price)
                );
            }
        });
        context.setPacketHandled(true);
    }
}
