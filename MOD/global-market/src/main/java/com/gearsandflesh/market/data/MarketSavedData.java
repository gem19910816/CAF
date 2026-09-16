package com.gearsandflesh.market.data;

import com.gearsandflesh.market.MarketConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Authoritative market state. This is always attached to the overworld data
 * storage so dimension unloads cannot split the market into separate copies.
 */
public final class MarketSavedData extends SavedData {
    private static final String DATA_NAME = MarketConstants.MOD_ID + "_market";

    private final Map<Long, MarketListing> listings = new LinkedHashMap<>();
    private final Deque<MarketTransaction> history = new ArrayDeque<>();
    /** Items and money whose delivery time has already arrived. */
    private final Map<UUID, Long> pendingMoney = new HashMap<>();
    private final Map<UUID, List<ItemStack>> pendingItems = new HashMap<>();
    /** Successful listing requests in the current rolling one-hour window. */
    private final Map<UUID, Deque<Long>> listingAttempts = new HashMap<>();
    /** Items reserved for a future delivery; these already consume mailbox slots. */
    private final Map<UUID, List<PendingItemDelivery>> pendingItemDeliveries = new HashMap<>();
    /** Sale proceeds reserved for a future delivery. */
    private final Map<UUID, List<PendingMoneyDelivery>> pendingMoneyDeliveries = new HashMap<>();
    private long nextListingId = 1L;
    private long nextTransactionId = 1L;

    public static MarketSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                MarketSavedData::load,
                MarketSavedData::new,
                DATA_NAME
        );
    }

    public static MarketSavedData load(CompoundTag root) {
        MarketSavedData data = new MarketSavedData();
        data.nextListingId = positiveOrOne(root.getLong("NextListingId"));
        data.nextTransactionId = positiveOrOne(root.getLong("NextTransactionId"));

        ListTag listingTags = root.getList("Listings", Tag.TAG_COMPOUND);
        long highestListingId = 0L;
        for (int i = 0; i < listingTags.size(); i++) {
            MarketListing listing;
            try {
                listing = MarketListing.load(listingTags.getCompound(i));
            } catch (RuntimeException malformedListing) {
                listing = null;
            }
            if (listing == null) {
                continue;
            }
            boolean validListing = listing.id() > 0L
                    && listing.price() > 0L
                    && listing.price() <= MarketConstants.MAX_PRICE
                    && MarketItemCodec.validationProblem(listing.item()) == null;
            if (!validListing || data.listings.putIfAbsent(listing.id(), listing) != null) {
                // Never silently throw an escrowed item away while migrating a
                // damaged/legacy file. It may exceed the new mailbox slot
                // limit, but it remains claimable and is saved again intact.
                data.addRecoveredItem(listing.sellerId(), listing.item());
                continue;
            }
            highestListingId = Math.max(highestListingId, listing.id());
        }
        data.nextListingId = Math.max(data.nextListingId, incrementOrMax(highestListingId));

        ListTag historyTags = root.getList("History", Tag.TAG_COMPOUND);
        long highestTransactionId = 0L;
        for (int i = 0; i < historyTags.size()
                && data.history.size() < MarketConstants.MAX_HISTORY_ENTRIES; i++) {
            MarketTransaction transaction;
            try {
                transaction = MarketTransaction.load(historyTags.getCompound(i));
            } catch (RuntimeException malformedTransaction) {
                transaction = null;
            }
            if (transaction == null || transaction.id() <= 0L) {
                continue;
            }
            data.history.addLast(transaction);
            highestTransactionId = Math.max(highestTransactionId, transaction.id());
        }
        data.nextTransactionId = Math.max(data.nextTransactionId, incrementOrMax(highestTransactionId));

        ListTag moneyTags = root.getList("PendingMoney", Tag.TAG_COMPOUND);
        for (int i = 0; i < moneyTags.size(); i++) {
            CompoundTag entry = moneyTags.getCompound(i);
            if (entry.hasUUID("Player")) {
                long amount = entry.getLong("Amount");
                if (amount > 0L) {
                    data.mergePendingMoney(entry.getUUID("Player"), amount);
                }
            }
        }

        ListTag mailboxTags = root.getList("PendingItems", Tag.TAG_COMPOUND);
        for (int i = 0; i < mailboxTags.size(); i++) {
            CompoundTag entry = mailboxTags.getCompound(i);
            if (!entry.hasUUID("Player")) {
                continue;
            }
            UUID playerId = entry.getUUID("Player");
            ListTag itemTags = entry.getList("Items", Tag.TAG_COMPOUND);
            List<ItemStack> mailbox = data.pendingItems.computeIfAbsent(
                    playerId, ignored -> new ArrayList<>()
            );
            for (int j = 0; j < itemTags.size(); j++) {
                ItemStack stack;
                try {
                    stack = ItemStack.of(itemTags.getCompound(j));
                } catch (RuntimeException malformedStack) {
                    continue;
                }
                if (!stack.isEmpty()) {
                    mailbox.add(stack);
                }
            }
            if (mailbox.isEmpty()) {
                data.pendingItems.remove(playerId);
            }
        }

        // Newer saves keep quota timestamps in one compact array per player.
        ListTag attemptTags = root.getList("ListingAttempts", Tag.TAG_COMPOUND);
        long now = System.currentTimeMillis();
        for (int i = 0; i < attemptTags.size(); i++) {
            CompoundTag entry = attemptTags.getCompound(i);
            if (!entry.hasUUID("Player")) {
                continue;
            }
            Deque<Long> attempts = new ArrayDeque<>();
            for (long timestamp : entry.getLongArray("Times")) {
                if (timestamp > 0L) {
                    attempts.addLast(timestamp);
                }
            }
            pruneAttempts(attempts, now);
            while (attempts.size() > MarketConstants.MAX_LISTINGS_PER_HOUR) {
                attempts.removeFirst();
            }
            if (!attempts.isEmpty()) {
                data.listingAttempts.put(entry.getUUID("Player"), attempts);
            }
        }

        loadItemDeliveries(data, root.getList("PendingItemDeliveries", Tag.TAG_COMPOUND));
        loadMoneyDeliveries(data, root.getList("PendingMoneyDeliveries", Tag.TAG_COMPOUND));
        return data;
    }

    private static void loadItemDeliveries(MarketSavedData data, ListTag playerTags) {
        for (int i = 0; i < playerTags.size(); i++) {
            CompoundTag playerTag = playerTags.getCompound(i);
            if (!playerTag.hasUUID("Player")) {
                continue;
            }
            UUID playerId = playerTag.getUUID("Player");
            List<PendingItemDelivery> deliveries = data.pendingItemDeliveries
                    .computeIfAbsent(playerId, ignored -> new ArrayList<>());
            ListTag deliveryTags = playerTag.getList("Deliveries", Tag.TAG_COMPOUND);
            for (int j = 0; j < deliveryTags.size(); j++) {
                CompoundTag deliveryTag = deliveryTags.getCompound(j);
                long deliverAt = deliveryTag.getLong("At");
                if (deliverAt <= 0L) {
                    continue;
                }
                ItemStack stack;
                try {
                    stack = ItemStack.of(deliveryTag.getCompound("Item"));
                } catch (RuntimeException malformedStack) {
                    continue;
                }
                if (!stack.isEmpty()) {
                    // Keep legacy/corrupt-but-readable deliveries intact. New
                    // submissions are still bounded by the normal quota.
                    deliveries.add(new PendingItemDelivery(deliverAt, stack));
                }
            }
            if (deliveries.isEmpty()) {
                data.pendingItemDeliveries.remove(playerId);
            }
        }
    }

    private static void loadMoneyDeliveries(MarketSavedData data, ListTag playerTags) {
        for (int i = 0; i < playerTags.size(); i++) {
            CompoundTag playerTag = playerTags.getCompound(i);
            if (!playerTag.hasUUID("Player")) {
                continue;
            }
            UUID playerId = playerTag.getUUID("Player");
            List<PendingMoneyDelivery> deliveries = data.pendingMoneyDeliveries
                    .computeIfAbsent(playerId, ignored -> new ArrayList<>());
            ListTag deliveryTags = playerTag.getList("Deliveries", Tag.TAG_COMPOUND);
            for (int j = 0; j < deliveryTags.size(); j++) {
                CompoundTag deliveryTag = deliveryTags.getCompound(j);
                long deliverAt = deliveryTag.getLong("At");
                long amount = deliveryTag.getLong("Amount");
                if (deliverAt > 0L && amount > 0L) {
                    deliveries.add(new PendingMoneyDelivery(deliverAt, amount));
                }
            }
            if (deliveries.isEmpty()) {
                data.pendingMoneyDeliveries.remove(playerId);
            }
        }
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putLong("NextListingId", nextListingId);
        root.putLong("NextTransactionId", nextTransactionId);

        ListTag listingTags = new ListTag();
        listings.values().forEach(listing -> listingTags.add(listing.save()));
        root.put("Listings", listingTags);

        ListTag historyTags = new ListTag();
        history.forEach(transaction -> historyTags.add(transaction.save()));
        root.put("History", historyTags);

        ListTag moneyTags = new ListTag();
        pendingMoney.forEach((playerId, amount) -> {
            if (amount <= 0L) {
                return;
            }
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Player", playerId);
            entry.putLong("Amount", amount);
            moneyTags.add(entry);
        });
        root.put("PendingMoney", moneyTags);

        ListTag mailboxTags = new ListTag();
        pendingItems.forEach((playerId, items) -> {
            if (items.isEmpty()) {
                return;
            }
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Player", playerId);
            ListTag itemTags = new ListTag();
            for (ItemStack stack : items) {
                if (!stack.isEmpty()) {
                    itemTags.add(stack.save(new CompoundTag()));
                }
            }
            if (!itemTags.isEmpty()) {
                entry.put("Items", itemTags);
                mailboxTags.add(entry);
            }
        });
        root.put("PendingItems", mailboxTags);

        ListTag attemptTags = new ListTag();
        listingAttempts.forEach((playerId, attempts) -> {
            if (attempts.isEmpty()) {
                return;
            }
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Player", playerId);
            long[] times = new long[attempts.size()];
            int index = 0;
            for (long timestamp : attempts) {
                times[index++] = timestamp;
            }
            entry.putLongArray("Times", times);
            attemptTags.add(entry);
        });
        root.put("ListingAttempts", attemptTags);

        root.put("PendingItemDeliveries", saveItemDeliveries());
        root.put("PendingMoneyDeliveries", saveMoneyDeliveries());
        return root;
    }

    private ListTag saveItemDeliveries() {
        ListTag playerTags = new ListTag();
        pendingItemDeliveries.forEach((playerId, deliveries) -> {
            if (deliveries.isEmpty()) {
                return;
            }
            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID("Player", playerId);
            ListTag deliveryTags = new ListTag();
            for (PendingItemDelivery delivery : deliveries) {
                if (delivery.item().isEmpty()) {
                    continue;
                }
                CompoundTag deliveryTag = new CompoundTag();
                deliveryTag.putLong("At", delivery.deliverAt());
                deliveryTag.put("Item", delivery.item().save(new CompoundTag()));
                deliveryTags.add(deliveryTag);
            }
            if (!deliveryTags.isEmpty()) {
                playerTag.put("Deliveries", deliveryTags);
                playerTags.add(playerTag);
            }
        });
        return playerTags;
    }

    private ListTag saveMoneyDeliveries() {
        ListTag playerTags = new ListTag();
        pendingMoneyDeliveries.forEach((playerId, deliveries) -> {
            if (deliveries.isEmpty()) {
                return;
            }
            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID("Player", playerId);
            ListTag deliveryTags = new ListTag();
            for (PendingMoneyDelivery delivery : deliveries) {
                if (delivery.amount() <= 0L) {
                    continue;
                }
                CompoundTag deliveryTag = new CompoundTag();
                deliveryTag.putLong("At", delivery.deliverAt());
                deliveryTag.putLong("Amount", delivery.amount());
                deliveryTags.add(deliveryTag);
            }
            if (!deliveryTags.isEmpty()) {
                playerTag.put("Deliveries", deliveryTags);
                playerTags.add(playerTag);
            }
        });
        return playerTags;
    }

    public long allocateListingId() {
        if (nextListingId == Long.MAX_VALUE) {
            throw new IllegalStateException("Market listing id space is exhausted");
        }
        return nextListingId++;
    }

    public long allocateTransactionId() {
        if (nextTransactionId == Long.MAX_VALUE) {
            throw new IllegalStateException("Market transaction id space is exhausted");
        }
        return nextTransactionId++;
    }

    public Collection<MarketListing> listings() {
        return Collections.unmodifiableCollection(listings.values());
    }

    public MarketListing listing(long id) {
        return listings.get(id);
    }

    public void addListing(MarketListing listing) {
        if (listings.putIfAbsent(listing.id(), listing) != null) {
            throw new IllegalArgumentException("Duplicate market listing id " + listing.id());
        }
        setDirty();
    }

    public MarketListing removeListing(long id) {
        MarketListing removed = listings.remove(id);
        if (removed != null) {
            setDirty();
        }
        return removed;
    }

    public int activeListingCount(UUID sellerId) {
        int count = 0;
        for (MarketListing listing : listings.values()) {
            if (listing.sellerId().equals(sellerId)) {
                count++;
            }
        }
        return count;
    }

    public void addHistory(MarketTransaction transaction) {
        history.addFirst(transaction);
        while (history.size() > MarketConstants.MAX_HISTORY_ENTRIES) {
            history.removeLast();
        }
        setDirty();
    }

    public List<MarketTransaction> recentHistory(UUID playerId, int limit) {
        List<MarketTransaction> result = new ArrayList<>(Math.min(limit, history.size()));
        for (MarketTransaction transaction : history) {
            if (transaction.involves(playerId)) {
                result.add(transaction);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    public int listingAttemptsUsed(UUID playerId, long now) {
        Deque<Long> attempts = listingAttempts.get(playerId);
        if (attempts == null) {
            return 0;
        }
        boolean changed = pruneAttempts(attempts, now);
        if (attempts.isEmpty()) {
            listingAttempts.remove(playerId);
            changed = true;
        }
        if (changed) {
            setDirty();
        }
        return attempts.size();
    }

    public int listingAttemptsRemaining(UUID playerId, long now) {
        return Math.max(0, MarketConstants.MAX_LISTINGS_PER_HOUR
                - listingAttemptsUsed(playerId, now));
    }

    public long listingQuotaResetAt(UUID playerId, long now) {
        Deque<Long> attempts = listingAttempts.get(playerId);
        if (attempts == null) {
            return 0L;
        }
        boolean changed = pruneAttempts(attempts, now);
        if (attempts.isEmpty()) {
            listingAttempts.remove(playerId);
            changed = true;
        }
        if (changed) {
            setDirty();
        }
        Long first = attempts.peekFirst();
        return first == null ? 0L : safeAdd(first, MarketConstants.LISTING_RATE_WINDOW_MILLIS);
    }

    public boolean canRecordListingAttempt(UUID playerId, long now) {
        return listingAttemptsUsed(playerId, now) < MarketConstants.MAX_LISTINGS_PER_HOUR;
    }

    public void recordListingAttempt(UUID playerId, long now) {
        if (!canRecordListingAttempt(playerId, now)) {
            throw new IllegalStateException("Listing rate limit reached");
        }
        listingAttempts.computeIfAbsent(playerId, ignored -> new ArrayDeque<>()).addLast(now);
        setDirty();
    }

    public long pendingMoney(UUID playerId) {
        return pendingMoney.getOrDefault(playerId, 0L);
    }

    public long inTransitMoney(UUID playerId) {
        long total = 0L;
        for (PendingMoneyDelivery delivery : pendingMoneyDeliveries.getOrDefault(playerId, List.of())) {
            total = saturatedAdd(total, Math.max(0L, delivery.amount()));
        }
        return total;
    }

    public long totalPendingMoney(UUID playerId) {
        return saturatedAdd(pendingMoney(playerId), inTransitMoney(playerId));
    }

    public boolean canCreditPendingMoney(UUID playerId, long amount) {
        if (amount <= 0L) {
            return false;
        }
        return totalPendingMoney(playerId) <= MarketConstants.MAX_PENDING_MONEY - amount;
    }

    public void creditPendingMoney(UUID playerId, long amount) {
        if (!canCreditPendingMoney(playerId, amount)) {
            throw new ArithmeticException("Pending market balance limit reached");
        }
        mergePendingMoney(playerId, amount);
        setDirty();
    }

    private void mergePendingMoney(UUID playerId, long amount) {
        long previous = pendingMoney.getOrDefault(playerId, 0L);
        pendingMoney.put(playerId, saturatedAdd(previous, amount));
    }

    public boolean canSchedulePendingMoney(UUID playerId, long amount) {
        if (amount <= 0L || pendingMoneyDeliveries
                .getOrDefault(playerId, List.of()).size() >= MarketConstants.MAX_PENDING_MONEY_DELIVERIES) {
            return false;
        }
        return totalPendingMoney(playerId) <= MarketConstants.MAX_PENDING_MONEY - amount;
    }

    public boolean schedulePendingMoney(UUID playerId, long amount, long deliverAt) {
        if (!canSchedulePendingMoney(playerId, amount) || deliverAt <= 0L) {
            return false;
        }
        pendingMoneyDeliveries.computeIfAbsent(playerId, ignored -> new ArrayList<>())
                .add(new PendingMoneyDelivery(deliverAt, amount));
        setDirty();
        return true;
    }

    public void setPendingMoney(UUID playerId, long amount) {
        if (amount > 0L) {
            // Legacy saves may already exceed the new submission cap. Claiming
            // part of that balance must never truncate the preserved remainder.
            pendingMoney.put(playerId, amount);
        } else {
            pendingMoney.remove(playerId);
        }
        setDirty();
    }

    public List<ItemStack> pendingItems(UUID playerId) {
        return pendingItems.computeIfAbsent(playerId, ignored -> new ArrayList<>());
    }

    private void addRecoveredItem(UUID playerId, ItemStack stack) {
        if (stack != null && !stack.isEmpty()) {
            pendingItems(playerId).add(stack.copy());
            setDirty();
        }
    }

    /** Trusted administrative returns bypass a full mailbox but never lose escrow. */
    public void addAdministrativeReturn(UUID playerId, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException("Cannot return an empty administrative market item");
        }
        addRecoveredItem(playerId, stack);
    }

    public List<ItemStack> pendingItemsView(UUID playerId) {
        List<ItemStack> items = pendingItems.get(playerId);
        return items == null ? List.of() : Collections.unmodifiableList(items);
    }

    /** Number of individual items already available to claim. */
    public int pendingItemCount(UUID playerId) {
        return saturatedItemCount(pendingItemsView(playerId));
    }

    /** Number of individual items still in the delivery queue. */
    public int inTransitItemCount(UUID playerId) {
        long count = 0L;
        for (PendingItemDelivery delivery : pendingItemDeliveries
                .getOrDefault(playerId, List.of())) {
            count += Math.max(0, delivery.item().getCount());
            if (count >= Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) count;
    }

    public int pendingMailboxSlots(UUID playerId) {
        long slots = 0L;
        for (ItemStack stack : pendingItemsView(playerId)) {
            slots = saturatedAdd(slots, requiredSlots(stack));
        }
        for (PendingItemDelivery delivery : pendingItemDeliveries
                .getOrDefault(playerId, List.of())) {
            slots = saturatedAdd(slots, requiredSlots(delivery.item()));
        }
        return (int) Math.min(Integer.MAX_VALUE, slots);
    }

    public int inTransitMailboxSlots(UUID playerId) {
        long slots = 0L;
        for (PendingItemDelivery delivery : pendingItemDeliveries
                .getOrDefault(playerId, List.of())) {
            slots = saturatedAdd(slots, requiredSlots(delivery.item()));
        }
        return (int) Math.min(Integer.MAX_VALUE, slots);
    }

    public long nextDeliveryAt(UUID playerId) {
        long next = Long.MAX_VALUE;
        for (PendingItemDelivery delivery : pendingItemDeliveries
                .getOrDefault(playerId, List.of())) {
            next = Math.min(next, delivery.deliverAt());
        }
        for (PendingMoneyDelivery delivery : pendingMoneyDeliveries
                .getOrDefault(playerId, List.of())) {
            next = Math.min(next, delivery.deliverAt());
        }
        return next == Long.MAX_VALUE ? 0L : next;
    }

    public boolean canAcceptPendingItem(UUID playerId, ItemStack offered) {
        if (offered == null || offered.isEmpty() || offered.getCount() <= 0) {
            return false;
        }
        long remaining = offered.getCount();
        List<ItemStack> mailbox = pendingItems.get(playerId);
        if (mailbox != null) {
            for (ItemStack existing : mailbox) {
                if (ItemStack.isSameItemSameTags(existing, offered)) {
                    remaining -= Math.max(0L,
                            (long) existing.getMaxStackSize() - existing.getCount());
                    if (remaining <= 0L) {
                        return pendingMailboxSlots(playerId) <= MarketConstants.MAX_MAILBOX_STACKS;
                    }
                }
            }
        }
        long occupied = pendingMailboxSlots(playerId);
        long additional = requiredSlots(remaining, offered.getMaxStackSize());
        return occupied <= MarketConstants.MAX_MAILBOX_STACKS - additional;
    }

    public boolean canSchedulePendingItem(UUID playerId, ItemStack offered) {
        if (!canAcceptPendingItem(playerId, offered)) {
            return false;
        }
        return pendingItemDeliveries.getOrDefault(playerId, List.of()).size()
                < MarketConstants.MAX_PENDING_ITEM_DELIVERIES;
    }

    public boolean schedulePendingItem(UUID playerId, ItemStack offered, long deliverAt) {
        if (offered == null || offered.isEmpty() || deliverAt <= 0L
                || !canSchedulePendingItem(playerId, offered)) {
            return false;
        }
        pendingItemDeliveries.computeIfAbsent(playerId, ignored -> new ArrayList<>())
                .add(new PendingItemDelivery(deliverAt, offered));
        setDirty();
        return true;
    }

    public boolean addPendingItem(UUID playerId, ItemStack offered) {
        if (!canAcceptPendingItem(playerId, offered)) {
            return false;
        }
        List<ItemStack> mailbox = pendingItems(playerId);
        ItemStack remaining = offered.copy();
        for (ItemStack existing : mailbox) {
            if (!ItemStack.isSameItemSameTags(existing, remaining)) {
                continue;
            }
            int moved = (int) Math.min(
                    remaining.getCount(),
                    Math.max(0L, (long) existing.getMaxStackSize() - existing.getCount())
            );
            existing.grow(moved);
            remaining.shrink(moved);
            if (remaining.isEmpty()) {
                setDirty();
                return true;
            }
        }
        int maxPerStack = Math.max(1, remaining.getMaxStackSize());
        while (!remaining.isEmpty()) {
            ItemStack part = remaining.copy();
            part.setCount(Math.min(maxPerStack, remaining.getCount()));
            remaining.shrink(part.getCount());
            mailbox.add(part);
        }
        setDirty();
        return true;
    }

    /** Moves due deliveries into the claimable mailbox/balance. */
    public void releaseDueDeliveries(long now) {
        boolean changed = false;
        for (Map.Entry<UUID, List<PendingItemDelivery>> entry
                : new ArrayList<>(pendingItemDeliveries.entrySet())) {
            UUID playerId = entry.getKey();
            List<PendingItemDelivery> deliveries = entry.getValue();
            for (int index = 0; index < deliveries.size();) {
                PendingItemDelivery delivery = deliveries.get(index);
                if (delivery.deliverAt() > now) {
                    index++;
                    continue;
                }
                deliveries.remove(index);
                if (addPendingItem(playerId, delivery.item())) {
                    changed = true;
                } else {
                    // Keep the item safe if a legacy save was already overfull.
                    deliveries.add(index, delivery);
                    index++;
                }
            }
            if (deliveries.isEmpty()) {
                pendingItemDeliveries.remove(playerId);
                changed = true;
            }
        }

        for (Map.Entry<UUID, List<PendingMoneyDelivery>> entry
                : new ArrayList<>(pendingMoneyDeliveries.entrySet())) {
            UUID playerId = entry.getKey();
            List<PendingMoneyDelivery> deliveries = entry.getValue();
            for (int index = 0; index < deliveries.size();) {
                PendingMoneyDelivery delivery = deliveries.get(index);
                if (delivery.deliverAt() > now) {
                    index++;
                    continue;
                }
                deliveries.remove(index);
                if (canCreditPendingMoney(playerId, delivery.amount())) {
                    mergePendingMoney(playerId, delivery.amount());
                    changed = true;
                } else {
                    deliveries.add(index, delivery);
                    index++;
                }
            }
            if (deliveries.isEmpty()) {
                pendingMoneyDeliveries.remove(playerId);
                changed = true;
            }
        }
        if (changed) {
            setDirty();
        }
    }

    public void removeMailboxIfEmpty(UUID playerId) {
        List<ItemStack> mailbox = pendingItems.get(playerId);
        if (mailbox != null && mailbox.isEmpty()) {
            pendingItems.remove(playerId);
            setDirty();
        }
    }

    private static int saturatedItemCount(Collection<ItemStack> stacks) {
        long count = 0L;
        for (ItemStack stack : stacks) {
            count += Math.max(0, stack.getCount());
            if (count >= Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) count;
    }

    private static long requiredSlots(ItemStack stack) {
        return stack == null || stack.isEmpty()
                ? 0L
                : requiredSlots(stack.getCount(), stack.getMaxStackSize());
    }

    private static long requiredSlots(long count, int maxStackSize) {
        if (count <= 0L) {
            return 0L;
        }
        long max = Math.max(1L, maxStackSize);
        return (count + max - 1L) / max;
    }

    private static boolean pruneAttempts(Deque<Long> attempts, long now) {
        boolean changed = false;
        long cutoff = now - MarketConstants.LISTING_RATE_WINDOW_MILLIS;
        while (!attempts.isEmpty() && attempts.peekFirst() <= cutoff) {
            attempts.removeFirst();
            changed = true;
        }
        return changed;
    }

    private static long saturatedAdd(long left, long right) {
        if (right <= 0L) {
            return Math.max(0L, left);
        }
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long safeAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long positiveOrOne(long value) {
        return value > 0L ? value : 1L;
    }

    private static long incrementOrMax(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    public record PendingItemDelivery(long deliverAt, ItemStack item) {
        public PendingItemDelivery {
            item = item == null ? ItemStack.EMPTY : item.copy();
        }
    }

    public record PendingMoneyDelivery(long deliverAt, long amount) {
    }
}
