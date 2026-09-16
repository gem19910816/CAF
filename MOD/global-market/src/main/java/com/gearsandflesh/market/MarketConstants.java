package com.gearsandflesh.market;

import net.minecraft.resources.ResourceLocation;

public final class MarketConstants {
    public static final String MOD_ID = "gearsandflesh_market";
    public static final ResourceLocation MONEY_ID = new ResourceLocation("caf", "money");
    public static final int ADMIN_PERMISSION_LEVEL = 2;

    public static final int PAGE_SIZE = 9;
    public static final int MAX_ACTIVE_LISTINGS = 30;
    /** A listing request counts once, regardless of the stack quantity. */
    public static final int MAX_LISTINGS_PER_HOUR = 10;
    public static final long LISTING_RATE_WINDOW_MILLIS = 60L * 60L * 1_000L;
    public static final int MAX_HISTORY_ENTRIES = 1_000;
    public static final int MAX_MAILBOX_STACKS = 256;
    /** Prevents an offline player from accumulating unbounded delivery records. */
    public static final int MAX_PENDING_ITEM_DELIVERIES = 256;
    public static final int MAX_PENDING_MONEY_DELIVERIES = 256;
    public static final long MAX_PENDING_MONEY = 1_000_000_000_000L;
    public static final int MAX_ITEM_NBT_BYTES = 128 * 1024;
    public static final long MAX_PRICE = 1_000_000_000L;
    public static final long LISTING_LIFETIME_MILLIS = 3L * 24L * 60L * 60L * 1_000L;
    public static final int LISTING_FEE_PERCENT = 2;
    public static final long MIN_LISTING_FEE = 5L;
    public static final int SALE_FEE_PERCENT = 3;
    public static final long DELIVERY_DELAY_MILLIS = 10L * 60L * 1_000L;

    private MarketConstants() {
    }
}
