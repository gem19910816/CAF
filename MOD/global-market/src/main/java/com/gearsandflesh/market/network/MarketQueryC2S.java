package com.gearsandflesh.market.network;

import com.gearsandflesh.market.data.MarketQuery;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record MarketQueryC2S(MarketQuery query) {
    public MarketQueryC2S {
        query = query == null ? MarketQuery.defaults() : query;
    }

    public static void encode(MarketQueryC2S message, FriendlyByteBuf buffer) {
        message.query.write(buffer);
    }

    public static MarketQueryC2S decode(FriendlyByteBuf buffer) {
        return new MarketQueryC2S(NetworkCodecs.readQuery(buffer));
    }

    public static void handle(MarketQueryC2S message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            MarketNetwork.rememberQuery(player, message.query);
            MarketNetwork.sendSnapshot(player, message.query);
        });
        context.setPacketHandled(true);
    }
}
