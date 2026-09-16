package com.gearsandflesh.market.network;

import com.gearsandflesh.market.MarketConstants;
import com.gearsandflesh.market.data.MarketCategory;
import com.gearsandflesh.market.data.MarketListing;
import com.gearsandflesh.market.data.MarketQuery;
import com.gearsandflesh.market.data.MarketTransaction;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

public record MarketSnapshotS2C(
        MarketQuery query,
        int page,
        int totalPages,
        int totalMatches,
        List<MarketListing> listings,
        long cash,
        long pendingMoney,
        int pendingItems,
        int inTransitItems,
        long inTransitMoney,
        long nextDeliveryAt,
        int listingAttemptsUsed,
        int listingAttemptsRemaining,
        long listingQuotaResetAt,
        int[] categoryCounts,
        List<MarketTransaction> history
) {
    private static final int MAX_HISTORY = 50;

    public MarketSnapshotS2C {
        query = query == null ? MarketQuery.defaults() : query;
        page = Math.max(0, page);
        totalPages = Math.max(1, totalPages);
        totalMatches = Math.max(0, totalMatches);
        listings = listings == null ? List.of() : List.copyOf(listings);
        if (listings.size() > MarketConstants.PAGE_SIZE) {
            throw new IllegalArgumentException("Market snapshot contains too many listings");
        }
        cash = Math.max(0L, cash);
        pendingMoney = Math.max(0L, pendingMoney);
        pendingItems = Math.max(0, pendingItems);
        inTransitItems = Math.max(0, inTransitItems);
        inTransitMoney = Math.max(0L, inTransitMoney);
        nextDeliveryAt = Math.max(0L, nextDeliveryAt);
        listingAttemptsUsed = Math.max(0, listingAttemptsUsed);
        listingAttemptsRemaining = Math.max(0, listingAttemptsRemaining);
        listingQuotaResetAt = Math.max(0L, listingQuotaResetAt);
        int expectedCategories = MarketCategory.values().length;
        categoryCounts = categoryCounts == null
                ? new int[expectedCategories]
                : Arrays.copyOf(categoryCounts, expectedCategories);
        history = history == null ? List.of() : List.copyOf(history);
        if (history.size() > MAX_HISTORY) {
            throw new IllegalArgumentException("Market snapshot contains too much history");
        }
    }

    @Override
    public int[] categoryCounts() {
        return categoryCounts.clone();
    }

    public static void encode(MarketSnapshotS2C message, FriendlyByteBuf buffer) {
        message.query.write(buffer);
        buffer.writeVarInt(message.page);
        buffer.writeVarInt(message.totalPages);
        buffer.writeVarInt(message.totalMatches);
        buffer.writeVarInt(message.listings.size());
        message.listings.forEach(listing -> listing.write(buffer));
        buffer.writeVarLong(message.cash);
        buffer.writeVarLong(message.pendingMoney);
        buffer.writeVarInt(message.pendingItems);
        buffer.writeVarInt(message.inTransitItems);
        buffer.writeVarLong(message.inTransitMoney);
        buffer.writeVarLong(message.nextDeliveryAt);
        buffer.writeVarInt(message.listingAttemptsUsed);
        buffer.writeVarInt(message.listingAttemptsRemaining);
        buffer.writeVarLong(message.listingQuotaResetAt);
        buffer.writeVarInt(message.categoryCounts.length);
        for (int count : message.categoryCounts) {
            buffer.writeVarInt(count);
        }
        buffer.writeVarInt(message.history.size());
        message.history.forEach(transaction -> transaction.write(buffer));
    }

    public static MarketSnapshotS2C decode(FriendlyByteBuf buffer) {
        MarketQuery query = NetworkCodecs.readQuery(buffer);
        int page = nonNegative(buffer.readVarInt(), "page");
        int totalPages = positive(buffer.readVarInt(), "total pages");
        int totalMatches = nonNegative(buffer.readVarInt(), "total matches");

        int listingCount = NetworkCodecs.readCount(
                buffer, MarketConstants.PAGE_SIZE, "market listing"
        );
        List<MarketListing> listings = new ArrayList<>(listingCount);
        for (int i = 0; i < listingCount; i++) {
            listings.add(MarketListing.read(buffer));
        }

        long cash = nonNegative(buffer.readVarLong(), "cash");
        long pendingMoney = nonNegative(buffer.readVarLong(), "pending money");
        int pendingItems = nonNegative(buffer.readVarInt(), "pending items");
        int inTransitItems = nonNegative(buffer.readVarInt(), "in-transit items");
        long inTransitMoney = nonNegative(buffer.readVarLong(), "in-transit money");
        long nextDeliveryAt = nonNegative(buffer.readVarLong(), "next delivery time");
        int listingAttemptsUsed = nonNegative(buffer.readVarInt(), "listing attempts used");
        int listingAttemptsRemaining = nonNegative(buffer.readVarInt(), "listing attempts remaining");
        long listingQuotaResetAt = nonNegative(buffer.readVarLong(), "listing quota reset time");

        int expectedCategories = MarketCategory.values().length;
        int categoryCount = NetworkCodecs.readCount(
                buffer, expectedCategories, "market category"
        );
        if (categoryCount != expectedCategories) {
            throw new DecoderException("Expected " + expectedCategories
                    + " market category counts, got " + categoryCount);
        }
        int[] categoryCounts = new int[categoryCount];
        for (int i = 0; i < categoryCount; i++) {
            categoryCounts[i] = nonNegative(buffer.readVarInt(), "category count");
        }

        int historyCount = NetworkCodecs.readCount(buffer, MAX_HISTORY, "market history");
        List<MarketTransaction> history = new ArrayList<>(historyCount);
        for (int i = 0; i < historyCount; i++) {
            history.add(MarketTransaction.read(buffer));
        }
        return new MarketSnapshotS2C(
                query, page, totalPages, totalMatches, listings, cash, pendingMoney,
                pendingItems, inTransitItems, inTransitMoney, nextDeliveryAt,
                listingAttemptsUsed, listingAttemptsRemaining, listingQuotaResetAt,
                categoryCounts, history
        );
    }

    public static void handle(
            MarketSnapshotS2C message,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> com.gearsandflesh.market.client.ClientMarketState.accept(message)
        ));
        context.setPacketHandled(true);
    }

    private static int nonNegative(int value, String field) {
        if (value < 0) {
            throw new DecoderException("Invalid negative " + field);
        }
        return value;
    }

    private static long nonNegative(long value, String field) {
        if (value < 0L) {
            throw new DecoderException("Invalid negative " + field);
        }
        return value;
    }

    private static int positive(int value, String field) {
        if (value <= 0) {
            throw new DecoderException("Invalid " + field);
        }
        return value;
    }
}
