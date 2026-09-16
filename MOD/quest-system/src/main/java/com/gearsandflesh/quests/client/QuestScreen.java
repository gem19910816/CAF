package com.gearsandflesh.quests.client;

import com.gearsandflesh.quests.QuestConstants;
import com.gearsandflesh.quests.data.QuestDefinition;
import com.gearsandflesh.quests.data.QuestEntry;
import com.gearsandflesh.quests.data.QuestGoalType;
import com.gearsandflesh.quests.data.QuestReward;
import com.gearsandflesh.quests.data.QuestRewardKind;
import com.gearsandflesh.quests.data.QuestType;
import com.gearsandflesh.quests.network.ClaimQuestC2S;
import com.gearsandflesh.quests.network.DeleteQuestC2S;
import com.gearsandflesh.quests.network.QuestNetwork;
import com.gearsandflesh.quests.network.QuestSnapshotS2C;
import com.gearsandflesh.quests.network.SaveQuestC2S;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class QuestScreen extends Screen {
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

    private final boolean requestedAdmin;
    /** Player-visible header tabs — the idle staging pool never appears here. */
    private static final QuestType[] TABS = {QuestType.DAILY, QuestType.WEEKLY, QuestType.SPECIAL};
    private QuestSnapshotS2CView view = new QuestSnapshotS2CView(false, List.of());
    private QuestType tab = QuestType.DAILY;
    private int selected = -1;
    private int listScroll;
    private boolean editor;

    private boolean editorEnabled = true;
    private final java.util.LinkedHashSet<QuestType> editorQuestTypes =
            new java.util.LinkedHashSet<>(List.of(QuestType.DAILY));
    private QuestGoalType editorGoalType = QuestGoalType.MANUAL;
    private List<String> editorGoalIds = new ArrayList<>();
    private List<String> editorPrereqs = new ArrayList<>();
    private List<QuestReward> editorRewards = new ArrayList<>();
    private int selectedReward = -1;
    private QuestRewardKind editorRewardKind = QuestRewardKind.MONEY;
    private boolean confirmDelete;
    private String editorLoadedId;
    private String editorId = "";
    private String editorTitle = "";
    private String editorDescription = "";
    private String editorTargetText = "1";
    private String rewardValueText = "";
    private String rewardAmountText = "1";

    private EditBox idBox;
    private EditBox titleBox;
    private EditBox descriptionBox;
    private EditBox targetBox;
    private EditBox rewardValueBox;
    private EditBox rewardAmountBox;
    private List<GoalPickerScreen.Option> killChoices;
    private List<GoalPickerScreen.Option> itemChoices;
    private List<GoalPickerScreen.Option> biomeChoices;
    private List<GoalPickerScreen.Option> structureChoices;

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int contentTop;
    private int contentBottom;
    private int sidebarWidth;

    public QuestScreen(boolean adminMode) {
        super(Component.literal(adminMode ? "任务管理台" : "任务"));
        requestedAdmin = adminMode;
        QuestSnapshotS2C cached = ClientQuestState.snapshot();
        if (cached != null) {
            apply(cached);
        }
    }

    public void apply(QuestSnapshotS2CView message) {
        // The server always answers admin actions (claim/save/claim-all) with an admin-mode snapshot;
        // a screen opened in player view must keep its layout (the extra entries are filtered client-side).
        if (!requestedAdmin && message.adminMode()) {
            message = new QuestSnapshotS2CView(false, message.entries());
        }
        view = message;
        int count = visibleEntries().size();
        if (selected < 0 && count > 0) {
            selected = 0;
        } else if (selected >= count) {
            selected = count - 1;
        }
    }

    public void apply(QuestSnapshotS2C message) {
        apply(new QuestSnapshotS2CView(message.adminMode(), message.entries()));
    }

    @Override
    protected void init() {
        calculateLayout();
        rebuildEditor();
    }

    /** Recreates editor widgets, preserving in-progress edits across resizes and picker returns. */
    private void rebuildEditor() {
        harvestEditorBoxes();
        confirmDelete = false;
        removeWidget(idBox);
        removeWidget(titleBox);
        removeWidget(descriptionBox);
        removeWidget(targetBox);
        removeWidget(rewardValueBox);
        removeWidget(rewardAmountBox);
        idBox = titleBox = descriptionBox = targetBox = rewardValueBox = rewardAmountBox = null;
        if (!editor || !view.adminMode()) {
            return;
        }
        QuestEntry entry = selectedEntry();
        if (entry == null) {
            return;
        }
        QuestDefinition definition = entry.definition();
        if (!definition.id().equals(editorLoadedId)) {
            editorLoadedId = definition.id();
            editorEnabled = definition.enabled();
            editorQuestTypes.clear();
            editorQuestTypes.addAll(definition.types());
            editorGoalType = definition.goalType();
            editorGoalIds = new ArrayList<>(definition.goalIds());
            editorPrereqs = new ArrayList<>(definition.prerequisites());
            editorRewards = new ArrayList<>(definition.rewards());
            editorId = definition.id();
            editorTitle = definition.title();
            editorDescription = definition.description();
            editorTargetText = Integer.toString(definition.target());
            selectedReward = -1;
            editorRewardKind = QuestRewardKind.MONEY;
            rewardValueText = "";
            rewardAmountText = "1";
        }

        int x = detailX();
        int width = detailWidth();
        int boxX = x + 62;
        int boxWidth = width - 72;
        idBox = field(boxX, contentTop + 30, 230, "任务 ID", QuestConstants.MAX_ID_LENGTH);
        titleBox = field(boxX, contentTop + 52, boxWidth, "任务标题", QuestConstants.MAX_TEXT_LENGTH);
        descriptionBox = field(boxX, contentTop + 74, boxWidth, "任务描述", QuestConstants.MAX_DESCRIPTION_LENGTH);
        targetBox = field(x + 234, contentTop + 96, 48, "数量", 7);
        int amountX = x + width - 64;
        rewardValueBox = field(x + 292, contentTop + 214, amountX - 6 - (x + 292), rewardValueHint(), QuestConstants.MAX_COMMAND_LENGTH);
        rewardAmountBox = field(amountX, contentTop + 214, 54, "数量", 7);
        idBox.setValue(editorId);
        titleBox.setValue(editorTitle);
        descriptionBox.setValue(editorDescription);
        targetBox.setValue(editorTargetText);
        rewardValueBox.setValue(rewardValueText);
        rewardAmountBox.setValue(rewardAmountText);
    }

    private void harvestEditorBoxes() {
        if (idBox == null) {
            return;
        }
        editorId = idBox.getValue();
        editorTitle = titleBox.getValue();
        editorDescription = descriptionBox.getValue();
        editorTargetText = targetBox.getValue();
        rewardValueText = rewardValueBox.getValue();
        rewardAmountText = rewardAmountBox.getValue();
    }

    private EditBox field(int x, int y, int width, String hint, int maxLength) {
        EditBox box = new EditBox(font, x + 5, y + 5, width - 10, 9, Component.literal(hint));
        box.setMaxLength(maxLength);
        box.setBordered(false);
        box.setTextColor(TEXT);
        box.setHint(Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(box);
        return box;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        calculateLayout();
        graphics.fill(0, 0, width, height, BACKDROP);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL);
        border(graphics, panelX, panelY, panelWidth, panelHeight, BORDER);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 2, GOLD);

        renderHeader(graphics, mouseX, mouseY);
        renderList(graphics, mouseX, mouseY);
        renderDetails(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** Market-style single header row: tabs, completion counter, close button. */
    private void renderHeader(GuiGraphics graphics, int mouseX, int mouseY) {
        int y = panelY + 5;
        int h = 23;
        int left = panelX + 6;
        int closeWidth = 19;
        int right = panelX + panelWidth - 6;
        int closeX = right - closeWidth;
        int counterWidth = Math.min(76, Math.max(54, panelWidth / 9));
        int counterX = closeX - 4 - counterWidth;
        int tabSpace = Math.max(TABS.length, counterX - 4 - left);
        int tabWidth = Math.max(1, tabSpace / TABS.length);
        int extraPixels = Math.max(0, tabSpace - tabWidth * TABS.length);

        int x = left;
        for (int index = 0; index < TABS.length; index++) {
            QuestType value = TABS[index];
            int actualWidth = tabWidth + (index < extraPixels ? 1 : 0);
            boolean active = tab == value;
            boolean hover = in(x, y, actualWidth - 2, h, mouseX, mouseY);
            graphics.fill(x, y, x + actualWidth - 2, y + h, active ? ACCENT : hover ? ACCENT_HOVER : PANEL_SOFT);
            if (active) {
                graphics.fill(x, y + h - 2, x + actualWidth - 2, y + h, GOLD);
            }
            centered(graphics, value.label(), x, y, actualWidth - 2, h, active ? TEXT : 0xFFD2DEE2);
            x += actualWidth;
        }

        graphics.fill(counterX, y, counterX + counterWidth, y + h, PANEL_SOFT);
        border(graphics, counterX, y, counterWidth, h, BORDER);
        graphics.drawString(font, trim("完成 " + completedCount() + "/" + visibleEntries().size(), counterWidth - 8),
                counterX + 5, y + 8, GOLD, false);

        boolean closeHover = in(closeX, y, closeWidth, h, mouseX, mouseY);
        graphics.fill(closeX, y, closeX + closeWidth, y + h, closeHover ? 0xFF4B252D : PANEL_SOFT);
        border(graphics, closeX, y, closeWidth, h, BORDER);
        centered(graphics, "X", closeX, y, closeWidth, h, closeHover ? GOLD : TEXT);
    }

    private void renderList(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = panelX + 6;
        int y = contentTop;
        int width = sidebarWidth;
        int height = contentBottom - y;
        graphics.fill(x, y, x + width, y + height, PANEL_SOFT);
        border(graphics, x, y, width, height, BORDER_DIM);

        int top = y + 6;
        graphics.drawString(font, view.adminMode() ? "全部任务" : "任务列表", x + 6, y + 6, TEXT, false);
        top = y + 24;

        int bottom = view.adminMode() ? y + height - 55 : y + height - 32;
        List<QuestEntry> entries = visibleEntries();
        int row = 30;
        int maxRows = Math.max(1, (bottom - top - 4) / row);
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, entries.size() - maxRows)));
        int end = Math.min(entries.size(), listScroll + maxRows);

        for (int i = listScroll; i < end; i++) {
            QuestEntry entry = entries.get(i);
            int yy = top + 2 + (i - listScroll) * row;
            boolean active = i == selected;
            boolean hover = in(x + 5, yy, width - 10, row - 2, mouseX, mouseY);
            graphics.fill(x + 5, yy, x + width - 5, yy + row - 2, active ? CELL_HOVER : hover ? CELL_HOVER : CELL);
            if (active) {
                graphics.fill(x + 5, yy, x + 7, yy + row - 2, GOLD);
            }
            QuestDefinition quest = entry.definition();
            int titleColor = entry.complete() ? GREEN : TEXT;
            graphics.drawString(font, trim(quest.title(), width - 58), x + 14, yy + 5, titleColor, false);
            graphics.drawString(font, entry.progress() + "/" + quest.target(), x + width - 56, yy + 5, MUTED, false);
            String status = entry.claimed() ? "已领取" : entry.complete() ? "可领取" : goalShort(quest);
            if (view.adminMode()) {
                status = typeShort(quest) + "·" + status;
            }
            graphics.drawString(font, trim(status, width - 24), x + 14, yy + 17,
                    entry.claimed() ? MUTED : entry.complete() ? GOLD : MUTED, false);
            if (!quest.enabled() && view.adminMode()) {
                graphics.drawString(font, "停用", x + width - 32, yy + 17, RED, false);
            }
        }

        if (view.adminMode()) {
            int buttonWidth = (width - 16) / 2;
            button(graphics, x + 6, y + height - 49, buttonWidth, 20, GREEN, "立即推送");
            button(graphics, x + 10 + buttonWidth, y + height - 49, buttonWidth, 20, GOLD, "任务池");
            button(graphics, x + 6, y + height - 25, buttonWidth, 20, editor ? GOLD : TEXT, editor ? "关闭编辑" : "编辑任务");
            button(graphics, x + 10 + buttonWidth, y + height - 25, buttonWidth, 20, GOLD, "新建任务");
        } else {
            button(graphics, x + 6, y + height - 26, width - 12, 20, GOLD, "一键领取全部");
        }
    }

    private void renderDetails(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = detailX();
        int y = contentTop;
        int width = detailWidth();
        int height = contentBottom - y;
        graphics.fill(x, y, x + width, y + height, PANEL_SOFT);
        border(graphics, x, y, width, height, BORDER_DIM);
        QuestEntry entry = selectedEntry();
        if (entry == null) {
            centered(graphics, "选择一个任务", x, y, width, height, MUTED);
            return;
        }

        QuestDefinition quest = entry.definition();
        graphics.drawString(font, trim(quest.title(), width - 82), x + 12, y + 10, TEXT, false);
        graphics.drawString(font, typesLabel(quest), x + width - 82, y + 10, GOLD, false);
        graphics.fill(x + 10, y + 24, x + width - 10, y + 25, BORDER_DIM);

        if (view.adminMode() && editor) {
            renderEditor(graphics, x, y, width, mouseX, mouseY);
            return;
        }

        graphics.drawString(font, trim(quest.description(), width - 24), x + 12, y + 34, MUTED, false);
        graphics.drawString(font, "目标", x + 12, y + 57, MUTED, false);
        graphics.fill(x + 10, y + 70, x + width - 10, y + 93, CELL);
        border(graphics, x + 10, y + 70, width - 20, 23, BORDER_DIM);
        graphics.drawString(font, trim(goalText(quest), width - 96), x + 18, y + 75, TEXT, false);
        graphics.drawString(font, entry.progress() + "/" + quest.target(), x + width - 62, y + 75,
                entry.complete() ? GREEN : MUTED, false);

        graphics.drawString(font, "奖励", x + 12, y + 100, MUTED, false);
        List<QuestReward> rewards = quest.rewards();
        if (rewards.isEmpty()) {
            graphics.drawString(font, "（未配置奖励）", x + 14, y + 114, MUTED, false);
        } else {
            for (int i = 0; i < Math.min(QuestConstants.MAX_REWARDS, rewards.size()); i++) {
                QuestReward reward = rewards.get(i);
                int rowY = y + 112 + i * 18;
                graphics.fill(x + 10, rowY, x + width - 10, rowY + 17, CELL);
                border(graphics, x + 10, rowY, width - 20, 17, BORDER_DIM);
                graphics.renderItem(rewardIcon(reward), x + 14, rowY + 1);
                graphics.drawString(font, trim(rewardText(reward), width - 70), x + 34, rowY + 5, TEXT, false);
            }
        }

        if (!view.adminMode()) {
            boolean claimable = entry.complete() && !entry.claimed();
            boolean tracked = entry.definition().id().equals(ClientQuestState.trackedId());
            button(graphics, x + width - 210, y + height - 30, 96, 20, tracked ? GOLD : TEXT,
                    tracked ? "★ 追踪中" : "☆ 追踪");
            button(graphics, x + width - 106, y + height - 30, 96, 20, claimable ? GOLD : MUTED,
                    entry.claimed() ? "已领取" : claimable ? "领取奖励" : "未完成");
        } else {
            button(graphics, x + 10, y + height - 30, 106, 20, quest.enabled() ? GREEN : MUTED,
                    quest.enabled() ? "启用中" : "已停用");
            button(graphics, x + width - 106, y + height - 30, 96, 20, GOLD, "编辑任务");
        }
    }

    private void renderEditor(GuiGraphics graphics, int x, int y, int width, int mouseX, int mouseY) {
        int boxX = x + 62;
        int boxWidth = width - 72;
        String[] labels = {"ID", "标题", "描述", "目标"};
        int[] rows = {30, 52, 74, 96};
        for (int i = 0; i < labels.length; i++) {
            graphics.drawString(font, labels[i], x + 10, y + rows[i] + 5, MUTED, false);
            if (i == 0) {
                fieldBox(graphics, boxX, y + rows[i], 230);
            } else if (i < 3) {
                fieldBox(graphics, boxX, y + rows[i], boxWidth);
            }
        }
        renderMultiSegments(graphics, x + width - 178, y + 30, 168,
                questTypeLabels(), editorQuestTypes);
        boolean manual = editorGoalType == QuestGoalType.MANUAL;
        renderSegments(graphics, boxX, y + 96, 168, QuestGoalType.values().length, editorGoalType.ordinal(), goalTypeLabels());
        fieldBox(graphics, x + 234, y + 96, 48);
        button(graphics, x + width - 116, y + 96, 106, 18, manual ? MUTED : TEXT, "选择目标 (" + editorGoalIds.size() + ")");
        graphics.drawString(font, trim(goalSummary(), width - 190), boxX, y + 118, MUTED, false);
        button(graphics, x + width - 116, y + 112, 106, 18, TEXT, "前置 (" + editorPrereqs.size() + ")");

        graphics.drawString(font, "奖励", x + 10, y + 132, MUTED, false);
        graphics.drawString(font, editorRewards.size() + "/" + QuestConstants.MAX_REWARDS + " 条 · 点行载入修改，点\"添加\"新增",
                x + width - 240, y + 132, MUTED, false);
        int windowSize = Math.min(4, editorRewards.size());
        if (editorRewards.isEmpty()) {
            graphics.fill(x + 10, y + 142, x + width - 10, y + 159, CELL);
            border(graphics, x + 10, y + 142, width - 20, 17, BORDER_DIM);
            graphics.drawString(font, "暂无奖励：选类型 → 填数量（或取手持）→ 点\"添加\"", x + 18, y + 146, MUTED, false);
        }
        int startIndex = rewardWindowStart();
        for (int i = startIndex; i < startIndex + windowSize; i++) {
            QuestReward reward = editorRewards.get(i);
            int rowY = y + 142 + (i - startIndex) * 17;
            boolean active = i == selectedReward;
            graphics.fill(x + 10, rowY, x + width - 10, rowY + 16, active ? CELL_HOVER : CELL);
            border(graphics, x + 10, rowY, width - 20, 16, active ? GOLD : BORDER_DIM);
            if (active) {
                graphics.fill(x + 10, rowY, x + 12, rowY + 16, GOLD);
            }
            graphics.renderItem(rewardIcon(reward), x + 14, rowY);
            graphics.drawString(font, trim(rewardText(reward), width - 70), x + 34, rowY + 4, active ? TEXT : MUTED, false);
        }

        renderSegments(graphics, boxX, y + 214, 224, QuestRewardKind.values().length, editorRewardKind.ordinal(), rewardKindLabels());
        fieldBox(graphics, x + 292, y + 214, x + width - 70 - (x + 292));
        fieldBox(graphics, x + width - 64, y + 214, 54);
        button(graphics, boxX, y + 236, 64, 18, TEXT, "添加");
        button(graphics, boxX + 70, y + 236, 64, 18, TEXT, "更新选中");
        button(graphics, boxX + 140, y + 236, 64, 18, RED, "删除选中");
        button(graphics, boxX + 210, y + 236, 64, 18, GOLD, "取手持");
        button(graphics, boxX + 280, y + 236, 64, 18, GOLD, "背包选物");

        int bottom = contentBottom - y;
        button(graphics, x + 10, y + bottom - 30, 106, 20, editorEnabled ? GREEN : MUTED,
                editorEnabled ? "已启用" : "已停用");
        button(graphics, x + 122, y + bottom - 30, 76, 20, TEXT, "复制任务");
        button(graphics, x + width - 210, y + bottom - 30, 98, 20, GOLD, "保存任务");
        if (confirmDelete) {
            int dx = x + width - 106;
            int dy = y + bottom - 30;
            graphics.fill(dx, dy, dx + 96, dy + 20, 0xFF73313A);
            border(graphics, dx, dy, 96, 20, RED);
            centered(graphics, "确认删除？", dx, dy, 96, 20, RED);
        } else {
            button(graphics, x + width - 106, y + bottom - 30, 96, 20, RED, "删除任务");
        }
    }

    private String[] questTypeLabels() {
        String[] labels = new String[QuestType.values().length];
        for (int i = 0; i < QuestType.values().length; i++) {
            labels[i] = switch (QuestType.values()[i]) {
                case DAILY -> "日常";
                case WEEKLY -> "周常";
                case SPECIAL -> "特殊";
                case IDLE -> "闲置";
            };
        }
        return labels;
    }

    private String[] goalTypeLabels() {
        String[] labels = new String[QuestGoalType.values().length];
        for (int i = 0; i < QuestGoalType.values().length; i++) {
            labels[i] = switch (QuestGoalType.values()[i]) {
                case KILL -> "击杀";
                case COLLECT -> "收集";
                case MANUAL -> "手动";
                case BIOME -> "群系";
                case STRUCTURE -> "结构";
            };
        }
        return labels;
    }

    private String[] rewardKindLabels() {
        QuestRewardKind[] values = QuestRewardKind.values();
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i].label();
        }
        return labels;
    }

    private void renderSegments(GuiGraphics graphics, int x, int y, int width, int count, int active, String[] labels) {
        int segmentWidth = width / count;
        for (int i = 0; i < count; i++) {
            int sx = x + i * segmentWidth;
            int w = i == count - 1 ? x + width - sx : segmentWidth;
            boolean isActive = i == active;
            graphics.fill(sx, y, sx + w - 2, y + 18, isActive ? ACCENT : CELL);
            border(graphics, sx, y, w - 2, 18, isActive ? GOLD : BORDER_DIM);
            centered(graphics, labels[i], sx, y, w - 2, 18, isActive ? TEXT : MUTED);
        }
    }

    /** Segment group where several options can be on at once (quest groups). */
    private void renderMultiSegments(GuiGraphics graphics, int x, int y, int width, String[] labels,
                                     java.util.Set<QuestType> active) {
        int count = labels.length;
        int segmentWidth = width / count;
        for (int i = 0; i < count; i++) {
            int sx = x + i * segmentWidth;
            int w = i == count - 1 ? x + width - sx : segmentWidth;
            boolean isActive = active.contains(QuestType.values()[i]);
            graphics.fill(sx, y, sx + w - 2, y + 18, isActive ? ACCENT : CELL);
            border(graphics, sx, y, w - 2, 18, isActive ? GOLD : BORDER_DIM);
            centered(graphics, labels[i], sx, y, w - 2, 18, isActive ? TEXT : MUTED);
        }
    }

    /** Returns the segment index under the mouse, or -1. */
    private int segmentAt(int x, int y, int width, int count, double mouseX, double mouseY) {
        if (!in(x, y, width, 18, mouseX, mouseY)) {
            return -1;
        }
        int index = (int) ((mouseX - x) / ((double) width / count));
        return Math.max(0, Math.min(count - 1, index));
    }

    /** Keeps the selected reward row visible when more than four rewards exist. */
    private int rewardWindowStart() {
        if (editorRewards.size() <= 4) {
            return 0;
        }
        return Math.max(0, Math.min(selectedReward - 3, editorRewards.size() - 4));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int left = panelX;
        int right = panelX + panelWidth;
        int headerY = panelY + 5;
        int headerH = 23;
        int closeX = right - 6 - 19;
        int counterWidth = Math.min(76, Math.max(54, panelWidth / 9));
        int counterX = closeX - 4 - counterWidth;
        int tabSpace = Math.max(TABS.length, counterX - 4 - left);
        int tabWidth = Math.max(1, tabSpace / TABS.length);
        int extraPixels = Math.max(0, tabSpace - tabWidth * TABS.length);

        if (in(right - 25, headerY, 19, headerH, mouseX, mouseY)) {
            onClose();
            return true;
        }

        int x = left;
        for (int i = 0; i < TABS.length; i++) {
            int actualWidth = tabWidth + (i < extraPixels ? 1 : 0);
            if (in(x, headerY, actualWidth - 2, headerH, mouseX, mouseY)) {
                tab = TABS[i];
                selected = firstIndex();
                listScroll = 0;
                rebuildEditor();
                return true;
            }
            x += actualWidth;
        }

        int listX = left + 6;
        int listY = contentTop;
        int listWidth = sidebarWidth;
        int listHeight = contentBottom - listY;

        List<QuestEntry> entries = visibleEntries();
        int row = 30;
        int contentTop2 = listY + 24;
        int contentBottom2 = view.adminMode() ? listY + listHeight - 55 : listY + listHeight - 32;
        int maxRows = Math.max(1, (contentBottom2 - contentTop2 - 4) / row);
        int end = Math.min(entries.size(), listScroll + maxRows);
        for (int i = listScroll; i < end; i++) {
            int yy = contentTop2 + 2 + (i - listScroll) * row;
            if (in(listX + 5, yy, listWidth - 10, row - 2, mouseX, mouseY)) {
                selected = i;
                rebuildEditor();
                return true;
            }
        }

        if ((!view.adminMode())
                && in(listX + 6, listY + listHeight - 26, listWidth - 12, 20, mouseX, mouseY)) {
            QuestNetwork.CHANNEL.sendToServer(new com.gearsandflesh.quests.network.ClaimAllC2S());
            return true;
        }

        if (view.adminMode()) {
            int buttonWidth = (listWidth - 16) / 2;
            int publishY = listY + listHeight - 49;
            if (in(listX + 6, publishY, buttonWidth, 20, mouseX, mouseY)) {
                QuestNetwork.CHANNEL.sendToServer(new com.gearsandflesh.quests.network.PublishQuestsC2S());
                return true;
            }
            if (in(listX + 10 + buttonWidth, publishY, buttonWidth, 20, mouseX, mouseY)) {
                // 任务池：专门管理任务分组与推送的独立界面
                minecraft.setScreen(new QuestPoolScreen(this));
                ClientQuestState.request(true);
                return true;
            }
            int actionY = listY + listHeight - 25;
            if (in(listX + 6, actionY, buttonWidth, 20, mouseX, mouseY)) {
                editor = !editor;
                rebuildEditor();
                return true;
            }
            if (in(listX + 10 + buttonWidth, actionY, buttonWidth, 20, mouseX, mouseY)) {
                createNew();
                return true;
            }
        }

        QuestEntry entry = selectedEntry();
        int detailLeft = detailX();
        int y = contentTop;
        int width = detailWidth();
        int height = contentBottom - y;
        if (entry != null && !view.adminMode()
                && in(detailLeft + width - 210, y + height - 30, 96, 20, mouseX, mouseY)) {
            ClientQuestState.toggleTracked(entry.definition().id());
            return true;
        }
        if (entry != null && !view.adminMode() && in(detailLeft + width - 106, y + height - 30, 96, 20, mouseX, mouseY)) {
            // Claim exactly the pool instance on show (the tab the player is viewing).
            QuestNetwork.CHANNEL.sendToServer(new ClaimQuestC2S(entry.definition().id(), entry.group()));
            return true;
        }

        if (entry != null && view.adminMode()) {
            if (!editor) {
                if (in(detailLeft + 10, y + height - 30, 106, 20, mouseX, mouseY)) {
                    toggleCurrent();
                    return true;
                }
                if (in(detailLeft + width - 106, y + height - 30, 96, 20, mouseX, mouseY)) {
                    editor = true;
                    rebuildEditor();
                    return true;
                }
            } else if (handleEditorClick(detailLeft, y, width, height, mouseX, mouseY)) {
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean handleEditorClick(int x, int y, int width, int height, double mouseX, double mouseY) {
        int boxX = x + 62;
        int bottom = y + height - 30;

        if (in(x + 10, bottom, 106, 20, mouseX, mouseY)) {
            editorEnabled = !editorEnabled;
            return true;
        }
        if (in(x + 122, bottom, 76, 20, mouseX, mouseY)) {
            duplicateCurrent();
            return true;
        }
        if (in(x + width - 210, bottom, 98, 20, mouseX, mouseY)) {
            saveCurrent();
            return true;
        }
        if (in(x + width - 106, bottom, 96, 20, mouseX, mouseY)) {
            if (!confirmDelete) {
                confirmDelete = true;
            } else {
                confirmDelete = false;
                QuestNetwork.CHANNEL.sendToServer(new DeleteQuestC2S(selectedEntry().definition().id()));
            }
            return true;
        }

        if (in(x + width - 116, y + 112, 106, 18, mouseX, mouseY)) {
            openPrereqPicker();
            return true;
        }

        int questTypeSegment = segmentAt(x + width - 178, y + 30, 168, QuestType.values().length, mouseX, mouseY);
        if (questTypeSegment >= 0) {
            QuestType toggled = QuestType.values()[questTypeSegment];
            if (editorQuestTypes.contains(toggled)) {
                if (editorQuestTypes.size() > 1) {
                    editorQuestTypes.remove(toggled);
                } else {
                    warn("任务至少要属于一个任务组");
                }
            } else {
                editorQuestTypes.add(toggled);
            }
            return true;
        }

        int goalSegment = segmentAt(boxX, y + 96, 168, QuestGoalType.values().length, mouseX, mouseY);
        if (goalSegment >= 0) {
            QuestGoalType newType = QuestGoalType.values()[goalSegment];
            if (newType != editorGoalType) {
                editorGoalType = newType;
                if (newType != QuestGoalType.MANUAL) {
                    // Drop ids that don't fit the new type; leave the list empty instead of
                    // silently pre-selecting something — the summary prompts picking goals.
                    editorGoalIds = validGoalsFor(newType, editorGoalIds);
                }
            }
            return true;
        }
        if (editorGoalType != QuestGoalType.MANUAL) {
            if (in(x + width - 116, y + 96, 106, 18, mouseX, mouseY)) {
                openGoalPicker();
                return true;
            }
        }

        int windowStart = rewardWindowStart();
        int windowSize = Math.min(4, editorRewards.size());
        for (int i = windowStart; i < windowStart + windowSize; i++) {
            int rowY = y + 142 + (i - windowStart) * 17;
            if (in(x + 10, rowY, width - 20, 16, mouseX, mouseY)) {
                selectReward(i);
                return true;
            }
        }

        int kindSegment = segmentAt(boxX, y + 214, 224, QuestRewardKind.values().length, mouseX, mouseY);
        if (kindSegment >= 0) {
            applyRewardKindDefaults(QuestRewardKind.values()[kindSegment]);
            return true;
        }
        if (in(boxX, y + 236, 64, 18, mouseX, mouseY)) {
            addReward();
            return true;
        }
        if (in(boxX + 70, y + 236, 64, 18, mouseX, mouseY)) {
            updateReward();
            return true;
        }
        if (in(boxX + 140, y + 236, 64, 18, mouseX, mouseY)) {
            deleteReward();
            return true;
        }
        if (in(boxX + 210, y + 236, 64, 18, mouseX, mouseY)) {
            takeHeldItem();
            return true;
        }
        if (in(boxX + 280, y + 236, 64, 18, mouseX, mouseY)) {
            openInventoryPicker();
            return true;
        }
        return false;
    }

    private void openInventoryPicker() {
        Minecraft.getInstance().setScreen(new InventoryPickerScreen(this, itemId -> {
            editorRewardKind = QuestRewardKind.ITEM;
            rewardValueBox.setValue(itemId);
        }));
    }

    /** Selects a reward row and loads its values into the editing fields. */
    private void selectReward(int index) {
        selectedReward = index;
        QuestReward reward = editorRewards.get(index);
        editorRewardKind = reward.kind();
        rewardValueBox.setValue(reward.value());
        rewardAmountBox.setValue(reward.amount() > 0 ? Integer.toString(reward.amount()) : "1");
    }

    private void applyRewardKindDefaults(QuestRewardKind kind) {
        editorRewardKind = kind;
        switch (kind) {
            case MONEY -> {
                rewardValueBox.setValue("");
                rewardAmountBox.setValue("25");
            }
            case ITEM -> {
                rewardValueBox.setValue("minecraft:diamond");
                rewardAmountBox.setValue("1");
            }
            case COMMAND -> {
                rewardValueBox.setValue("say %player% 你好！");
                rewardAmountBox.setValue("0");
            }
            case XP -> {
                rewardValueBox.setValue("");
                rewardAmountBox.setValue("100");
            }
        }
    }

    /** Clones the selected quest (saved version) with a fresh ID and selects the copy. */
    private void duplicateCurrent() {
        QuestEntry current = selectedEntry();
        if (current == null) {
            return;
        }
        List<QuestEntry> entries = new ArrayList<>(view.entries());
        String base = current.definition().id();
        if (base.length() > 20) {
            base = base.substring(0, 20);
        }
        String id = base + "_copy";
        int n = 1;
        while (true) {
            String candidate = id;
            if (entries.stream().noneMatch(entry -> entry.definition().id().equals(candidate))) {
                break;
            }
            id = base + "_copy" + n++;
        }
        QuestDefinition old = current.definition();
        // The copy starts standalone: inheriting prerequisites would chain it to the original quest.
        QuestDefinition copy = new QuestDefinition(id, old.types(), old.title() + "副本", old.description(),
                old.goalType(), old.goalIds(), old.target(), old.rewards(), old.enabled(), List.of(), old.revision());
        entries.add(new QuestEntry(copy, copy.types().get(0).name(), 0, false));
        view = new QuestSnapshotS2CView(view.adminMode(), entries);
        selected = entries.size() - 1;
        editor = true;
        rebuildEditor();
    }

    /** Fills the reward value from the item in the player's main (or off) hand. */
    private void takeHeldItem() {
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        ItemStack held = minecraft.player.getMainHandItem();
        if (held.isEmpty()) {
            held = minecraft.player.getOffhandItem();
        }
        if (held.isEmpty()) {
            return;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(held.getItem());
        if (id == null) {
            return;
        }
        editorRewardKind = QuestRewardKind.ITEM;
        rewardValueBox.setValue(id.toString());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int listX = panelX + 6;
        int listY = contentTop;
        int listHeight = contentBottom - listY;
        if (in(listX, listY, sidebarWidth, listHeight, mouseX, mouseY)) {
            int row = 30;
            int top = listY + 24;
            int bottom = view.adminMode() ? listY + listHeight - 55 : listY + listHeight - 32;
            int maxRows = Math.max(1, (bottom - top - 4) / row);
            int maxScroll = Math.max(0, visibleEntries().size() - maxRows);
            listScroll = Math.max(0, Math.min(maxScroll, listScroll - (int) Math.signum(delta)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private void openGoalPicker() {
        if (editorGoalType == QuestGoalType.MANUAL) {
            return;
        }
        ensureGoalChoices();
        List<GoalPickerScreen.Option> options = switch (editorGoalType) {
            case KILL -> killChoices;
            case COLLECT -> itemChoices;
            case BIOME -> biomeChoices;
            case STRUCTURE -> structureChoices;
            default -> List.of();
        };
        String title = switch (editorGoalType) {
            case KILL -> "选择击杀目标";
            case COLLECT -> "选择收集物品";
            case BIOME -> "选择要探索的群系";
            case STRUCTURE -> "选择要探索的结构";
            default -> "选择目标";
        };
        List<String> current = new ArrayList<>(editorGoalIds);
        Minecraft.getInstance().setScreen(new GoalPickerScreen(this,
                editorGoalType == QuestGoalType.COLLECT,
                Component.literal(title), options, current, ids -> editorGoalIds = ids));
    }

    private void openPrereqPicker() {
        List<GoalPickerScreen.Option> options = new ArrayList<>();
        for (QuestEntry entry : view.entries()) {
            if (entry.definition().id().equals(editorLoadedId)) {
                continue;
            }
            options.add(new GoalPickerScreen.Option(entry.definition().id(),
                    entry.definition().title() + " · " + entry.definition().id(), null, null));
        }
        options.sort(Comparator.comparing(GoalPickerScreen.Option::value));
        Minecraft.getInstance().setScreen(new GoalPickerScreen(this, false,
                Component.literal("选择前置任务（全部领取后解锁）"), options, new ArrayList<>(editorPrereqs),
                ids -> editorPrereqs = ids));
    }

    private void cycleGoalType() {
        QuestGoalType[] values = QuestGoalType.values();
        editorGoalType = values[(editorGoalType.ordinal() + 1) % values.length];
        if (editorGoalType == QuestGoalType.MANUAL) {
            return;
        }
        if (editorGoalIds.isEmpty()) {
            editorGoalIds.add(editorGoalType == QuestGoalType.KILL ? "minecraft:zombie" : "minecraft:iron_ingot");
        }
    }

    private void cycleRewardKind() {
        applyRewardKindDefaults(QuestRewardKind.values()[(editorRewardKind.ordinal() + 1) % QuestRewardKind.values().length]);
    }

    private void addReward() {
        if (editorRewards.size() >= QuestConstants.MAX_REWARDS) {
            return;
        }
        editorRewards.add(buildRewardFromFields());
        selectedReward = editorRewards.size() - 1;
    }

    private void updateReward() {
        if (selectedReward < 0 || selectedReward >= editorRewards.size()) {
            return;
        }
        editorRewards.set(selectedReward, buildRewardFromFields());
    }

    private void deleteReward() {
        if (selectedReward < 0 || selectedReward >= editorRewards.size()) {
            return;
        }
        editorRewards.remove(selectedReward);
        selectedReward = -1;
    }

    private QuestReward buildRewardFromFields() {
        return new QuestReward(editorRewardKind, rewardValueBox.getValue(), parseInt(rewardAmountBox, 1));
    }

    private void createNew() {
        List<QuestEntry> entries = new ArrayList<>(view.entries());
        int sequence = entries.size() + 1;
        String id = "new_quest_" + sequence;
        while (true) {
            String candidate = id;
            if (entries.stream().noneMatch(entry -> entry.definition().id().equals(candidate))) {
                break;
            }
            sequence++;
            id = "new_quest_" + sequence;
        }
        QuestDefinition definition = new QuestDefinition(id, List.of(QuestType.IDLE), "新任务", "填写任务描述",
                QuestGoalType.KILL, List.of(), 1,
                List.of(new QuestReward(QuestRewardKind.MONEY, "", 10)), true, List.of(), 0);
        entries.add(new QuestEntry(definition, tab.name(), 0, false));
        view = new QuestSnapshotS2CView(true, entries);
        selected = entries.size() - 1;
        editor = true;
        rebuildEditor();
    }

    private void saveCurrent() {
        QuestEntry current = selectedEntry();
        if (current == null || idBox == null) {
            return;
        }
        String id = idBox.getValue().strip();
        String title = titleBox.getValue().strip();
        if (id.isEmpty()) {
            warn("任务 ID 不能为空");
            return;
        }
        if (title.isEmpty()) {
            warn("任务标题不能为空");
            return;
        }
        if (editorGoalType != QuestGoalType.MANUAL) {
            if (editorGoalIds.isEmpty()) {
                warn("请先点\"选择目标\"至少勾选一个目标");
                return;
            }
            if (editorGoalIds.size() > QuestConstants.MAX_GOALS) {
                warn("目标最多 " + QuestConstants.MAX_GOALS + " 个，当前选了 " + editorGoalIds.size() + " 个");
                return;
            }
            for (String goalId : editorGoalIds) {
                if (!validGoal(goalId, editorGoalType)) {
                    warn("目标 \"" + goalId + "\" 与类型不匹配或无效，请重新选择");
                    return;
                }
            }
        }
        for (QuestReward reward : editorRewards) {
            if (reward.kind() == QuestRewardKind.ITEM && !validGoal(reward.value(), QuestGoalType.COLLECT)) {
                warn("奖励物品 \"" + reward.value() + "\" 无效，请用\"取手持\"重新选择");
                return;
            }
        }
        int target = parseInt(targetBox, current.definition().target());
        List<String> goals = editorGoalType == QuestGoalType.MANUAL ? List.of() : editorGoalIds;
        QuestDefinition definition = new QuestDefinition(id, List.copyOf(editorQuestTypes), title,
                descriptionBox.getValue(), editorGoalType, goals, target,
                new ArrayList<>(editorRewards), editorEnabled, editorPrereqs, current.definition().revision());
        QuestNetwork.CHANNEL.sendToServer(new SaveQuestC2S(definition));
    }

    /** Keeps only ids that exist in the registry matching the goal type. */
    private List<String> validGoalsFor(QuestGoalType type, List<String> ids) {
        List<String> result = new ArrayList<>();
        for (String id : ids) {
            if (validGoal(id, type)) {
                result.add(id);
            }
        }
        return result;
    }

    private boolean validGoal(String id, QuestGoalType type) {
        ResourceLocation parsed = ResourceLocation.tryParse(id);
        if (parsed == null) {
            return false;
        }
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return true;
        }
        return switch (type) {
            case KILL -> ForgeRegistries.ENTITY_TYPES.containsKey(parsed);
            case COLLECT -> ForgeRegistries.ITEMS.containsKey(parsed);
            case BIOME -> worldgenContains(net.minecraft.core.registries.Registries.BIOME, parsed);
            case STRUCTURE -> worldgenContains(net.minecraft.core.registries.Registries.STRUCTURE, parsed);
            case MANUAL -> false;
        };
    }

    /** 1.20.1 does not sync worldgen registries (structures not at all) to the client: use the
     *  integrated server's full registries first, then the networked ones. A queryable registry
     *  gives the definitive answer; only when none is reachable do we stay lenient and let the
     *  server-side validation decide on save. */
    private static <E> boolean worldgenContains(net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<E>> key,
                                                ResourceLocation id) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSingleplayerServer() != null) {
            try {
                return minecraft.getSingleplayerServer().registryAccess().registryOrThrow(key).containsKey(id);
            } catch (Exception ignored) {
                // fall through
            }
        }
        if (minecraft.level != null) {
            try {
                return minecraft.level.registryAccess().registryOrThrow(key).containsKey(id);
            } catch (Exception ignored) {
                // fall through
            }
        }
        return true;
    }

    private void warn(String message) {
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.literal("[任务] " + message).withStyle(ChatFormatting.GOLD), false);
        }
    }

    private void toggleCurrent() {
        QuestEntry current = selectedEntry();
        if (current == null) {
            return;
        }
        QuestDefinition old = current.definition();
        QuestDefinition definition = new QuestDefinition(old.id(), old.types(), old.title(), old.description(),
                old.goalType(), old.goalIds(), old.target(), old.rewards(), !old.enabled(), old.prerequisites(), old.revision());
        QuestNetwork.CHANNEL.sendToServer(new SaveQuestC2S(definition));
    }

    private void ensureGoalChoices() {
        if (killChoices == null) {
            killChoices = new ArrayList<>();
            ForgeRegistries.ENTITY_TYPES.getEntries().forEach(entry -> {
                String location = entry.getKey().location().toString();
                killChoices.add(new GoalPickerScreen.Option(location,
                        entry.getValue().getDescription().getString() + " · " + location, entry.getValue(), null));
            });
            killChoices.sort(Comparator.comparing(GoalPickerScreen.Option::value));
        }
        if (itemChoices == null) {
            itemChoices = new ArrayList<>();
            ForgeRegistries.ITEMS.getEntries().forEach(entry -> {
                String location = entry.getKey().location().toString();
                ItemStack stack = new ItemStack(entry.getValue());
                itemChoices.add(new GoalPickerScreen.Option(location,
                        stack.getHoverName().getString() + " · " + location, null, stack));
            });
            itemChoices.sort(Comparator.comparing(GoalPickerScreen.Option::value));
        }
        if (biomeChoices == null) {
            biomeChoices = worldgenOptions(net.minecraft.core.registries.Registries.BIOME, "biome");
        }
        if (structureChoices == null) {
            structureChoices = worldgenOptions(net.minecraft.core.registries.Registries.STRUCTURE, "structure");
        }
    }

    /** Builds picker rows from a worldgen registry; singleplayer uses the integrated server's full
     *  registry set (structures are never synced to a dedicated client in 1.20.1). */
    private static <E> List<GoalPickerScreen.Option> worldgenOptions(
            net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<E>> key, String prefix) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSingleplayerServer() != null) {
            try {
                return optionsFrom(minecraft.getSingleplayerServer().registryAccess().registryOrThrow(key), prefix);
            } catch (Exception ignored) {
                // fall through
            }
        }
        if (minecraft.level != null) {
            try {
                return optionsFrom(minecraft.level.registryAccess().registryOrThrow(key), prefix);
            } catch (Exception ignored) {
                // fall through
            }
        }
        return List.of();
    }

    private static List<GoalPickerScreen.Option> optionsFrom(net.minecraft.core.Registry<?> registry, String prefix) {
        List<GoalPickerScreen.Option> result = new ArrayList<>();
        for (var entry : registry.entrySet()) {
            String location = entry.getKey().location().toString();
            String key = entry.getKey().location().toLanguageKey(prefix);
            String name = Component.translatable(key).getString();
            if (name.equals(key)) {
                name = location;
            }
            result.add(new GoalPickerScreen.Option(location, name + " · " + location, null, null));
        }
        result.sort(Comparator.comparing(GoalPickerScreen.Option::value));
        return result;
    }

    private List<QuestEntry> visibleEntries() {
        // 管理界面：全部任务（每个任务一行，取最快周期的实例）；玩家视图：当前页签的实例
        if (view.adminMode()) {
            return QuestEntry.uniqueByQuest(view.entries());
        }
        return view.entries().stream()
                .filter(entry -> entry.group().equals(tab.name()))
                .filter(entry -> entry.definition().enabled())
                .filter(this::prereqsVisible)
                .toList();
    }

    private boolean prereqsVisible(QuestEntry entry) {
        for (String prereqId : entry.definition().prerequisites()) {
            boolean found = false;
            boolean anyClaimed = false;
            for (QuestEntry other : view.entries()) {
                if (other.definition().id().equals(prereqId)) {
                    found = true;
                    anyClaimed |= other.claimed();
                }
            }
            // Any claimed pool instance counts (mirrors the server); a prereq missing from the
            // list also counts as met — the quest was deleted or the snapshot filtered it out.
            if (found && !anyClaimed) {
                return false;
            }
        }
        return true;
    }

    private QuestEntry selectedEntry() {
        List<QuestEntry> entries = visibleEntries();
        return selected >= 0 && selected < entries.size() ? entries.get(selected) : null;
    }

    private int firstIndex() {
        return visibleEntries().isEmpty() ? -1 : 0;
    }

    private int completedCount() {
        return (int) visibleEntries().stream().filter(QuestEntry::complete).count();
    }

    private String goalShort(QuestDefinition quest) {
        return switch (quest.goalType()) {
            case KILL -> "击杀 · " + Math.max(1, quest.goalIds().size()) + "种";
            case COLLECT -> "收集 · " + Math.max(1, quest.goalIds().size()) + "种";
            case BIOME -> "群系 · " + Math.max(1, quest.goalIds().size()) + "种";
            case STRUCTURE -> "结构 · " + Math.max(1, quest.goalIds().size()) + "种";
            case MANUAL -> "手动推进";
        };
    }

    private String typeShort(QuestDefinition quest) {
        StringBuilder joined = new StringBuilder();
        for (QuestType questType : quest.types()) {
            if (joined.length() > 0) {
                joined.append('+');
            }
            joined.append(switch (questType) {
                case DAILY -> "日常";
                case WEEKLY -> "周常";
                case SPECIAL -> "特殊";
                case IDLE -> "闲置";
            });
        }
        return joined.toString();
    }

    private String typesLabel(QuestDefinition quest) {
        if (quest.types().size() == 1) {
            return quest.types().get(0).label();
        }
        return typeShort(quest) + "任务";
    }

    private String goalText(QuestDefinition quest) {
        if (quest.goalType() == QuestGoalType.MANUAL) {
            return "手动推进";
        }
        String prefix = switch (quest.goalType()) {
            case KILL -> "击杀任意 · ";
            case COLLECT -> "收集任意 · ";
            case BIOME -> "探索任意群系 · ";
            case STRUCTURE -> "探索任意结构 · ";
            case MANUAL -> "";
        };
        List<String> ids = quest.goalIds();
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < ids.size() && i < 3; i++) {
            if (i > 0) {
                names.append("、");
            }
            names.append(goalName(ids.get(i), quest.goalType()));
        }
        if (ids.size() > 3) {
            names.append(" 等").append(ids.size()).append("种");
        }
        return prefix + names;
    }

    private String goalSummary() {
        if (editorGoalType == QuestGoalType.MANUAL) {
            return "手动推进，用 /quests progress 命令或模组调用";
        }
        if (editorGoalIds.isEmpty()) {
            return "尚未选择目标，点击右侧按钮选择";
        }
        String prefix = switch (editorGoalType) {
            case KILL -> "击杀任意一个即计数：";
            case COLLECT -> "收集任意一个即计数：";
            case BIOME -> "到达任意一个群系即计数：";
            case STRUCTURE -> "到达任意一个结构即计数：";
            case MANUAL -> "";
        };
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < editorGoalIds.size() && i < 3; i++) {
            if (i > 0) {
                names.append("、");
            }
            names.append(goalName(editorGoalIds.get(i), editorGoalType));
        }
        if (editorGoalIds.size() > 3) {
            names.append(" 等").append(editorGoalIds.size()).append("种");
        }
        return prefix + names;
    }

    private String goalName(String id, QuestGoalType type) {
        ResourceLocation parsed = ResourceLocation.tryParse(id);
        if (parsed == null) {
            return id;
        }
        if (type == QuestGoalType.KILL) {
            EntityType<?> type1 = ForgeRegistries.ENTITY_TYPES.getValue(parsed);
            return type1 == null ? id : type1.getDescription().getString();
        }
        if (type == QuestGoalType.BIOME || type == QuestGoalType.STRUCTURE) {
            String prefix = type == QuestGoalType.BIOME ? "biome" : "structure";
            String key = parsed.toLanguageKey(prefix);
            String name = Component.translatable(key).getString();
            return name.equals(key) ? id : name;
        }
        Item item = ForgeRegistries.ITEMS.getValue(parsed);
        return item == null || item == Items.AIR ? id : new ItemStack(item).getHoverName().getString();
    }

    private ItemStack rewardIcon(QuestReward reward) {
        return switch (reward.kind()) {
            case MONEY -> EntityIconRenderer.itemIcon(QuestConstants.MONEY_ID, new ItemStack(Items.EMERALD));
            case ITEM -> EntityIconRenderer.itemIcon(ResourceLocation.tryParse(reward.value()), new ItemStack(Items.BARRIER));
            case COMMAND -> new ItemStack(Items.COMMAND_BLOCK);
            case XP -> new ItemStack(Items.EXPERIENCE_BOTTLE);
        };
    }

    private String rewardText(QuestReward reward) {
        if (reward.kind() == QuestRewardKind.ITEM) {
            String name = reward.value();
            ResourceLocation parsed = ResourceLocation.tryParse(reward.value());
            if (parsed != null) {
                Item item = ForgeRegistries.ITEMS.getValue(parsed);
                if (item != null && item != Items.AIR) {
                    name = new ItemStack(item).getHoverName().getString();
                }
            }
            return name + " ×" + reward.amount();
        }
        return reward.describe();
    }

    private String rewardValueHint() {
        return switch (editorRewardKind) {
            case ITEM -> "物品 ID，如 minecraft:diamond";
            case COMMAND -> "命令，%player% = 玩家名";
            default -> "此类型无需填写";
        };
    }

    private int parseInt(EditBox box, int fallback) {
        try {
            return Integer.parseInt(box.getValue().strip());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int detailX() {
        return panelX + sidebarWidth + 12;
    }

    private int detailWidth() {
        return panelWidth - sidebarWidth - 24;
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

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void renderSegment(GuiGraphics graphics, int x, int y, int width, String text, boolean active) {
        graphics.fill(x, y, x + width, y + 18, active ? ACCENT : CELL);
        border(graphics, x, y, width, 18, active ? GOLD : BORDER_DIM);
        centered(graphics, text, x, y, width, 18, active ? TEXT : MUTED);
    }

    private void fieldBox(GuiGraphics graphics, int x, int y, int width) {
        graphics.fill(x, y, x + width, y + 18, CELL);
        border(graphics, x, y, width, 18, BORDER);
    }

    private void button(GuiGraphics graphics, int x, int y, int width, int height, int color, String text) {
        graphics.fill(x, y, x + width, y + height, CELL);
        border(graphics, x, y, width, height, color);
        centered(graphics, text, x, y, width, height, color);
    }

    private void border(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y, x + 1, y + height, color);
        graphics.fill(x + width - 1, y, x + width, y + height, color);
    }

    private void centered(GuiGraphics graphics, String text, int x, int y, int width, int height, int color) {
        String value = trim(text, width - 8);
        graphics.drawString(font, value, x + (width - font.width(value)) / 2, y + (height - font.lineHeight) / 2, color, false);
    }

    private String trim(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(1, maxWidth - 3)) + "...";
    }

    private boolean in(int x, int y, int width, int height, double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private record QuestSnapshotS2CView(boolean adminMode, List<QuestEntry> entries) {
    }
}
