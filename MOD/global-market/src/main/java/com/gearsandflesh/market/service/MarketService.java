package com.gearsandflesh.market.service;

import com.gearsandflesh.market.GlobalMarketMod;
import com.gearsandflesh.market.MarketConstants;
import com.gearsandflesh.market.data.MarketCategory;
import com.gearsandflesh.market.data.MarketItemCodec;
import com.gearsandflesh.market.data.MarketListing;
import com.gearsandflesh.market.data.MarketQuery;
import com.gearsandflesh.market.data.MarketSavedData;
import com.gearsandflesh.market.data.MarketSort;
import com.gearsandflesh.market.data.MarketTransaction;
import com.gearsandflesh.market.data.MarketView;
import com.gearsandflesh.market.network.MarketNetwork;
import com.gearsandflesh.market.network.MarketSnapshotS2C;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.PlayerMainInvWrapper;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class MarketService {
    private MarketService() {
    }

    public static OperationResult createListing(ServerPlayer seller, int slot, int count, long price) {
        requireServerThread(seller.server);
        Item money = moneyItem();
        String currencyProblem = currencyProblem(money);
        if (currencyProblem != null) {
            return OperationResult.error(currencyProblem);
        }
        if (price <= 0L || price > MarketConstants.MAX_PRICE) {
            return OperationResult.error("价格必须在 1 到 " + MarketConstants.MAX_PRICE + " 之间");
        }

        Inventory inventory = seller.getInventory();
        if (slot < 0 || slot >= inventory.getContainerSize()) {
            return OperationResult.error("无效的背包槽位");
        }
        ItemStack source = inventory.getItem(slot);
        if (source.isEmpty() || count <= 0 || count > source.getCount()) {
            return OperationResult.error("上架数量无效，物品可能已发生变化");
        }
        if (source.is(money)) {
            return OperationResult.error("市场货币本身不能上架");
        }

        MarketSavedData data = MarketSavedData.get(seller.server);
        long now = System.currentTimeMillis();
        data.releaseDueDeliveries(now);
        expireListings(seller.server);
        if (data.activeListingCount(seller.getUUID()) >= MarketConstants.MAX_ACTIVE_LISTINGS) {
            return OperationResult.error("活动挂单已达到 " + MarketConstants.MAX_ACTIVE_LISTINGS + " 个上限");
        }
        if (!data.canRecordListingAttempt(seller.getUUID(), now)) {
            long resetAt = data.listingQuotaResetAt(seller.getUUID(), now);
            return OperationResult.error("上架额度已用完（每小时最多 "
                    + MarketConstants.MAX_LISTINGS_PER_HOUR + " 次），下次可用时间 "
                    + formatTimeUntil(resetAt, now));
        }

        ItemStack escrow = source.copy();
        escrow.setCount(count);
        String itemProblem = MarketItemCodec.validationProblem(escrow);
        if (itemProblem != null) {
            return OperationResult.error(itemProblem);
        }

        long listingFee = listingFee(price);
        if (countMoney(seller, money) < listingFee) {
            return OperationResult.error("上架服务费不足，需要 " + listingFee + " 枚 caf:money");
        }
        long listingId;
        try {
            listingId = data.allocateListingId();
        } catch (IllegalStateException exhausted) {
            GlobalMarketMod.LOGGER.error("Unable to allocate a market listing id", exhausted);
            return OperationResult.error("市场编号已耗尽，请联系管理员");
        }

        MarketListing listing = new MarketListing(
                listingId,
                seller.getUUID(),
                seller.getGameProfile().getName(),
                escrow,
                price,
                now,
                now + MarketConstants.LISTING_LIFETIME_MILLIS
        );

        // Commit section: all validations above are side-effect free.
        deductMoney(seller, money, listingFee);
        source.shrink(count);
        if (source.isEmpty()) {
            inventory.setItem(slot, ItemStack.EMPTY);
        }
        inventory.setChanged();
        data.addListing(listing);
        data.recordListingAttempt(seller.getUUID(), now);
        seller.containerMenu.broadcastChanges();
        return OperationResult.ok("商品已上架，收取服务费 " + listingFee + " 枚");
    }

    public static OperationResult buyListing(ServerPlayer buyer, long listingId) {
        requireServerThread(buyer.server);
        Item money = moneyItem();
        String currencyProblem = currencyProblem(money);
        if (currencyProblem != null) {
            return OperationResult.error(currencyProblem);
        }

        MarketSavedData data = MarketSavedData.get(buyer.server);
        long now = System.currentTimeMillis();
        data.releaseDueDeliveries(now);
        expireListings(buyer.server);
        MarketListing listing = data.listing(listingId);
        if (listing == null) {
            return OperationResult.error("该挂单不存在或已结束");
        }
        if (listing.isExpired(System.currentTimeMillis())) {
            return OperationResult.error("该挂单已过期，正在等待退回卖家邮箱");
        }
        if (listing.sellerId().equals(buyer.getUUID())) {
            return OperationResult.error("不能购买自己上架的商品");
        }
        if (listing.price() <= 0L || listing.price() > MarketConstants.MAX_PRICE) {
            return OperationResult.error("该挂单价格无效");
        }
        long saleFee = saleFee(listing.price());
        long sellerNet = sellerNet(listing.price());
        if (!data.canSchedulePendingItem(buyer.getUUID(), listing.item())) {
            return OperationResult.error("安全邮箱已满或待处理记录过多，请先领取到达的物品");
        }
        if (sellerNet > 0L && !data.canSchedulePendingMoney(
                listing.sellerId(), sellerNet)) {
            return OperationResult.error("卖家的待结算余额已达到安全上限");
        }
        if (countMoney(buyer, money) < listing.price()) {
            return OperationResult.error("持有的 caf:money 不足");
        }
        long transactionId;
        try {
            transactionId = data.allocateTransactionId();
        } catch (IllegalStateException exhausted) {
            GlobalMarketMod.LOGGER.error("Unable to allocate a market transaction id", exhausted);
            return OperationResult.error("市场历史编号已耗尽，请联系管理员");
        }

        // All checks above are side-effect free. From here onward every operation
        // is deterministic on the server thread, making this the commit section.
        deductMoney(buyer, money, listing.price());
        MarketListing removed = data.removeListing(listingId);
        if (removed != listing) {
            throw new IllegalStateException("Market listing changed during an atomic purchase");
        }
        long deliverAt = safeDeliveryTime(now);
        if (!data.schedulePendingItem(buyer.getUUID(), listing.item(), deliverAt)) {
            throw new IllegalStateException("Prevalidated buyer delivery queue rejected a market item");
        }
        if (sellerNet > 0L && !data.schedulePendingMoney(
                listing.sellerId(), sellerNet, deliverAt)) {
            throw new IllegalStateException("Prevalidated seller delivery queue rejected market proceeds");
        }
        data.addHistory(new MarketTransaction(
                transactionId,
                listing.item(),
                listing.price(),
                listing.sellerId(),
                listing.sellerName(),
                buyer.getUUID(),
                buyer.getGameProfile().getName(),
                System.currentTimeMillis(),
                MarketTransaction.Result.SOLD
        ));
        buyer.getInventory().setChanged();
        buyer.containerMenu.broadcastChanges();
        return OperationResult.ok("购买成功；卖家成交手续费 " + saleFee
                + " 枚，物品和卖家结算将在 10 分钟后可领取");
    }

    public static OperationResult cancelListing(ServerPlayer seller, long listingId) {
        requireServerThread(seller.server);
        MarketSavedData data = MarketSavedData.get(seller.server);
        long now = System.currentTimeMillis();
        data.releaseDueDeliveries(now);
        expireListings(seller.server);
        MarketListing listing = data.listing(listingId);
        if (listing == null) {
            return OperationResult.error("该挂单不存在或已结束");
        }
        if (!listing.sellerId().equals(seller.getUUID())) {
            return OperationResult.error("只能撤销自己的挂单");
        }
        if (!data.canSchedulePendingItem(seller.getUUID(), listing.item())) {
            return OperationResult.error("安全邮箱已满或待处理记录过多，请先领取到达的物品");
        }
        long transactionId;
        try {
            transactionId = data.allocateTransactionId();
        } catch (IllegalStateException exhausted) {
            GlobalMarketMod.LOGGER.error("Unable to allocate a market transaction id", exhausted);
            return OperationResult.error("市场历史编号已耗尽，请联系管理员");
        }

        data.removeListing(listingId);
        if (!data.schedulePendingItem(seller.getUUID(), listing.item(), safeDeliveryTime(now))) {
            throw new IllegalStateException("Prevalidated seller delivery queue rejected cancellation return");
        }
        data.addHistory(new MarketTransaction(
                transactionId,
                listing.item(),
                listing.price(),
                listing.sellerId(),
                listing.sellerName(),
                null,
                "",
                System.currentTimeMillis(),
                MarketTransaction.Result.CANCELLED
        ));
        return OperationResult.ok("挂单已撤销，物品将在 10 分钟后进入安全邮箱");
    }

    public static OperationResult adminCopyListingItem(
            ServerPlayer administrator,
            long listingId
    ) {
        requireServerThread(administrator.server);
        if (!hasAdminPermission(administrator)) {
            GlobalMarketMod.LOGGER.warn(
                    "Player {} ({}) attempted to copy market listing {} without permission",
                    administrator.getGameProfile().getName(), administrator.getUUID(), listingId
            );
            return OperationResult.error("没有市场管理权限");
        }

        MarketSavedData data = MarketSavedData.get(administrator.server);
        data.releaseDueDeliveries(System.currentTimeMillis());
        expireListings(administrator.server);
        MarketListing listing = data.listing(listingId);
        if (listing == null) {
            return OperationResult.error("该挂单不存在或已结束");
        }

        PlayerMainInvWrapper inventoryTarget = new PlayerMainInvWrapper(
                administrator.getInventory());
        ItemStack simulatedRemainder = ItemHandlerHelper.insertItemStacked(
                inventoryTarget, listing.item().copy(), true
        );
        if (!simulatedRemainder.isEmpty()) {
            return OperationResult.error("管理员背包空间不足，无法获取完整副本");
        }

        long transactionId;
        try {
            transactionId = data.allocateTransactionId();
        } catch (IllegalStateException exhausted) {
            GlobalMarketMod.LOGGER.error(
                    "Unable to allocate an administrator copy transaction id", exhausted
            );
            return OperationResult.error("市场历史编号已耗尽，请联系管理员");
        }

        ItemStack actualRemainder = ItemHandlerHelper.insertItemStacked(
                inventoryTarget, listing.item().copy(), false
        );
        if (!actualRemainder.isEmpty()) {
            throw new IllegalStateException(
                    "Prevalidated administrator inventory rejected a market item copy");
        }
        data.addHistory(adminTransaction(
                transactionId, listing, administrator,
                System.currentTimeMillis(), MarketTransaction.Result.ADMIN_COPIED
        ));
        administrator.getInventory().setChanged();
        administrator.containerMenu.broadcastChanges();
        logAdminAction("copied", administrator, listing);
        return OperationResult.ok("已获取挂单 #" + listing.id() + " 的副本，原挂单保持不变");
    }

    public static OperationResult adminRemoveListing(
            ServerPlayer administrator,
            long listingId
    ) {
        requireServerThread(administrator.server);
        if (!hasAdminPermission(administrator)) {
            GlobalMarketMod.LOGGER.warn(
                    "Player {} ({}) attempted to remove market listing {} without permission",
                    administrator.getGameProfile().getName(), administrator.getUUID(), listingId
            );
            return OperationResult.error("没有市场管理权限");
        }

        MarketSavedData data = MarketSavedData.get(administrator.server);
        data.releaseDueDeliveries(System.currentTimeMillis());
        expireListings(administrator.server);
        MarketListing listing = data.listing(listingId);
        if (listing == null) {
            return OperationResult.error("该挂单不存在或已结束");
        }

        long transactionId;
        try {
            transactionId = data.allocateTransactionId();
        } catch (IllegalStateException exhausted) {
            GlobalMarketMod.LOGGER.error(
                    "Unable to allocate an administrator removal transaction id", exhausted
            );
            return OperationResult.error("市场历史编号已耗尽，请联系管理员");
        }

        MarketListing removed = data.removeListing(listingId);
        if (removed != listing) {
            throw new IllegalStateException(
                    "Market listing changed during an administrator removal");
        }
        // Moderation must work even if the seller filled the normal mailbox.
        // The original escrow therefore returns through the recovery path.
        data.addAdministrativeReturn(listing.sellerId(), listing.item());
        data.addHistory(adminTransaction(
                transactionId, listing, administrator,
                System.currentTimeMillis(), MarketTransaction.Result.ADMIN_REMOVED
        ));
        notifySellerOfAdminAction(administrator, listing, false);
        logAdminAction("removed and returned", administrator, listing);
        return OperationResult.ok("已下架挂单 #" + listing.id() + "，原物已退回卖家安全邮箱");
    }

    public static OperationResult adminDeleteListing(
            ServerPlayer administrator,
            long listingId
    ) {
        requireServerThread(administrator.server);
        if (!hasAdminPermission(administrator)) {
            GlobalMarketMod.LOGGER.warn(
                    "Player {} ({}) attempted to delete market listing {} without permission",
                    administrator.getGameProfile().getName(), administrator.getUUID(), listingId
            );
            return OperationResult.error("没有市场管理权限");
        }

        MarketSavedData data = MarketSavedData.get(administrator.server);
        data.releaseDueDeliveries(System.currentTimeMillis());
        expireListings(administrator.server);
        MarketListing listing = data.listing(listingId);
        if (listing == null) {
            return OperationResult.error("该挂单不存在或已结束");
        }

        long transactionId;
        try {
            transactionId = data.allocateTransactionId();
        } catch (IllegalStateException exhausted) {
            GlobalMarketMod.LOGGER.error(
                    "Unable to allocate an administrator deletion transaction id", exhausted
            );
            return OperationResult.error("市场历史编号已耗尽，请联系管理员");
        }

        MarketListing removed = data.removeListing(listingId);
        if (removed != listing) {
            throw new IllegalStateException(
                    "Market listing changed during an administrator deletion");
        }
        // Intentionally do not return the escrow. Keep only a server-created
        // marker without the original NBT so the deleted stack is never rendered again.
        data.addHistory(adminTransaction(
                transactionId, listing, administrator,
                System.currentTimeMillis(), MarketTransaction.Result.ADMIN_DELETED
        ));
        notifySellerOfAdminAction(administrator, listing, true);
        logAdminAction("permanently deleted", administrator, listing);
        return OperationResult.ok("已永久删除异常挂单 #" + listing.id() + "，物品未退回卖家");
    }

    public static OperationResult claimMailbox(ServerPlayer player) {
        requireServerThread(player.server);
        MarketSavedData data = MarketSavedData.get(player.server);
        UUID playerId = player.getUUID();
        data.releaseDueDeliveries(System.currentTimeMillis());
        long insertedItems = 0L;
        PlayerMainInvWrapper inventoryTarget = new PlayerMainInvWrapper(player.getInventory());

        List<ItemStack> mailbox = data.pendingItems(playerId);
        Iterator<ItemStack> iterator = mailbox.iterator();
        int inspectedStacks = 0;
        while (iterator.hasNext()
                && inspectedStacks++ < MarketConstants.MAX_MAILBOX_STACKS) {
            ItemStack stored = iterator.next();
            int before = stored.getCount();
            ItemStack remaining = ItemHandlerHelper.insertItemStacked(
                    inventoryTarget, stored.copy(), false
            );
            int inserted = before - remaining.getCount();
            if (inserted <= 0) {
                continue;
            }
            insertedItems += inserted;
            if (remaining.isEmpty()) {
                iterator.remove();
            } else {
                stored.setCount(remaining.getCount());
            }
            data.setDirty();
        }
        data.removeMailboxIfEmpty(playerId);

        long insertedMoney = 0L;
        long pendingMoney = data.pendingMoney(playerId);
        Item money = moneyItem();
        String currencyProblem = pendingMoney > 0L ? currencyProblem(money) : null;
        if (pendingMoney > 0L && money != null) {
            if (currencyProblem != null) {
                return OperationResult.error(currencyProblem + "；待结算货币仍安全保留");
            }
            int maxStack = Math.max(1, new ItemStack(money).getMaxStackSize());
            while (pendingMoney > 0L) {
                int offered = (int) Math.min(pendingMoney, (long) maxStack);
                ItemStack cash = new ItemStack(money, offered);
                ItemStack remainingCash = ItemHandlerHelper.insertItemStacked(
                        inventoryTarget, cash, false
                );
                int inserted = offered - remainingCash.getCount();
                if (inserted <= 0) {
                    break;
                }
                pendingMoney -= inserted;
                insertedMoney += inserted;
            }
            data.setPendingMoney(playerId, pendingMoney);
        }

        if (insertedItems > 0L || insertedMoney > 0L) {
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
            if (currencyProblem != null && data.pendingMoney(playerId) > 0L) {
                return OperationResult.ok(
                        "已领取 " + insertedItems
                                + " 件物品；caf:money 未注册，待结算货币仍安全保留"
                );
            }
            return OperationResult.ok(
                    "已领取 " + insertedItems + " 件物品和 " + insertedMoney + " 枚货币"
            );
        }
        if (data.pendingItemCount(playerId) > 0 || data.pendingMoney(playerId) > 0L) {
            if (currencyProblem != null && data.pendingMoney(playerId) > 0L) {
                return OperationResult.error("caf:money 未注册，物品已领取但货币仍安全保留");
            }
            return OperationResult.error("背包空间不足，待领取内容仍安全保留");
        }
        if (data.inTransitItemCount(playerId) > 0 || data.inTransitMoney(playerId) > 0L) {
            return OperationResult.error("暂无可领取内容，仍有运输中的物品或结算款项");
        }
        return OperationResult.error("没有待领取内容");
    }

    public static MarketSnapshotS2C snapshot(ServerPlayer player, MarketQuery requestedQuery) {
        requireServerThread(player.server);
        MarketSavedData data = MarketSavedData.get(player.server);
        data.releaseDueDeliveries(System.currentTimeMillis());
        expireListings(player.server);
        MarketQuery query = requestedQuery == null ? MarketQuery.defaults() : requestedQuery;
        UUID playerId = player.getUUID();
        String search = query.normalizedSearch();

        List<MarketListing> base = new ArrayList<>();
        for (MarketListing listing : data.listings()) {
            if (listing.isExpired(System.currentTimeMillis())) {
                continue;
            }
            if (query.view() == MarketView.MINE && !listing.sellerId().equals(playerId)) {
                continue;
            }
            if (!search.isEmpty() && !matchesSearch(listing, search)) {
                continue;
            }
            base.add(listing);
        }

        int[] categoryCounts = new int[MarketCategory.values().length];
        categoryCounts[MarketCategory.ALL.ordinal()] = base.size();
        for (MarketListing listing : base) {
            MarketCategory category = MarketCategory.classify(listing.item());
            categoryCounts[category.ordinal()]++;
        }

        List<MarketListing> filtered = new ArrayList<>(base.size());
        for (MarketListing listing : base) {
            if (query.category().matches(listing.item())) {
                filtered.add(listing);
            }
        }
        filtered.sort(comparator(query.sort()));

        int totalMatches = filtered.size();
        int totalPages = (int) Math.max(
                1L,
                ((long) totalMatches + MarketConstants.PAGE_SIZE - 1L)
                        / MarketConstants.PAGE_SIZE
        );
        int page = Math.min(query.page(), totalPages - 1);
        int from = (int) ((long) page * MarketConstants.PAGE_SIZE);
        int to = (int) Math.min(
                (long) totalMatches,
                (long) from + MarketConstants.PAGE_SIZE
        );
        List<MarketListing> pageListings = new ArrayList<>(filtered.subList(from, to));
        MarketQuery effectiveQuery = new MarketQuery(
                query.view(), query.category(), query.sort(), query.search(), page
        );

        return new MarketSnapshotS2C(
                effectiveQuery,
                page,
                totalPages,
                totalMatches,
                pageListings,
                countMoney(player, moneyItem()),
                data.pendingMoney(playerId),
                data.pendingItemCount(playerId),
                data.inTransitItemCount(playerId),
                data.inTransitMoney(playerId),
                data.nextDeliveryAt(playerId),
                data.listingAttemptsUsed(playerId, System.currentTimeMillis()),
                data.listingAttemptsRemaining(playerId, System.currentTimeMillis()),
                data.listingQuotaResetAt(playerId, System.currentTimeMillis()),
                categoryCounts,
                data.recentHistory(playerId, 50)
        );
    }

    public static int expireListings(MinecraftServer server) {
        requireServerThread(server);
        MarketSavedData data = MarketSavedData.get(server);
        long now = System.currentTimeMillis();
        data.releaseDueDeliveries(now);
        List<MarketListing> expired = data.listings().stream()
                .filter(listing -> listing.isExpired(now))
                .toList();
        int count = 0;
        for (MarketListing listing : expired) {
            if (!data.canSchedulePendingItem(listing.sellerId(), listing.item())) {
                GlobalMarketMod.LOGGER.debug(
                        "Cannot expire market listing {} because seller {} mailbox is full",
                        listing.id(),
                        listing.sellerId()
                );
                continue;
            }
            long transactionId;
            try {
                transactionId = data.allocateTransactionId();
            } catch (IllegalStateException exhaustedIds) {
                GlobalMarketMod.LOGGER.error(
                        "Unable to allocate a transaction id while expiring listing {}",
                        listing.id(),
                        exhaustedIds
                );
                continue;
            }
            data.removeListing(listing.id());
            if (!data.schedulePendingItem(
                    listing.sellerId(), listing.item(), safeDeliveryTime(now))) {
                throw new IllegalStateException("Prevalidated expiry delivery queue rejected listing item");
            }
            data.addHistory(new MarketTransaction(
                    transactionId,
                    listing.item(),
                    listing.price(),
                    listing.sellerId(),
                    listing.sellerName(),
                    null,
                    "",
                    now,
                    MarketTransaction.Result.EXPIRED
            ));
            count++;
        }
        return count;
    }

    /** Called periodically by the server tick handler to release due queues. */
    public static void processDeliveries(MinecraftServer server) {
        requireServerThread(server);
        MarketSavedData.get(server).releaseDueDeliveries(System.currentTimeMillis());
    }

    public static long listingFee(long price) {
        if (price <= 0L) {
            return 0L;
        }
        return Math.max(MarketConstants.MIN_LISTING_FEE,
                percentageCeiling(price, MarketConstants.LISTING_FEE_PERCENT));
    }

    public static long saleFee(long price) {
        if (price <= 0L) {
            return 0L;
        }
        return Math.min(price, percentageCeiling(price, MarketConstants.SALE_FEE_PERCENT));
    }

    public static long sellerNet(long price) {
        return Math.max(0L, price - saleFee(price));
    }

    public static boolean isMoneyAvailable() {
        return currencyProblem(moneyItem()) == null;
    }

    public static long countMoney(ServerPlayer player) {
        return countMoney(player, moneyItem());
    }

    private static Item moneyItem() {
        if (!ForgeRegistries.ITEMS.containsKey(MarketConstants.MONEY_ID)) {
            return null;
        }
        Item item = ForgeRegistries.ITEMS.getValue(MarketConstants.MONEY_ID);
        return item == null || item == Items.AIR ? null : item;
    }

    private static long countMoney(ServerPlayer player, Item money) {
        if (money == null) {
            return 0L;
        }
        long total = 0L;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(money)) {
                total = total > Long.MAX_VALUE - stack.getCount()
                        ? Long.MAX_VALUE
                        : total + stack.getCount();
            }
        }
        return total;
    }

    private static void deductMoney(ServerPlayer player, Item money, long amount) {
        long remaining = amount;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0L; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.is(money)) {
                continue;
            }
            int taken = (int) Math.min(remaining, (long) stack.getCount());
            stack.shrink(taken);
            remaining -= taken;
            if (stack.isEmpty()) {
                inventory.setItem(slot, ItemStack.EMPTY);
            }
        }
        if (remaining != 0L) {
            throw new IllegalStateException("Prevalidated market payment could not be deducted");
        }
    }

    private static boolean matchesSearch(MarketListing listing, String search) {
        ItemStack item = listing.item();
        String displayName = item.getHoverName().getString().toLowerCase(Locale.ROOT);
        if (displayName.contains(search)) {
            return true;
        }
        var itemId = ForgeRegistries.ITEMS.getKey(item.getItem());
        if (itemId != null && itemId.toString().toLowerCase(Locale.ROOT).contains(search)) {
            return true;
        }
        return listing.sellerName().toLowerCase(Locale.ROOT).contains(search);
    }

    private static Comparator<MarketListing> comparator(MarketSort sort) {
        Comparator<MarketListing> newestFirst = Comparator
                .comparingLong(MarketListing::createdAt)
                .reversed()
                .thenComparing(Comparator.comparingLong(MarketListing::id).reversed());
        return switch (sort) {
            case PRICE_ASC -> Comparator.comparingLong(MarketListing::price)
                    .thenComparing(newestFirst);
            case PRICE_DESC -> Comparator.comparingLong(MarketListing::price)
                    .reversed()
                    .thenComparing(newestFirst);
            case OLDEST -> Comparator.comparingLong(MarketListing::expiresAt)
                    .thenComparingLong(MarketListing::id);
            case NEWEST -> newestFirst;
        };
    }

    private static String currencyProblem(Item money) {
        if (money == null) {
            return "市场货币 caf:money 未注册，交易暂不可用";
        }
        int maxStackSize = new ItemStack(money).getMaxStackSize();
        if (maxStackSize < 128) {
            return null;
        }
        ItemStack probe = new ItemStack(money, 128);
        String itemProblem = MarketItemCodec.validationProblem(probe);
        if (itemProblem != null) {
            return "caf:money 的 BigCount/NBT 编码不可用，交易暂不可用";
        }
        String platformProblem = MarketItemCodec.platformCountRoundTripProblem(probe);
        if (platformProblem != null) {
            return platformProblem + "，交易暂不可用";
        }
        return null;
    }

    private static long percentageCeiling(long amount, int percent) {
        if (amount <= 0L || percent <= 0) {
            return 0L;
        }
        long whole = amount / 100L;
        long remainder = amount % 100L;
        long result = whole * percent;
        long remainderFee = (remainder * percent + 99L) / 100L;
        return result > Long.MAX_VALUE - remainderFee
                ? Long.MAX_VALUE
                : result + remainderFee;
    }

    private static long safeDeliveryTime(long now) {
        return now > Long.MAX_VALUE - MarketConstants.DELIVERY_DELAY_MILLIS
                ? Long.MAX_VALUE
                : now + MarketConstants.DELIVERY_DELAY_MILLIS;
    }

    private static String formatTimeUntil(long target, long now) {
        if (target <= 0L || target <= now) {
            return "稍后";
        }
        long seconds = Math.max(1L, (target - now + 999L) / 1_000L);
        long minutes = (seconds + 59L) / 60L;
        if (minutes < 60L) {
            return minutes + " 分钟后";
        }
        return ((minutes + 59L) / 60L) + " 小时后";
    }

    private static boolean hasAdminPermission(ServerPlayer player) {
        return player.createCommandSourceStack().hasPermission(
                MarketConstants.ADMIN_PERMISSION_LEVEL);
    }

    private static MarketTransaction adminTransaction(
            long transactionId,
            MarketListing listing,
            ServerPlayer administrator,
            long timestamp,
            MarketTransaction.Result result
    ) {
        ItemStack auditItem = result == MarketTransaction.Result.ADMIN_DELETED
                ? deletedItemAuditMarker(listing)
                : listing.item();
        return new MarketTransaction(
                transactionId,
                auditItem,
                listing.price(),
                listing.sellerId(),
                listing.sellerName(),
                administrator.getUUID(),
                administrator.getGameProfile().getName(),
                timestamp,
                result
        );
    }

    private static ItemStack deletedItemAuditMarker(MarketListing listing) {
        var itemId = ForgeRegistries.ITEMS.getKey(listing.item().getItem());
        ItemStack marker = new ItemStack(Items.BARRIER, listing.item().getCount());
        marker.setHoverName(Component.literal("已销毁："
                + (itemId == null ? "unknown" : itemId.toString())));
        return marker;
    }

    private static void notifySellerOfAdminAction(
            ServerPlayer administrator,
            MarketListing listing,
            boolean deleted
    ) {
        ServerPlayer seller = administrator.server.getPlayerList().getPlayer(
                listing.sellerId());
        if (seller == null || seller.getUUID().equals(administrator.getUUID())) {
            return;
        }
        String message = deleted
                ? "管理员已永久删除异常挂单 #" + listing.id() + "，物品未退回"
                : "管理员已下架挂单 #" + listing.id() + "，原物已退回安全邮箱";
        MarketNetwork.sendNotice(seller, !deleted, message);
        MarketNetwork.refresh(seller);
    }

    private static void logAdminAction(
            String action,
            ServerPlayer administrator,
            MarketListing listing
    ) {
        var itemId = ForgeRegistries.ITEMS.getKey(listing.item().getItem());
        GlobalMarketMod.LOGGER.warn(
                "Market administrator action: action={}, administrator={} ({}), "
                        + "listing={}, seller={} ({}), item={}, count={}, nbtBytes={}",
                action,
                administrator.getGameProfile().getName(),
                administrator.getUUID(),
                listing.id(),
                listing.sellerName(),
                listing.sellerId(),
                itemId == null ? "unknown" : itemId,
                listing.item().getCount(),
                MarketItemCodec.uncompressedPersistentBytes(listing.item())
        );
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Market mutation attempted off the server thread");
        }
    }

    public record OperationResult(boolean success, String message) {
        public static OperationResult ok(String message) {
            return new OperationResult(true, message);
        }

        public static OperationResult error(String message) {
            return new OperationResult(false, message);
        }
    }

}
