package com.gearsandflesh.market.network;

import com.gearsandflesh.market.data.MarketCategory;
import com.gearsandflesh.market.data.MarketQuery;
import com.gearsandflesh.market.data.MarketSort;
import com.gearsandflesh.market.data.MarketView;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;

final class NetworkCodecs {
    private NetworkCodecs() {
    }

    static MarketQuery readQuery(FriendlyByteBuf buffer) {
        MarketView view = readEnum(buffer, MarketView.values(), "market view");
        MarketCategory category = readEnum(buffer, MarketCategory.values(), "market category");
        MarketSort sort = readEnum(buffer, MarketSort.values(), "market sort");
        String search = buffer.readUtf(64);
        int page = buffer.readVarInt();
        return new MarketQuery(view, category, sort, search, page);
    }

    static int readCount(FriendlyByteBuf buffer, int maximum, String field) {
        int count = buffer.readVarInt();
        if (count < 0 || count > maximum) {
            throw new DecoderException("Invalid " + field + " count: " + count);
        }
        return count;
    }

    private static <E extends Enum<E>> E readEnum(FriendlyByteBuf buffer, E[] values, String field) {
        int ordinal = buffer.readVarInt();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new DecoderException("Invalid " + field + " ordinal: " + ordinal);
        }
        return values[ordinal];
    }
}
