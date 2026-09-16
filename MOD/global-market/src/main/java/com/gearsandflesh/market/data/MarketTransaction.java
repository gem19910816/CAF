package com.gearsandflesh.market.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

public record MarketTransaction(
        long id,
        ItemStack item,
        long price,
        UUID sellerId,
        String sellerName,
        UUID buyerId,
        String buyerName,
        long timestamp,
        Result result
) {
    public enum Result {
        SOLD,
        CANCELLED,
        EXPIRED,
        ADMIN_COPIED,
        ADMIN_REMOVED,
        ADMIN_DELETED
    }

    public MarketTransaction {
        item = item == null ? ItemStack.EMPTY : item.copy();
        sellerName = sanitizeName(sellerName);
        buyerName = sanitizeName(buyerName);
        result = result == null ? Result.SOLD : result;
    }

    public boolean involves(UUID playerId) {
        return sellerId.equals(playerId) || (buyerId != null && buyerId.equals(playerId));
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Id", id);
        tag.put("Item", item.save(new CompoundTag()));
        tag.putLong("Price", price);
        tag.putUUID("SellerId", sellerId);
        tag.putString("SellerName", sellerName);
        if (buyerId != null) {
            tag.putUUID("BuyerId", buyerId);
        }
        tag.putString("BuyerName", buyerName);
        tag.putLong("Timestamp", timestamp);
        tag.putString("Result", result.name());
        return tag;
    }

    public static MarketTransaction load(CompoundTag tag) {
        if (!tag.hasUUID("SellerId")) {
            return null;
        }
        ItemStack stack = ItemStack.of(tag.getCompound("Item"));
        if (stack.isEmpty()) {
            return null;
        }
        Result loadedResult;
        try {
            loadedResult = Result.valueOf(tag.getString("Result"));
        } catch (IllegalArgumentException ignored) {
            loadedResult = Result.SOLD;
        }
        return new MarketTransaction(
                tag.getLong("Id"),
                stack,
                tag.getLong("Price"),
                tag.getUUID("SellerId"),
                tag.getString("SellerName"),
                tag.hasUUID("BuyerId") ? tag.getUUID("BuyerId") : null,
                tag.getString("BuyerName"),
                tag.getLong("Timestamp"),
                loadedResult
        );
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeVarLong(id);
        MarketItemCodec.write(buffer, item);
        buffer.writeVarLong(price);
        buffer.writeUUID(sellerId);
        buffer.writeUtf(sellerName, 64);
        buffer.writeBoolean(buyerId != null);
        if (buyerId != null) {
            buffer.writeUUID(buyerId);
        }
        buffer.writeUtf(buyerName, 64);
        buffer.writeVarLong(timestamp);
        buffer.writeEnum(result);
    }

    public static MarketTransaction read(FriendlyByteBuf buffer) {
        long id = buffer.readVarLong();
        ItemStack item = MarketItemCodec.read(buffer);
        long price = buffer.readVarLong();
        UUID sellerId = buffer.readUUID();
        String sellerName = buffer.readUtf(64);
        UUID buyerId = buffer.readBoolean() ? buffer.readUUID() : null;
        String buyerName = buffer.readUtf(64);
        long timestamp = buffer.readVarLong();
        Result result = buffer.readEnum(Result.class);
        return new MarketTransaction(id, item, price, sellerId, sellerName, buyerId, buyerName, timestamp, result);
    }

    private static String sanitizeName(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.replaceAll("[\\p{Cntrl}]", "");
        return clean.length() <= 64 ? clean : clean.substring(0, 64);
    }
}
