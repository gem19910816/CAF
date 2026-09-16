package com.gearsandflesh.market.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record MarketNoticeS2C(boolean success, String message) {
    private static final int MAX_MESSAGE_LENGTH = 256;

    public MarketNoticeS2C {
        message = sanitize(message);
    }

    public static void encode(MarketNoticeS2C message, FriendlyByteBuf buffer) {
        buffer.writeBoolean(message.success);
        buffer.writeUtf(message.message, MAX_MESSAGE_LENGTH);
    }

    public static MarketNoticeS2C decode(FriendlyByteBuf buffer) {
        return new MarketNoticeS2C(
                buffer.readBoolean(),
                buffer.readUtf(MAX_MESSAGE_LENGTH)
        );
    }

    public static void handle(
            MarketNoticeS2C message,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> com.gearsandflesh.market.client.ClientMarketState.onNotice(message)
        ));
        context.setPacketHandled(true);
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.replaceAll("[\\p{Cntrl}]", "");
        return clean.length() <= MAX_MESSAGE_LENGTH
                ? clean
                : clean.substring(0, MAX_MESSAGE_LENGTH);
    }
}
