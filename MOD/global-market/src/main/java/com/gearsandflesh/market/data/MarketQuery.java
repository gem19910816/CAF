package com.gearsandflesh.market.data;

import net.minecraft.network.FriendlyByteBuf;

import java.util.Locale;

public record MarketQuery(MarketView view, MarketCategory category, MarketSort sort, String search, int page) {
    public MarketQuery {
        view = view == null ? MarketView.GLOBAL : view;
        category = category == null ? MarketCategory.ALL : category;
        sort = sort == null ? MarketSort.NEWEST : sort;
        search = sanitizeSearch(search);
        page = Math.max(0, page);
    }

    public static MarketQuery defaults() {
        return new MarketQuery(MarketView.GLOBAL, MarketCategory.ALL, MarketSort.NEWEST, "", 0);
    }

    public String normalizedSearch() {
        return search.toLowerCase(Locale.ROOT);
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeEnum(view);
        buffer.writeEnum(category);
        buffer.writeEnum(sort);
        buffer.writeUtf(search, 64);
        buffer.writeVarInt(page);
    }

    public static MarketQuery read(FriendlyByteBuf buffer) {
        return new MarketQuery(
                buffer.readEnum(MarketView.class),
                buffer.readEnum(MarketCategory.class),
                buffer.readEnum(MarketSort.class),
                buffer.readUtf(64),
                buffer.readVarInt()
        );
    }

    private static String sanitizeSearch(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.strip().replaceAll("[\\p{Cntrl}]", "");
        return clean.length() <= 64 ? clean : clean.substring(0, 64);
    }
}
