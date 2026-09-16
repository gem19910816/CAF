package com.gearsandflesh.market.client;

import com.gearsandflesh.market.MarketConstants;
import com.gearsandflesh.market.data.MarketCategory;
import com.gearsandflesh.market.data.MarketListing;
import com.gearsandflesh.market.data.MarketQuery;
import com.gearsandflesh.market.data.MarketSort;
import com.gearsandflesh.market.data.MarketTransaction;
import com.gearsandflesh.market.data.MarketView;
import com.gearsandflesh.market.network.AdminListingActionC2S;
import com.gearsandflesh.market.network.BuyListingC2S;
import com.gearsandflesh.market.network.CancelListingC2S;
import com.gearsandflesh.market.network.ClaimMailboxC2S;
import com.gearsandflesh.market.network.CreateListingC2S;
import com.gearsandflesh.market.network.MarketNetwork;
import com.gearsandflesh.market.network.MarketNoticeS2C;
import com.gearsandflesh.market.network.MarketSnapshotS2C;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MarketScreen extends Screen {
    private static final int BACKDROP = 0x75000000;
    private static final int PANEL = 0xEE0D1A22;
    private static final int PANEL_SOFT = 0xDD11232D;
    private static final int CELL = 0xB8152731;
    private static final int CELL_HOVER = 0xE11B3441;
    private static final int BORDER = 0xFF37596A;
    private static final int BORDER_DIM = 0xFF29434F;
    private static final int ACCENT = 0xFF19799B;
    private static final int ACCENT_HOVER = 0xFF238EB2;
    private static final int TEXT = 0xFFE8F1F4;
    private static final int MUTED = 0xFF9CB0B9;
    private static final int GOLD = 0xFFF4CE59;
    private static final int GREEN = 0xFF63D8A3;
    private static final int RED = 0xFFDF6C73;
    private static final int FOOTER_HEIGHT = 20;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final List<ClickRegion> clickRegions = new ArrayList<>();
    private final List<ListingRegion> listingRegions = new ArrayList<>();
    private final boolean adminMode;
    private MarketSnapshotS2C snapshot;
    private MarketQuery query;
    private Tab tab = Tab.GLOBAL;
    private EditBox searchBox;
    private EditBox countBox;
    private EditBox priceBox;
    private MarketCategory selectedCategory = MarketCategory.ALL;
    private MarketSort selectedSort = MarketSort.NEWEST;
    private int selectedRarity;
    private int selectedSlot = -1;
    private int historyPage;
    private boolean raritySidebar;
    private boolean compactPrices = true;
    private boolean detailedTooltips = true;
    private Confirmation confirmation;
    private AdminMenu adminMenu;
    private String notice = "";
    private int noticeColor = MUTED;
    private long noticeUntil;

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int contentTop;
    private int contentBottom;
    private int sidebarWidth;

    public MarketScreen() {
        this(false);
    }

    public MarketScreen(boolean adminMode) {
        super(Component.literal(adminMode ? "全球市场 - 管理模式" : "全球市场"));
        this.adminMode = adminMode;
        snapshot = ClientMarketState.snapshot();
        query = snapshot == null ? MarketQuery.defaults() : snapshot.query();
        selectedCategory = query.category();
        selectedSort = query.sort();
        MarketNoticeS2C previous = ClientMarketState.lastNotice();
        if (previous != null) {
            notice = previous.message();
            noticeColor = previous.success() ? GREEN : RED;
        }
    }

    @Override
    protected void init() {
        String oldSearch = searchBox == null ? query.search() : searchBox.getValue();
        String oldCount = countBox == null ? "1" : countBox.getValue();
        String oldPrice = priceBox == null ? "1000" : priceBox.getValue();
        calculateLayout();

        searchBox = new EditBox(font, panelX + 8, contentTop + 3,
                Math.max(54, sidebarWidth - 16), 18, Component.literal("搜索商品"));
        searchBox.setMaxLength(64);
        searchBox.setValue(oldSearch);
        searchBox.setHint(Component.literal("搜索商品").withStyle(ChatFormatting.DARK_GRAY));
        searchBox.setBordered(false);
        addRenderableWidget(searchBox);

        int detailX = sellDetailX();
        int fieldWidth = Math.max(72, panelX + panelWidth - 14 - detailX);
        countBox = new EditBox(font, detailX, contentTop + 58, fieldWidth, 18,
                Component.literal("上架数量"));
        countBox.setMaxLength(6);
        countBox.setFilter(value -> value.isEmpty() || value.chars().allMatch(Character::isDigit));
        countBox.setValue(oldCount);
        addRenderableWidget(countBox);

        priceBox = new EditBox(font, detailX, contentTop + 93, fieldWidth, 18,
                Component.literal("上架价格"));
        priceBox.setMaxLength(10);
        priceBox.setFilter(value -> value.isEmpty() || value.chars().allMatch(Character::isDigit));
        priceBox.setValue(oldPrice);
        addRenderableWidget(priceBox);
        updateWidgetVisibility();
    }

    public void requestInitialSnapshot() {
        query = MarketQuery.defaults();
        selectedCategory = query.category();
        selectedSort = query.sort();
        ClientMarketState.query(query);
    }

    public void applySnapshot(MarketSnapshotS2C message) {
        snapshot = message;
        query = message.query();
        selectedCategory = query.category();
        selectedSort = query.sort();
        if (searchBox != null && !searchBox.isFocused()) {
            searchBox.setValue(query.search());
        }
    }

    public void showNotice(MarketNoticeS2C message) {
        adminMenu = null;
        notice = message.message();
        noticeColor = message.success() ? GREEN : RED;
        noticeUntil = System.currentTimeMillis() + 5_000L;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        calculateLayout();
        clickRegions.clear();
        listingRegions.clear();
        graphics.fill(0, 0, width, height, BACKDROP);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL);
        outline(graphics, panelX, panelY, panelWidth, panelHeight, adminMode ? RED : BORDER);
        if (adminMode) {
            graphics.fill(panelX + 1, panelY + 1, panelX + panelWidth - 1, panelY + 3, RED);
        }
        renderHeader(graphics, mouseX, mouseY);

        switch (tab) {
            case MINE -> renderListingsPage(graphics, mouseX, mouseY, true, false);
            case GLOBAL -> renderListingsPage(graphics, mouseX, mouseY, false, false);
            case BUY -> renderListingsPage(graphics, mouseX, mouseY, false, true);
            case SELL -> renderSell(graphics, mouseX, mouseY);
            case MERCHANT -> renderMerchant(graphics, mouseX, mouseY);
            case HISTORY -> renderHistory(graphics, mouseX, mouseY);
            case SETTINGS -> renderSettings(graphics, mouseX, mouseY);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
        renderNotice(graphics);

        if (confirmation != null) {
            clickRegions.clear();
            renderConfirmation(graphics, mouseX, mouseY);
        } else if (adminMenu != null) {
            clickRegions.clear();
            renderAdminMenu(graphics, mouseX, mouseY);
            renderHoveredTooltip(graphics, mouseX, mouseY);
        } else {
            renderHoveredTooltip(graphics, mouseX, mouseY);
        }
    }

    private void renderHeader(GuiGraphics graphics, int mouseX, int mouseY) {
        int y = panelY + 5;
        int h = 23;
        int left = panelX + 6;
        int closeWidth = 19;
        int moneyWidth = Math.min(76, Math.max(54, panelWidth / 9));
        int right = panelX + panelWidth - 6;
        int closeX = right - closeWidth;
        int moneyX = closeX - 4 - moneyWidth;
        int tabSpace = Math.max(Tab.values().length, moneyX - 4 - left);
        int tabWidth = Math.max(1, tabSpace / Tab.values().length);
        int extraPixels = Math.max(0, tabSpace - tabWidth * Tab.values().length);

        int x = left;
        for (int index = 0; index < Tab.values().length; index++) {
            Tab value = Tab.values()[index];
            int actualWidth = tabWidth + (index < extraPixels ? 1 : 0);
            boolean active = value == tab;
            button(graphics, x, y, actualWidth - 2, h, active, contains(x, y, actualWidth - 2, h, mouseX, mouseY));
            centeredTrimmed(graphics, value.label, x + 2, y, actualWidth - 6, h,
                    active ? TEXT : 0xFFD2DEE2);
            addClick(x, y, actualWidth - 2, h, () -> changeTab(value), null);
            x += actualWidth;
        }

        box(graphics, moneyX, y, moneyWidth, h, PANEL_SOFT, BORDER);
        Item money = ForgeRegistries.ITEMS.getValue(MarketConstants.MONEY_ID);
        int textX = moneyX + 5;
        if (money != null) {
            graphics.renderItem(new ItemStack(money), moneyX + 3, y + 3);
            textX += 17;
        } else {
            graphics.fill(moneyX + 5, y + 10, moneyX + 9, y + 14, GOLD);
            textX += 9;
        }
        graphics.drawString(font, trim(formatMoney(cash()), moneyWidth - (textX - moneyX) - 3),
                textX, y + 8, GOLD, false);

        boolean closeHover = contains(closeX, y, closeWidth, h, mouseX, mouseY);
        box(graphics, closeX, y, closeWidth, h, closeHover ? 0xFF4B252D : PANEL_SOFT, BORDER);
        centered(graphics, "X", closeX, y, closeWidth, h, RED);
        addClick(closeX, y, closeWidth, h, this::onClose,
                List.of(Component.literal("关闭市场")));
    }

    private void renderListingsPage(GuiGraphics graphics, int mouseX, int mouseY,
                                    boolean mine, boolean buyFocused) {
        renderSidebar(graphics, mouseX, mouseY);
        int mainX = panelX + sidebarWidth + 7;
        int mainRight = panelX + panelWidth - 7;
        int top = contentTop + 1;

        if (mine) {
            int claimWidth = Math.max(60, (mainRight - mainX) / 2);
            box(graphics, mainX, top, mainRight - mainX - claimWidth - 3, 25, PANEL_SOFT, BORDER_DIM);
            graphics.drawString(font, trim("我的上架 " + listings().size(),
                    mainRight - mainX - claimWidth - 10), mainX + 6, top + 8, TEXT, false);
            boolean canClaim = pendingItems() > 0 || pendingMoney() > 0L;
            int claimX = mainRight - claimWidth;
            button(graphics, claimX, top, claimWidth, 25, canClaim,
                    canClaim && contains(claimX, top, claimWidth, 25, mouseX, mouseY));
            String claimText = canClaim
                    ? "待领 " + pendingItems() + " / " + formatMoney(pendingMoney())
                    : (inTransitItems() > 0 || inTransitMoney() > 0L
                    ? "运输 " + inTransitItems() + " / " + formatMoney(inTransitMoney())
                            + " · " + nextDeliveryRemaining()
                    : "暂无待领取");
            centeredTrimmed(graphics, claimText, claimX + 3, top, claimWidth - 6, 25,
                    canClaim ? GREEN : MUTED);
            if (canClaim) {
                addClick(claimX, top, claimWidth, 25, this::claimMailbox,
                        List.of(Component.literal("领取已售款项和退回物品")));
            }
            top += 30;
        } else {
            String heading = buyFocused ? "最低价优先" : "全球挂单";
            graphics.drawString(font, heading, mainX + 2, top + 3, TEXT, false);
            String summary = totalMatches() + " 件 · " + selectedSort.displayName();
            graphics.drawString(font, trim(summary, Math.max(20, mainRight - mainX - 90)),
                    Math.max(mainX + 70, mainRight - font.width(trim(summary, mainRight - mainX - 90)) - 2),
                    top + 3, MUTED, false);
            graphics.fill(mainX, top + 15, mainRight, top + 16, BORDER_DIM);
            top += 20;
        }

        List<MarketListing> visible = visibleListings();
        int footerY = contentBottom - FOOTER_HEIGHT;
        int gap = 4;
        int gridWidth = mainRight - mainX;
        int cardWidth = Math.max(1, (gridWidth - gap * 2) / 3);
        int cardHeight = Math.max(38, (footerY - top - gap * 2) / 3);
        for (int i = 0; i < Math.min(9, visible.size()); i++) {
            MarketListing listing = visible.get(i);
            int col = i % 3;
            int row = i / 3;
            int x = mainX + col * (cardWidth + gap);
            int y = top + row * (cardHeight + gap);
            renderListingCard(graphics, listing, x, y, cardWidth, cardHeight, mouseX, mouseY, mine);
        }
        if (visible.isEmpty()) {
            String empty = selectedRarity > 0 ? "当前页没有该等级商品" : "没有找到商品";
            centered(graphics, empty, mainX, top, gridWidth, Math.max(30, footerY - top), MUTED);
        }
        renderPagination(graphics, mainX, footerY, gridWidth, mouseX, mouseY);
    }

    private void renderSidebar(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = panelX + 6;
        int y = contentTop;
        int w = sidebarWidth - 2;
        graphics.fill(x, y, x + w, contentBottom, PANEL_SOFT);
        outline(graphics, x, y, w, contentBottom - y, BORDER_DIM);
        outline(graphics, x + 5, y + 1, w - 10, 20, BORDER);

        int modeY = y + 25;
        int half = (w - 10) / 2;
        button(graphics, x + 5, modeY, half, 18, !raritySidebar,
                contains(x + 5, modeY, half, 18, mouseX, mouseY));
        centered(graphics, "分类", x + 5, modeY, half, 18, TEXT);
        addClick(x + 5, modeY, half, 18, () -> raritySidebar = false, null);
        button(graphics, x + 6 + half, modeY, w - 11 - half, 18, raritySidebar,
                contains(x + 6 + half, modeY, w - 11 - half, 18, mouseX, mouseY));
        centered(graphics, "等级", x + 6 + half, modeY, w - 11 - half, 18, TEXT);
        addClick(x + 6 + half, modeY, w - 11 - half, 18, () -> raritySidebar = true,
                List.of(Component.literal("按 Rarity Core 等级筛选当前页")));

        int listY = modeY + 22;
        int sortY = contentBottom - 21;
        int rows = 8;
        int rowHeight = Math.max(13, Math.min(18, (sortY - listY - 2) / rows));
        if (raritySidebar) {
            renderRaritySidebar(graphics, mouseX, mouseY, x + 5, listY, w - 10, rowHeight);
        } else {
            int[] counts = snapshot == null ? new int[MarketCategory.values().length] : snapshot.categoryCounts();
            MarketCategory[] categories = MarketCategory.values();
            for (int i = 0; i < categories.length; i++) {
                MarketCategory category = categories[i];
                int rowY = listY + i * rowHeight;
                boolean active = selectedCategory == category;
                boolean hover = contains(x + 5, rowY, w - 10, rowHeight - 1, mouseX, mouseY);
                if (active || hover) {
                    graphics.fill(x + 5, rowY, x + w - 5, rowY + rowHeight - 1,
                            active ? ACCENT : CELL_HOVER);
                }
                graphics.drawString(font, category.displayName(), x + 9, rowY + centeredTextY(rowY, rowHeight),
                        active ? TEXT : 0xFFC7D4D9, false);
                String count = i < counts.length ? Integer.toString(counts[i]) : "0";
                graphics.drawString(font, count, x + w - 9 - font.width(count),
                        rowY + centeredTextY(rowY, rowHeight), active ? GOLD : MUTED, false);
                addClick(x + 5, rowY, w - 10, rowHeight - 1, () -> setCategory(category), null);
            }
        }

        boolean sortHover = contains(x + 5, sortY, w - 10, 17, mouseX, mouseY);
        box(graphics, x + 5, sortY, w - 10, 17, sortHover ? CELL_HOVER : CELL, BORDER_DIM);
        centeredTrimmed(graphics, selectedSort.displayName(), x + 8, sortY, w - 16, 17, GOLD);
        addClick(x + 5, sortY, w - 10, 17, this::cycleSort,
                List.of(Component.literal("点击切换排序")));
    }

    private void renderRaritySidebar(GuiGraphics graphics, int mouseX, int mouseY,
                                     int x, int y, int w, int rowHeight) {
        int[] counts = new int[8];
        for (MarketListing listing : listings()) {
            counts[RarityCompat.rarity(listing.item())]++;
        }
        for (int index = 0; index < 8; index++) {
            final int rarity = index;
            int rowY = y + index * rowHeight;
            boolean active = selectedRarity == rarity;
            boolean hover = contains(x, rowY, w, rowHeight - 1, mouseX, mouseY);
            if (active || hover) {
                graphics.fill(x, rowY, x + w, rowY + rowHeight - 1, active ? ACCENT : CELL_HOVER);
            }
            int color = rarity == 0 ? TEXT : RarityCompat.color(rarity);
            String label = rarity == 0 ? "全部等级" : chineseRarity(rarity);
            graphics.drawString(font, label, x + 4, rowY + centeredTextY(rowY, rowHeight), color, false);
            int count = rarity == 0 ? listings().size() : counts[rarity];
            String countText = Integer.toString(count);
            graphics.drawString(font, countText, x + w - 4 - font.width(countText),
                    rowY + centeredTextY(rowY, rowHeight), MUTED, false);
            addClick(x, rowY, w, rowHeight - 1, () -> selectedRarity = rarity, null);
        }
    }

    private void renderListingCard(GuiGraphics graphics, MarketListing listing,
                                   int x, int y, int w, int h, int mouseX, int mouseY,
                                   boolean mine) {
        boolean hover = contains(x, y, w, h, mouseX, mouseY);
        box(graphics, x, y, w, h, hover ? CELL_HOVER : CELL, hover ? BORDER : BORDER_DIM);
        int rarity = RarityCompat.rarity(listing.item());
        int rarityColor = RarityCompat.color(rarity);
        graphics.fill(x + 1, y + 1, x + 3, y + h - 1, rarityColor);
        graphics.drawString(font, chineseRarity(rarity), x + 6, y + 4, rarityColor, false);
        graphics.renderItem(listing.item(), x + 6, y + 16);
        graphics.renderItemDecorations(font, listing.item(), x + 6, y + 16);

        int nameX = x + 27;
        int textWidth = Math.max(8, w - (nameX - x) - 5);
        graphics.drawString(font, trim(listing.item().getHoverName().getString(), textWidth),
                nameX, y + 17, TEXT, false);
        if (h >= 51) {
            String secondary = mine ? remaining(listing.expiresAt()) : listing.sellerName();
            graphics.drawString(font, trim(secondary, textWidth), nameX, y + 29, MUTED, false);
        }
        String price = formatMoney(listing.price());
        graphics.drawString(font, price, x + w - 5 - font.width(price), y + h - 11, GOLD, false);

        Runnable action = mine ? () -> confirmCancel(listing) : () -> confirmBuy(listing);
        addClick(x, y, w, h, action, listingTooltip(listing, mine));
        if (adminMode) {
            listingRegions.add(new ListingRegion(x, y, w, h, listing));
        }
    }

    private void renderPagination(GuiGraphics graphics, int x, int y, int w, int mouseX, int mouseY) {
        int page = snapshot == null ? query.page() : snapshot.page();
        int pages = snapshot == null ? 1 : snapshot.totalPages();
        int buttonWidth = 24;
        int center = x + w / 2;
        boolean back = page > 0;
        boolean next = page + 1 < pages;
        button(graphics, center - 58, y + 1, buttonWidth, 17, back,
                back && contains(center - 58, y + 1, buttonWidth, 17, mouseX, mouseY));
        centered(graphics, "<", center - 58, y + 1, buttonWidth, 17, back ? TEXT : MUTED);
        if (back) {
            addClick(center - 58, y + 1, buttonWidth, 17, () -> setPage(page - 1), null);
        }
        String pageText = (page + 1) + " / " + pages;
        centered(graphics, pageText, center - 31, y + 1, 62, 17, TEXT);
        button(graphics, center + 34, y + 1, buttonWidth, 17, next,
                next && contains(center + 34, y + 1, buttonWidth, 17, mouseX, mouseY));
        centered(graphics, ">", center + 34, y + 1, buttonWidth, 17, next ? TEXT : MUTED);
        if (next) {
            addClick(center + 34, y + 1, buttonWidth, 17, () -> setPage(page + 1), null);
        }
    }

    private void renderSell(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = panelX + 12;
        int y = contentTop + 3;
        graphics.drawString(font, "选择背包物品", x, y, TEXT, false);
        graphics.drawString(font, "服务器将按选定数量安全扣除物品", x, y + 12, MUTED, false);

        Minecraft minecraft = Minecraft.getInstance();
        Inventory inventory = minecraft.player == null ? null : minecraft.player.getInventory();
        int cellSize = sellCellSize();
        int gridY = contentTop + 30;
        if (inventory != null) {
            for (int display = 0; display < 36; display++) {
                int slot = display < 27 ? display + 9 : display - 27;
                int col = display % 9;
                int row = display / 9;
                int cellX = x + col * cellSize;
                int cellY = gridY + row * cellSize;
                boolean selected = slot == selectedSlot;
                boolean hover = contains(cellX, cellY, cellSize - 2, cellSize - 2, mouseX, mouseY);
                box(graphics, cellX, cellY, cellSize - 2, cellSize - 2,
                        selected ? 0xE1235265 : hover ? CELL_HOVER : CELL,
                        selected ? ACCENT_HOVER : BORDER_DIM);
                ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty()) {
                    graphics.renderItem(stack, cellX + Math.max(1, (cellSize - 18) / 2),
                            cellY + Math.max(1, (cellSize - 18) / 2));
                    graphics.renderItemDecorations(font, stack,
                            cellX + Math.max(1, (cellSize - 18) / 2),
                            cellY + Math.max(1, (cellSize - 18) / 2));
                    addClick(cellX, cellY, cellSize - 2, cellSize - 2,
                            () -> selectInventorySlot(slot, stack), itemTooltip(stack));
                }
            }
        }

        int detailX = sellDetailX();
        int detailWidth = panelX + panelWidth - 13 - detailX;
        box(graphics, detailX - 7, contentTop + 3, detailWidth + 7,
                Math.max(145, contentBottom - contentTop - 7), PANEL_SOFT, BORDER_DIM);
        ItemStack selected = selectedStack();
        if (!selected.isEmpty()) {
            graphics.renderItem(selected, detailX, contentTop + 11);
            graphics.renderItemDecorations(font, selected, detailX, contentTop + 11);
            graphics.drawString(font, trim(selected.getHoverName().getString(), detailWidth - 24),
                    detailX + 21, contentTop + 13, TEXT, false);
            graphics.drawString(font, "可用 " + selected.getCount(), detailX + 21, contentTop + 25, MUTED, false);
        } else {
            graphics.drawString(font, "未选择物品", detailX, contentTop + 15, MUTED, false);
        }
        graphics.drawString(font, "数量", detailX, contentTop + 47, MUTED, false);
        graphics.drawString(font, "总价（caf:money）", detailX, contentTop + 82, MUTED, false);
        outline(graphics, countBox.getX() - 2, countBox.getY() - 2, countBox.getWidth() + 4, 20, BORDER);
        outline(graphics, priceBox.getX() - 2, priceBox.getY() - 2, priceBox.getWidth() + 4, 20, BORDER);

        int buttonY = contentTop + 120;
        boolean ready = !selected.isEmpty() && parsePositive(countBox.getValue()) > 0L
                && parsePositive(priceBox.getValue()) > 0L;
        boolean hover = ready && contains(detailX, buttonY, detailWidth, 24, mouseX, mouseY);
        button(graphics, detailX, buttonY, detailWidth, 24, ready, hover);
        centered(graphics, "确认上架", detailX, buttonY, detailWidth, 24, ready ? TEXT : MUTED);
        if (ready) {
            addClick(detailX, buttonY, detailWidth, 24, this::confirmSell, null);
        }
        graphics.drawString(font, trim("上架费 2%（最低 5）；成交费 3%", detailWidth),
                detailX, buttonY + 31, MUTED, false);
        graphics.drawString(font, trim("小时额度 " + listingAttemptsRemaining() + " / "
                        + MarketConstants.MAX_LISTINGS_PER_HOUR
                        + "；挂单上限 " + MarketConstants.MAX_ACTIVE_LISTINGS, detailWidth),
                detailX, buttonY + 43, MUTED, false);
    }

    private void renderMerchant(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = panelX + 12;
        int right = panelX + panelWidth - 12;
        int y = contentTop + 3;
        graphics.drawString(font, "市场行情", x, y, TEXT, false);
        graphics.drawString(font, "按当前最低价样本汇总，点击分类进入购买", x + 62, y, MUTED, false);
        y += 17;
        box(graphics, x, y, right - x, 18, PANEL_SOFT, BORDER_DIM);
        graphics.drawString(font, "商品分组", x + 6, y + 5, MUTED, false);
        graphics.drawString(font, "挂单", x + (right - x) / 2, y + 5, MUTED, false);
        graphics.drawString(font, "可见最低价", right - 82, y + 5, MUTED, false);
        y += 21;

        Map<MarketCategory, PriceStats> stats = new EnumMap<>(MarketCategory.class);
        for (MarketListing listing : listings()) {
            stats.computeIfAbsent(MarketCategory.classify(listing.item()), ignored -> new PriceStats())
                    .accept(listing.price());
        }
        int[] counts = snapshot == null ? new int[MarketCategory.values().length] : snapshot.categoryCounts();
        MarketCategory[] categories = MarketCategory.values();
        int rowHeight = Math.max(18, Math.min(28, (contentBottom - y - 4) / (categories.length - 1)));
        for (int i = 1; i < categories.length; i++) {
            MarketCategory category = categories[i];
            int rowY = y + (i - 1) * rowHeight;
            boolean hover = contains(x, rowY, right - x, rowHeight - 2, mouseX, mouseY);
            box(graphics, x, rowY, right - x, rowHeight - 2, hover ? CELL_HOVER : CELL, BORDER_DIM);
            graphics.fill(x + 1, rowY + 1, x + 3, rowY + rowHeight - 3,
                    RarityCompat.color(Math.min(7, i)));
            graphics.drawString(font, category.displayName(), x + 9,
                    rowY + centeredTextY(rowY, rowHeight - 2), TEXT, false);
            String count = i < counts.length ? Integer.toString(counts[i]) : "0";
            graphics.drawString(font, count, x + (right - x) / 2,
                    rowY + centeredTextY(rowY, rowHeight - 2), MUTED, false);
            PriceStats stat = stats.get(category);
            String price = stat == null ? "--" : formatMoney(stat.min);
            graphics.drawString(font, price, right - 7 - font.width(price),
                    rowY + centeredTextY(rowY, rowHeight - 2), stat == null ? MUTED : GOLD, false);
            addClick(x, rowY, right - x, rowHeight - 2, () -> openBuyCategory(category),
                    List.of(Component.literal("进入该分类的最低价挂单")));
        }
    }

    private void renderHistory(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = panelX + 12;
        int right = panelX + panelWidth - 12;
        int y = contentTop + 3;
        graphics.drawString(font, "交易记录", x, y, TEXT, false);
        graphics.drawString(font, "最近 " + history().size() + " 条", right - 60, y, MUTED, false);
        y += 16;
        int available = contentBottom - y - FOOTER_HEIGHT;
        int perPage = Math.max(4, available / 18);
        int pages = Math.max(1, (history().size() + perPage - 1) / perPage);
        historyPage = Math.min(historyPage, pages - 1);
        int start = historyPage * perPage;
        int end = Math.min(history().size(), start + perPage);
        for (int index = start; index < end; index++) {
            MarketTransaction transaction = history().get(index);
            int rowY = y + (index - start) * 18;
            boolean hover = contains(x, rowY, right - x, 16, mouseX, mouseY);
            box(graphics, x, rowY, right - x, 16, hover ? CELL_HOVER : CELL, BORDER_DIM);
            graphics.renderItem(transaction.item(), x + 2, rowY);
            String marker = switch (transaction.result()) {
                case SOLD -> "购";
                case CANCELLED -> "撤";
                case EXPIRED -> "退";
                case ADMIN_COPIED -> "取";
                case ADMIN_REMOVED -> "管";
                case ADMIN_DELETED -> "删";
            };
            int markerColor = switch (transaction.result()) {
                case SOLD -> GREEN;
                case ADMIN_COPIED, ADMIN_REMOVED -> GOLD;
                case ADMIN_DELETED -> RED;
                case CANCELLED, EXPIRED -> MUTED;
            };
            graphics.drawString(font, marker, x + 21, rowY + 4, markerColor, false);
            String item = transaction.item().getHoverName().getString() + " x" + transaction.item().getCount();
            graphics.drawString(font, trim(item, Math.max(40, (right - x) / 2 - 28)),
                    x + 36, rowY + 4, TEXT, false);
            String price = formatMoney(transaction.price());
            graphics.drawString(font, price, right - 78 - font.width(price), rowY + 4, GOLD, false);
            graphics.drawString(font, formatDate(transaction.timestamp()), right - 65, rowY + 4, MUTED, false);
            addClick(x, rowY, right - x, 16, () -> {
            }, historyTooltip(transaction));
        }
        if (history().isEmpty()) {
            centered(graphics, "暂无交易记录", x, y, right - x, available, MUTED);
        }
        renderLocalPagination(graphics, x, contentBottom - FOOTER_HEIGHT, right - x,
                historyPage, pages, mouseX, mouseY,
                () -> historyPage = Math.max(0, historyPage - 1),
                () -> historyPage = Math.min(pages - 1, historyPage + 1));
    }

    private void renderSettings(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = panelX + Math.max(12, panelWidth / 8);
        int w = panelWidth - (x - panelX) * 2;
        int y = contentTop + 8;
        graphics.drawString(font, "物品显示与查询设置", x, y, TEXT, false);
        y += 20;
        settingRow(graphics, mouseX, mouseY, x, y, w, "默认排序", selectedSort.displayName(), this::cycleSort);
        y += 30;
        settingRow(graphics, mouseX, mouseY, x, y, w, "默认分类", selectedCategory.displayName(), this::cycleCategory);
        y += 30;
        settingRow(graphics, mouseX, mouseY, x, y, w, "价格格式", compactPrices ? "紧凑 4.15m" : "完整 4,150,000",
                () -> compactPrices = !compactPrices);
        y += 30;
        settingRow(graphics, mouseX, mouseY, x, y, w, "悬停详情", detailedTooltips ? "完整" : "精简",
                () -> detailedTooltips = !detailedTooltips);
        y += 38;
        graphics.drawString(font, "等级颜色来自 Rarity Core；未安装时按一级显示。", x, y, MUTED, false);
        graphics.drawString(font, "分类与排序会在下次市场查询中立即生效。", x, y + 13, MUTED, false);
    }

    private void settingRow(GuiGraphics graphics, int mouseX, int mouseY,
                            int x, int y, int w, String label, String value, Runnable action) {
        boolean hover = contains(x, y, w, 24, mouseX, mouseY);
        box(graphics, x, y, w, 24, hover ? CELL_HOVER : CELL, BORDER_DIM);
        graphics.drawString(font, label, x + 8, y + 8, TEXT, false);
        String shown = "<  " + value + "  >";
        graphics.drawString(font, trim(shown, w / 2), x + w - 8 - font.width(trim(shown, w / 2)),
                y + 8, GOLD, false);
        addClick(x, y, w, 24, action, null);
    }

    private void renderConfirmation(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(0, 0, width, height, 0x8A000000);
        int w = Math.min(290, panelWidth - 30);
        int h = 112;
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        box(graphics, x, y, w, h, 0xFA0D1A22,
                confirmation.destructive() ? RED : ACCENT_HOVER);
        centeredTrimmed(graphics, confirmation.title(), x + 8, y + 9, w - 16, 18, TEXT);
        int lineY = y + 33;
        for (Component line : confirmation.lines()) {
            centeredTrimmed(graphics, line.getString(), x + 10, lineY, w - 20, 13,
                    line.getStyle().getColor() == null ? MUTED : line.getStyle().getColor().getValue());
            lineY += 13;
        }
        int buttonY = y + h - 30;
        boolean cancelHover = contains(x + 12, buttonY, 74, 20, mouseX, mouseY);
        boolean confirmHover = contains(x + w - 86, buttonY, 74, 20, mouseX, mouseY);
        button(graphics, x + 12, buttonY, 74, 20, false, cancelHover);
        centered(graphics, "返回", x + 12, buttonY, 74, 20, TEXT);
        if (confirmation.destructive()) {
            box(graphics, x + w - 86, buttonY, 74, 20,
                    confirmHover ? 0xFF73313A : 0xFF4B252D, RED);
            centered(graphics, "永久销毁", x + w - 86, buttonY, 74, 20, TEXT);
        } else {
            button(graphics, x + w - 86, buttonY, 74, 20, true, confirmHover);
            centered(graphics, "确认", x + w - 86, buttonY, 74, 20, TEXT);
        }
        addClick(x + 12, buttonY, 74, 20, () -> confirmation = null, null);
        addClick(x + w - 86, buttonY, 74, 20, () -> {
            Runnable action = confirmation.action();
            confirmation = null;
            action.run();
        }, null);
    }

    private void renderAdminMenu(GuiGraphics graphics, int mouseX, int mouseY) {
        AdminMenu menu = adminMenu;
        if (menu == null) {
            return;
        }
        int w = Math.min(196, panelWidth - 16);
        int h = 111;
        int x = Math.max(panelX + 5,
                Math.min(menu.x(), panelX + panelWidth - w - 5));
        int y = Math.max(panelY + 5,
                Math.min(menu.y(), panelY + panelHeight - h - 5));

        graphics.fill(x + 3, y + 3, x + w + 3, y + h + 3, 0x75000000);
        box(graphics, x, y, w, h, 0xFA0D1A22, RED);
        graphics.renderItem(menu.listing().item(), x + 6, y + 5);
        graphics.renderItemDecorations(font, menu.listing().item(), x + 6, y + 5);
        graphics.drawString(font, trim(menu.listing().item().getHoverName().getString(), w - 34),
                x + 27, y + 6, TEXT, false);
        graphics.drawString(font, trim("#" + menu.listing().id() + " · "
                        + menu.listing().sellerName(), w - 34),
                x + 27, y + 18, MUTED, false);

        int buttonX = x + 6;
        int buttonW = w - 12;
        int copyY = y + 34;
        boolean copyHover = contains(buttonX, copyY, buttonW, 21, mouseX, mouseY);
        button(graphics, buttonX, copyY, buttonW, 21, true, copyHover);
        centered(graphics, "获取副本（保留挂单）", buttonX, copyY, buttonW, 21, TEXT);
        addClick(buttonX, copyY, buttonW, 21,
                () -> confirmAdminCopy(menu.listing()),
                List.of(Component.literal("复制完整物品到管理员背包")));

        int removeY = copyY + 24;
        boolean removeHover = contains(buttonX, removeY, buttonW, 21, mouseX, mouseY);
        box(graphics, buttonX, removeY, buttonW, 21,
                removeHover ? 0xFF655624 : CELL, GOLD);
        centered(graphics, "下架并退回卖家", buttonX, removeY, buttonW, 21, TEXT);
        addClick(buttonX, removeY, buttonW, 21,
                () -> confirmAdminRemove(menu.listing()),
                List.of(Component.literal("移除挂单，原物立即进入卖家安全邮箱")));

        int deleteY = removeY + 24;
        boolean deleteHover = contains(buttonX, deleteY, buttonW, 21, mouseX, mouseY);
        box(graphics, buttonX, deleteY, buttonW, 21,
                deleteHover ? 0xFF73313A : 0xFF4B252D, RED);
        centered(graphics, "销毁异常物品（不退回）", buttonX, deleteY, buttonW, 21, TEXT);
        addClick(buttonX, deleteY, buttonW, 21,
                () -> confirmAdminDelete(menu.listing()),
                List.of(Component.literal("永久删除托管原物，操作不可撤销")
                        .withStyle(ChatFormatting.RED)));
    }

    private void renderNotice(GuiGraphics graphics) {
        if (notice.isBlank()) {
            return;
        }
        if (noticeUntil > 0L && System.currentTimeMillis() > noticeUntil) {
            notice = "";
            return;
        }
        int y = panelY + panelHeight - 15;
        graphics.fill(panelX + 2, y - 2, panelX + panelWidth - 2, panelY + panelHeight - 2, 0xE30A151B);
        graphics.drawString(font, trim(notice, panelWidth - 16), panelX + 8, y, noticeColor, false);
    }

    private void renderHoveredTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        for (int i = clickRegions.size() - 1; i >= 0; i--) {
            ClickRegion region = clickRegions.get(i);
            if (region.tooltip() != null && region.contains(mouseX, mouseY)) {
                graphics.renderComponentTooltip(font, region.tooltip(), mouseX, mouseY);
                return;
            }
        }
    }

    private void changeTab(Tab newTab) {
        if (newTab == tab) {
            return;
        }
        tab = newTab;
        confirmation = null;
        adminMenu = null;
        selectedRarity = 0;
        historyPage = 0;
        updateWidgetVisibility();
        switch (newTab) {
            case MINE -> sendQuery(MarketView.MINE, selectedCategory, selectedSort, 0);
            case GLOBAL -> sendQuery(MarketView.GLOBAL, selectedCategory, selectedSort, 0);
            case BUY -> {
                selectedSort = MarketSort.PRICE_ASC;
                sendQuery(MarketView.GLOBAL, selectedCategory, selectedSort, 0);
            }
            case MERCHANT -> {
                selectedCategory = MarketCategory.ALL;
                selectedSort = MarketSort.PRICE_ASC;
                sendQuery(MarketView.GLOBAL, selectedCategory, selectedSort, 0);
            }
            case HISTORY -> sendQuery(query.view(), selectedCategory, selectedSort, query.page());
            case SELL, SETTINGS -> {
            }
        }
    }

    private void setCategory(MarketCategory category) {
        selectedCategory = category;
        selectedRarity = 0;
        sendQuery(tab == Tab.MINE ? MarketView.MINE : MarketView.GLOBAL,
                category, selectedSort, 0);
    }

    private void openBuyCategory(MarketCategory category) {
        tab = Tab.BUY;
        selectedCategory = category;
        selectedSort = MarketSort.PRICE_ASC;
        updateWidgetVisibility();
        sendQuery(MarketView.GLOBAL, category, selectedSort, 0);
    }

    private void cycleSort() {
        MarketSort[] values = MarketSort.values();
        selectedSort = values[(selectedSort.ordinal() + 1) % values.length];
        if (tab == Tab.MINE || tab == Tab.GLOBAL || tab == Tab.BUY) {
            sendQuery(tab == Tab.MINE ? MarketView.MINE : MarketView.GLOBAL,
                    selectedCategory, selectedSort, 0);
        }
    }

    private void cycleCategory() {
        MarketCategory[] values = MarketCategory.values();
        selectedCategory = values[(selectedCategory.ordinal() + 1) % values.length];
    }

    private void setPage(int page) {
        sendQuery(tab == Tab.MINE ? MarketView.MINE : MarketView.GLOBAL,
                selectedCategory, selectedSort, page);
    }

    private void sendQuery(MarketView view, MarketCategory category, MarketSort sort, int page) {
        adminMenu = null;
        String search = searchBox == null ? query.search() : searchBox.getValue();
        query = new MarketQuery(view, category, sort, search, page);
        ClientMarketState.query(query);
    }

    private void confirmBuy(MarketListing listing) {
        confirmation = new Confirmation("确认购买", List.of(
                Component.literal(listing.item().getHoverName().getString() + " x" + listing.item().getCount()),
                Component.literal("支付 " + formatMoney(listing.price())).withStyle(ChatFormatting.GOLD),
                Component.literal("卖家成交手续费 "
                        + formatMoney(com.gearsandflesh.market.service.MarketService.saleFee(listing.price()))
                        + "；10 分钟后交付").withStyle(ChatFormatting.GRAY)
        ), () -> MarketNetwork.CHANNEL.sendToServer(new BuyListingC2S(listing.id())));
    }

    private void confirmCancel(MarketListing listing) {
        confirmation = new Confirmation("撤销挂单", List.of(
                Component.literal(listing.item().getHoverName().getString() + " x" + listing.item().getCount()),
                Component.literal("物品将在 10 分钟后进入安全邮箱").withStyle(ChatFormatting.GRAY)
        ), () -> MarketNetwork.CHANNEL.sendToServer(new CancelListingC2S(listing.id())));
    }

    private void confirmAdminCopy(MarketListing listing) {
        adminMenu = null;
        confirmation = new Confirmation("管理员：获取副本", List.of(
                Component.literal(listing.item().getHoverName().getString()
                        + " x" + listing.item().getCount()),
                Component.literal("原挂单和卖家托管物保持不变").withStyle(ChatFormatting.GRAY),
                Component.literal("操作将写入管理员审计记录").withStyle(ChatFormatting.GOLD)
        ), () -> MarketNetwork.CHANNEL.sendToServer(new AdminListingActionC2S(
                listing.id(), AdminListingActionC2S.Action.COPY_ITEM)));
    }

    private void confirmAdminRemove(MarketListing listing) {
        adminMenu = null;
        confirmation = new Confirmation("管理员：强制下架", List.of(
                Component.literal(listing.item().getHoverName().getString()
                        + " x" + listing.item().getCount()),
                Component.literal("原物立即退回卖家 " + listing.sellerName())
                        .withStyle(ChatFormatting.GRAY),
                Component.literal("原上架手续费不会退还").withStyle(ChatFormatting.GOLD)
        ), () -> MarketNetwork.CHANNEL.sendToServer(new AdminListingActionC2S(
                listing.id(), AdminListingActionC2S.Action.REMOVE_AND_RETURN)));
    }

    private void confirmAdminDelete(MarketListing listing) {
        adminMenu = null;
        confirmation = new Confirmation("永久销毁异常挂单", List.of(
                Component.literal(listing.item().getHoverName().getString()
                        + " x" + listing.item().getCount()).withStyle(ChatFormatting.RED),
                Component.literal("挂单和托管原物都会永久删除").withStyle(ChatFormatting.RED),
                Component.literal("不会退回卖家；该操作不可撤销").withStyle(ChatFormatting.RED)
        ), () -> MarketNetwork.CHANNEL.sendToServer(new AdminListingActionC2S(
                listing.id(), AdminListingActionC2S.Action.DELETE_WITHOUT_RETURN)), true);
    }

    private void claimMailbox() {
        confirmation = new Confirmation("领取全部", List.of(
                Component.literal("待领取物品 " + pendingItems() + " 件"),
                Component.literal("待结算款项 " + formatMoney(pendingMoney())).withStyle(ChatFormatting.GOLD),
                Component.literal("运输中 " + inTransitItems() + " 件 / "
                        + formatMoney(inTransitMoney()) + " 枚").withStyle(ChatFormatting.GRAY)
        ), () -> MarketNetwork.CHANNEL.sendToServer(new ClaimMailboxC2S()));
    }

    private void confirmSell() {
        ItemStack stack = selectedStack();
        int count = (int) Math.min(Integer.MAX_VALUE, parsePositive(countBox.getValue()));
        long price = parsePositive(priceBox.getValue());
        if (stack.isEmpty() || count < 1 || count > stack.getCount()) {
            showLocalError("上架数量超出选中物品数量");
            return;
        }
        if (price < 1L || price > MarketConstants.MAX_PRICE) {
            showLocalError("价格必须在 1 到 " + MarketConstants.MAX_PRICE + " 之间");
            return;
        }
        int slot = selectedSlot;
        long listingFee = com.gearsandflesh.market.service.MarketService.listingFee(price);
        long saleFee = com.gearsandflesh.market.service.MarketService.saleFee(price);
        long sellerNet = com.gearsandflesh.market.service.MarketService.sellerNet(price);
        confirmation = new Confirmation("确认上架", List.of(
                Component.literal(stack.getHoverName().getString() + " x" + count),
                Component.literal("标价 " + formatMoney(price) + "；成交净收 "
                        + formatMoney(sellerNet)).withStyle(ChatFormatting.GOLD),
                Component.literal("上架费 " + formatMoney(listingFee) + "；成交费 "
                        + formatMoney(saleFee)).withStyle(ChatFormatting.GRAY)
        ), () -> MarketNetwork.CHANNEL.sendToServer(new CreateListingC2S(slot, count, price)));
    }

    private void selectInventorySlot(int slot, ItemStack stack) {
        selectedSlot = slot;
        countBox.setValue(Integer.toString(stack.getCount()));
    }

    private void showLocalError(String message) {
        notice = message;
        noticeColor = RED;
        noticeUntil = System.currentTimeMillis() + 4_000L;
    }

    private void updateWidgetVisibility() {
        if (searchBox == null) {
            return;
        }
        boolean searchVisible = tab == Tab.MINE || tab == Tab.GLOBAL || tab == Tab.BUY;
        searchBox.visible = searchVisible;
        searchBox.active = searchVisible;
        boolean sellVisible = tab == Tab.SELL;
        countBox.visible = sellVisible;
        countBox.active = sellVisible;
        priceBox.visible = sellVisible;
        priceBox.active = sellVisible;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (confirmation != null) {
            if (button == 0) {
                for (int i = clickRegions.size() - 1; i >= 0; i--) {
                    ClickRegion region = clickRegions.get(i);
                    if (region.contains(mouseX, mouseY)) {
                        region.action().run();
                        return true;
                    }
                }
            }
            return true;
        }
        if (adminMode && button == 1) {
            for (int i = listingRegions.size() - 1; i >= 0; i--) {
                ListingRegion region = listingRegions.get(i);
                if (region.contains(mouseX, mouseY)) {
                    adminMenu = new AdminMenu(
                            region.listing(), (int) mouseX, (int) mouseY);
                    clickRegions.clear();
                    return true;
                }
            }
            if (adminMenu != null) {
                adminMenu = null;
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (adminMenu != null) {
            if (button == 0) {
                for (int i = clickRegions.size() - 1; i >= 0; i--) {
                    ClickRegion region = clickRegions.get(i);
                    if (region.contains(mouseX, mouseY)) {
                        region.action().run();
                        return true;
                    }
                }
                adminMenu = null;
            }
            return true;
        }
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        for (int i = clickRegions.size() - 1; i >= 0; i--) {
            ClickRegion region = clickRegions.get(i);
            if (region.contains(mouseX, mouseY)) {
                region.action().run();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && confirmation != null) {
            confirmation = null;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && adminMenu != null) {
            adminMenu = null;
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && searchBox != null && searchBox.isFocused()) {
            sendQuery(tab == Tab.MINE ? MarketView.MINE : MarketView.GLOBAL,
                    selectedCategory, selectedSort, 0);
            searchBox.setFocused(false);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void calculateLayout() {
        panelWidth = Math.min(620, Math.max(300, width - 12));
        panelHeight = Math.min(344, Math.max(210, height - 12));
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        contentTop = panelY + 33;
        contentBottom = panelY + panelHeight - 6;
        sidebarWidth = Math.min(112, Math.max(90, panelWidth / 5));
    }

    private int sellCellSize() {
        return Math.max(18, Math.min(22, (panelWidth - 196) / 9));
    }

    private int sellDetailX() {
        return panelX + 12 + sellCellSize() * 9 + 12;
    }

    private List<MarketListing> visibleListings() {
        if (selectedRarity == 0) {
            return listings();
        }
        return listings().stream()
                .filter(listing -> RarityCompat.rarity(listing.item()) == selectedRarity)
                .toList();
    }

    private List<MarketListing> listings() {
        return snapshot == null ? List.of() : snapshot.listings();
    }

    private List<MarketTransaction> history() {
        return snapshot == null ? List.of() : snapshot.history();
    }

    private int totalMatches() {
        return snapshot == null ? 0 : snapshot.totalMatches();
    }

    private long cash() {
        return snapshot == null ? 0L : snapshot.cash();
    }

    private long pendingMoney() {
        return snapshot == null ? 0L : snapshot.pendingMoney();
    }

    private int pendingItems() {
        return snapshot == null ? 0 : snapshot.pendingItems();
    }

    private int inTransitItems() {
        return snapshot == null ? 0 : snapshot.inTransitItems();
    }

    private long inTransitMoney() {
        return snapshot == null ? 0L : snapshot.inTransitMoney();
    }

    private int listingAttemptsRemaining() {
        return snapshot == null
                ? MarketConstants.MAX_LISTINGS_PER_HOUR
                : snapshot.listingAttemptsRemaining();
    }

    private String nextDeliveryRemaining() {
        long deliveryAt = snapshot == null ? 0L : snapshot.nextDeliveryAt();
        if (deliveryAt <= 0L) {
            return "等待中";
        }
        long seconds = Math.max(1L,
                (deliveryAt - System.currentTimeMillis() + 999L) / 1_000L);
        if (seconds < 60L) {
            return seconds + " 秒";
        }
        return ((seconds + 59L) / 60L) + " 分";
    }

    private ItemStack selectedStack() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || selectedSlot < 0
                || selectedSlot >= minecraft.player.getInventory().getContainerSize()) {
            return ItemStack.EMPTY;
        }
        return minecraft.player.getInventory().getItem(selectedSlot);
    }

    private List<Component> listingTooltip(MarketListing listing, boolean mine) {
        List<Component> lines = itemTooltip(listing.item());
        lines.add(Component.literal("金币 " + formatMoney(listing.price())).withStyle(ChatFormatting.GOLD));
        if (detailedTooltips) {
            lines.add(Component.literal("卖家 " + listing.sellerName()).withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("上架 " + formatDate(listing.createdAt())).withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("剩余 " + remaining(listing.expiresAt())).withStyle(ChatFormatting.GREEN));
        }
        lines.add(Component.literal(mine ? "点击撤销挂单" : "点击购买").withStyle(ChatFormatting.GREEN));
        if (adminMode) {
            lines.add(Component.literal("管理模式：右键打开管理菜单")
                    .withStyle(ChatFormatting.GOLD));
        }
        return lines;
    }

    private List<Component> itemTooltip(ItemStack stack) {
        return new ArrayList<>(stack.getTooltipLines(Minecraft.getInstance().player, TooltipFlag.Default.NORMAL));
    }

    private List<Component> historyTooltip(MarketTransaction transaction) {
        List<Component> lines = itemTooltip(transaction.item());
        lines.add(Component.literal("金额 " + formatMoney(transaction.price())).withStyle(ChatFormatting.GOLD));
        lines.add(Component.literal("卖家 " + transaction.sellerName()).withStyle(ChatFormatting.GRAY));
        if (!transaction.buyerName().isBlank()) {
            boolean adminAction = switch (transaction.result()) {
                case ADMIN_COPIED, ADMIN_REMOVED, ADMIN_DELETED -> true;
                default -> false;
            };
            lines.add(Component.literal((adminAction ? "管理员 " : "买家 ")
                    + transaction.buyerName()).withStyle(ChatFormatting.GRAY));
        }
        String resultText = switch (transaction.result()) {
            case ADMIN_COPIED -> "管理员获取了副本，挂单保留";
            case ADMIN_REMOVED -> "管理员下架，原物已退回";
            case ADMIN_DELETED -> "管理员永久销毁，原物未退回";
            default -> "";
        };
        if (!resultText.isBlank()) {
            lines.add(Component.literal(resultText).withStyle(
                    transaction.result() == MarketTransaction.Result.ADMIN_DELETED
                            ? ChatFormatting.RED : ChatFormatting.GOLD));
        }
        lines.add(Component.literal(formatDate(transaction.timestamp())).withStyle(ChatFormatting.GRAY));
        return lines;
    }

    private void renderLocalPagination(GuiGraphics graphics, int x, int y, int w,
                                       int page, int pages, int mouseX, int mouseY,
                                       Runnable previous, Runnable next) {
        int center = x + w / 2;
        boolean hasPrevious = page > 0;
        boolean hasNext = page + 1 < pages;
        button(graphics, center - 55, y + 1, 23, 17, hasPrevious,
                hasPrevious && contains(center - 55, y + 1, 23, 17, mouseX, mouseY));
        centered(graphics, "<", center - 55, y + 1, 23, 17, hasPrevious ? TEXT : MUTED);
        if (hasPrevious) {
            addClick(center - 55, y + 1, 23, 17, previous, null);
        }
        centered(graphics, (page + 1) + " / " + pages, center - 28, y + 1, 56, 17, TEXT);
        button(graphics, center + 32, y + 1, 23, 17, hasNext,
                hasNext && contains(center + 32, y + 1, 23, 17, mouseX, mouseY));
        centered(graphics, ">", center + 32, y + 1, 23, 17, hasNext ? TEXT : MUTED);
        if (hasNext) {
            addClick(center + 32, y + 1, 23, 17, next, null);
        }
    }

    private void addClick(int x, int y, int w, int h, Runnable action, List<Component> tooltip) {
        if (w > 0 && h > 0) {
            clickRegions.add(new ClickRegion(x, y, w, h, action, tooltip));
        }
    }

    private static boolean contains(int x, int y, int w, int h, double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private void box(GuiGraphics graphics, int x, int y, int w, int h, int color, int border) {
        graphics.fill(x, y, x + w, y + h, color);
        outline(graphics, x, y, w, h, border);
    }

    private void button(GuiGraphics graphics, int x, int y, int w, int h,
                        boolean active, boolean hover) {
        int fill = active ? (hover ? ACCENT_HOVER : ACCENT) : (hover ? CELL_HOVER : CELL);
        box(graphics, x, y, w, h, fill, active ? BORDER : BORDER_DIM);
    }

    private static void outline(GuiGraphics graphics, int x, int y, int w, int h, int color) {
        graphics.fill(x, y, x + w, y + 1, color);
        graphics.fill(x, y + h - 1, x + w, y + h, color);
        graphics.fill(x, y, x + 1, y + h, color);
        graphics.fill(x + w - 1, y, x + w, y + h, color);
    }

    private void centered(GuiGraphics graphics, String text, int x, int y, int w, int h, int color) {
        graphics.drawString(font, text, x + (w - font.width(text)) / 2,
                y + centeredTextY(y, h), color, false);
    }

    private void centeredTrimmed(GuiGraphics graphics, String text, int x, int y,
                                 int w, int h, int color) {
        String shown = trim(text, w);
        centered(graphics, shown, x, y, w, h, color);
    }

    private int centeredTextY(int y, int h) {
        return Math.max(1, (h - font.lineHeight) / 2);
    }

    private String trim(String text, int width) {
        if (width <= 3 || font.width(text) <= width) {
            return width <= 0 ? "" : text;
        }
        String ellipsis = "...";
        return font.plainSubstrByWidth(text, Math.max(1, width - font.width(ellipsis))) + ellipsis;
    }

    private String formatMoney(long value) {
        if (!compactPrices) {
            return String.format(Locale.ROOT, "%,d", value);
        }
        if (value >= 1_000_000L) {
            return compactDecimal(value / 1_000_000.0D) + "m";
        }
        if (value >= 1_000L) {
            return compactDecimal(value / 1_000.0D) + "k";
        }
        return Long.toString(value);
    }

    private static String compactDecimal(double value) {
        if (value >= 100.0D || value == Math.rint(value)) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        if (value >= 10.0D) {
            return String.format(Locale.ROOT, "%.1f", value).replaceAll("\\.0$", "");
        }
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static long parsePositive(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static String remaining(long expiresAt) {
        long seconds = Math.max(0L, (expiresAt - System.currentTimeMillis()) / 1_000L);
        if (seconds <= 0L) {
            return "已到期";
        }
        long days = seconds / 86_400L;
        long hours = (seconds % 86_400L) / 3_600L;
        if (days > 0L) {
            return days + "天 " + hours + "小时";
        }
        long minutes = (seconds % 3_600L) / 60L;
        return hours + "小时 " + minutes + "分";
    }

    private static String formatDate(long timestamp) {
        return DATE_FORMAT.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()));
    }

    private static String chineseRarity(int rarity) {
        return switch (rarity) {
            case 2 -> "二级";
            case 3 -> "三级";
            case 4 -> "四级";
            case 5 -> "五级";
            case 6 -> "六级";
            case 7 -> "七级";
            default -> "一级";
        };
    }

    private enum Tab {
        MINE("我的"),
        GLOBAL("全球市场"),
        BUY("购买"),
        SELL("出售"),
        MERCHANT("商人"),
        HISTORY("交易记录"),
        SETTINGS("物品设置");

        private final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private record ClickRegion(int x, int y, int width, int height,
                               Runnable action, List<Component> tooltip) {
        private boolean contains(double mouseX, double mouseY) {
            return MarketScreen.contains(x, y, width, height, mouseX, mouseY);
        }
    }

    private record ListingRegion(int x, int y, int width, int height, MarketListing listing) {
        private boolean contains(double mouseX, double mouseY) {
            return MarketScreen.contains(x, y, width, height, mouseX, mouseY);
        }
    }

    private record AdminMenu(MarketListing listing, int x, int y) {
    }

    private record Confirmation(
            String title,
            List<Component> lines,
            Runnable action,
            boolean destructive
    ) {
        private Confirmation(String title, List<Component> lines, Runnable action) {
            this(title, lines, action, false);
        }
    }

    private static final class PriceStats {
        private long min = Long.MAX_VALUE;

        private void accept(long price) {
            min = Math.min(min, price);
        }
    }
}
