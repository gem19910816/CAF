package com.gearsandflesh.market.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

public record MarketListing(
        long id,
        UUID sellerId,
        String sellerName,
        ItemStack item,
        long price,
        long createdAt,
        long expiresAt
) {
    public MarketListing {
        sellerName = sanitizeName(sellerName);
        item = item == null ? ItemStack.EMPTY : item.copy();
    }

    public boolean isExpired(long now) {
        return expiresAt <= now;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Id", id);
        tag.putUUID("SellerId", sellerId);
        tag.putString("SellerName", sellerName);
        tag.put("Item", item.save(new CompoundTag()));
        tag.putLong("Price", price);
        tag.putLong("CreatedAt", createdAt);
        tag.putLong("ExpiresAt", expiresAt);
        return tag;
    }

    public static MarketListing load(CompoundTag tag) {
        if (!tag.hasUUID("SellerId")) {
            return null;
        }
        ItemStack stack = ItemStack.of(tag.getCompound("Item"));
        if (stack.isEmpty()) {
            return null;
        }
        return new MarketListing(
                tag.getLong("Id"),
                tag.getUUID("SellerId"),
                tag.getString("SellerName"),
                stack,
                tag.getLong("Price"),
                tag.getLong("CreatedAt"),
                tag.getLong("ExpiresAt")
        );
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeVarLong(id);
        buffer.writeUUID(sellerId);
        buffer.writeUtf(sellerName, 64);
        MarketItemCodec.write(buffer, item);
        buffer.writeVarLong(price);
        buffer.writeVarLong(createdAt);
        buffer.writeVarLong(expiresAt);
    }

    public static MarketListing read(FriendlyByteBuf buffer) {
        return new MarketListing(
                buffer.readVarLong(),
                buffer.readUUID(),
                buffer.readUtf(64),
                MarketItemCodec.read(buffer),
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readVarLong()
        );
    }

    private static String sanitizeName(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.replaceAll("[\\p{Cntrl}]", "");
        return clean.length() <= 64 ? clean : clean.substring(0, 64);
    }
}
