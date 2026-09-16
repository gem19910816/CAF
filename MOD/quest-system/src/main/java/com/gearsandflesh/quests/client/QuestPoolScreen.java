package com.gearsandflesh.quests.client;

import com.gearsandflesh.quests.QuestConstants;
import com.gearsandflesh.quests.data.QuestDefinition;
import com.gearsandflesh.quests.data.QuestEntry;
import com.gearsandflesh.quests.data.QuestGoalType;
import com.gearsandflesh.quests.data.QuestReward;
import com.gearsandflesh.quests.data.QuestRewardKind;
import com.gearsandflesh.quests.data.QuestType;
import com.gearsandflesh.quests.network.DeleteQuestC2S;
import com.gearsandflesh.quests.network.PublishQuestsC2S;
import com.gearsandflesh.quests.network.QuestNetwork;
import com.gearsandflesh.quests.network.QuestSnapshotS2C;
import com.gearsandflesh.quests.network.SaveQuestC2S;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/** Dedicated quest-pool console: every created quest lands here; the admin assigns each one to a
 *  group (daily / weekly / special) and publishes. Editing happens back in the admin screen. */
public final class QuestPoolScreen extends Screen {
    private static final int BACKDROP = 0x75000000;
    private static final int PANEL = 0xEE0D1A22;
    private static final int PANEL_SOFT = 0xDD11232D;
    private static final int CELL = 0xB8152731;
    private static final int CELL_HOVER = 0xE11B3441;
    private static final int BORDER = 0xFF37596A;
    private static final int BORDER_DIM = 0xFF29434F;
    private static final int ACCENT = 0xFF19799B;
    private static final int TEXT = 0xFFE8F1F4;
    private static final int MUTED = 0xFF9CB0B9;
    private static final int GOLD = 0xFFF4CE59;
    private static final int GREEN = 0xFF63D8A3;
    private static final int RED = 0xFFDF6C73;

    private final QuestScreen parent;
    private List<QuestEntry> entries = List.of();
    private int selected;
    private int listScroll;
    private boolean confirmDelete;

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int contentTop;
    private int contentBottom;
    private int sidebarWidth;

    public QuestPoolScreen(QuestScreen parent) {
        super(Component.literal("任务池"));
        this.parent = parent;
        QuestSnapshotS2C cached = ClientQuestState.snapshot();
        if (cached != null) {
            apply(cached);
        }
    }

    public void apply(QuestSnapshotS2C message) {
        // The snapshot carries one entry per pool instance; management shows one row per quest.
        entries = QuestEntry.uniqueByQuest(message.entries());
        if (selected >= entries.size()) {
            selected = entries.size() - 1;
        }
        if (selected < 0 && !entries.isEmpty()) {
            selected = 0;
        }
        confirmDelete = false;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(620, Math.max(300, width - 12));
        panelHeight = Math.min(344, Math.max(210, height - 12));
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        contentTop = panelY + 33;
        contentBottom = panelY + panelHeight - 6;
        sidebarWidth = Math.min(150, Math.max(110, panelWidth / 4));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        init();
        graphics.fill(0, 0, width, height, BACKDROP);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL);
        border(graphics, panelX, panelY, panelWidth, panelHeight, BORDER);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 2, GOLD);

        renderHeader(graphics, mouseX, mouseY);
        renderList(graphics, mouseX, mouseY);
        renderDetails(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderHeader(GuiGraphics graphics, int mouseX, int mouseY) {
        int y = panelY + 5;
        int h = 23;
        graphics.drawString(font, "任务池 · 共 " + entries.size() + " 个任务", panelX + 10, y + 8, GOLD, false);

        int closeWidth = 19;
        int closeX = panelX + panelWidth - 6 - closeWidth;
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
        graphics.drawString(font, "全部任务", x + 6, y + 6, TEXT, false);

        int top = y + 24;
        int bottom = y + height - 6;
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
            graphics.drawString(font, trim(quest.title(), width - 30), x + 14, yy + 5,
                    quest.enabled() ? TEXT : MUTED, false);
            String status = typeShort(quest) + "·" + goalShort(quest);
            graphics.drawString(font, trim(status, width - 24), x + 14, yy + 17, MUTED, false);
            if (!quest.enabled()) {
                graphics.drawString(font, "停用", x + width - 32, yy + 17, RED, false);
            }
        }
    }

    private void renderDetails(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = panelX + sidebarWidth + 12;
        int y = contentTop;
        int width = panelX + panelWidth - 6 - x;
        int height = contentBottom - y;
        graphics.fill(x, y, x + width, y + height, PANEL_SOFT);
        border(graphics, x, y, width, height, BORDER_DIM);
        QuestEntry entry = selectedEntry();
        if (entry == null) {
            centered(graphics, "任务池为空，先去编辑任务创建", x, y, width, height, MUTED);
            return;
        }
        QuestDefinition quest = entry.definition();

        graphics.drawString(font, trim(quest.title(), width - 120), x + 12, y + 10, TEXT, false);
        graphics.drawString(font, typesLabel(quest), x + width - 80, y + 10, GOLD, false);
        graphics.fill(x + 10, y + 24, x + width - 10, y + 25, BORDER_DIM);

        graphics.drawString(font, trim(quest.description().isEmpty() ? "（无描述）" : quest.description(), width - 24),
                x + 12, y + 34, MUTED, false);
        graphics.drawString(font, "目标", x + 12, y + 57, MUTED, false);
        graphics.fill(x + 10, y + 70, x + width - 10, y + 93, CELL);
        border(graphics, x + 10, y + 70, width - 20, 23, BORDER_DIM);
        graphics.drawString(font, trim(goalText(quest), width - 96), x + 18, y + 75, TEXT, false);
        graphics.drawString(font, "×" + quest.target(), x + width - 56, y + 75, GOLD, false);
        if (!quest.prerequisites().isEmpty()) {
            graphics.drawString(font, "前置：" + quest.prerequisites().size() + " 个任务", x + 12, y + 100, MUTED, false);
        }

        graphics.drawString(font, "奖励", x + 12, y + 118, MUTED, false);
        List<QuestReward> rewards = quest.rewards();
        if (rewards.isEmpty()) {
            graphics.drawString(font, "（未配置奖励）", x + 14, y + 132, MUTED, false);
        } else {
            int shown = Math.min(4, rewards.size());
            for (int i = 0; i < shown; i++) {
                QuestReward reward = rewards.get(i);
                int rowY = y + 130 + i * 17;
                graphics.fill(x + 10, rowY, x + width - 10, rowY + 16, CELL);
                border(graphics, x + 10, rowY, width - 20, 16, BORDER_DIM);
                graphics.renderItem(rewardIcon(reward), x + 14, rowY);
                graphics.drawString(font, trim(rewardText(reward), width - 70), x + 34, rowY + 4, TEXT, false);
            }
            if (rewards.size() > shown) {
                graphics.drawString(font, "…等 " + rewards.size() + " 条奖励", x + 14, y + 130 + shown * 17 + 2, MUTED, false);
            }
        }

        int segY = y + height - 100;
        graphics.drawString(font, "推送到任务组（可多选）", x + 12, segY - 11, GOLD, false);
        renderSegments(graphics, x + 12, segY, width - 24, groupLabels(), quest.types());

        int bottom = y + height;
        button(graphics, x + 10, bottom - 56, 106, 20, quest.enabled() ? GREEN : MUTED,
                quest.enabled() ? "启用中" : "已停用");
        if (confirmDelete) {
            int dx = x + width - 116;
            graphics.fill(dx, bottom - 56, dx + 106, bottom - 36, 0xFF73313A);
            border(graphics, dx, bottom - 56, 106, 20, RED);
            centered(graphics, "确认删除？", dx, bottom - 56, 106, 20, RED);
        } else {
            button(graphics, x + width - 116, bottom - 56, 106, 20, RED, "删除任务");
        }
        button(graphics, x + 10, bottom - 30, 106, 20, TEXT, "预览玩家视角");
        button(graphics, x + width - 116, bottom - 30, 106, 20, GREEN, "立即推送");
    }

    private String[] groupLabels() {
        String[] labels = new String[QuestType.values().length];
        for (int i = 0; i < QuestType.values().length; i++) {
            labels[i] = switch (QuestType.values()[i]) {
                case DAILY -> "日常任务组";
                case WEEKLY -> "周常任务组";
                case SPECIAL -> "特殊任务组";
                case IDLE -> "闲置组";
            };
        }
        return labels;
    }

    private void renderSegments(GuiGraphics graphics, int x, int y, int width, String[] labels, List<QuestType> active) {
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

    private int segmentAt(int x, int y, int width, int count, double mouseX, double mouseY) {
        if (!in(x, y, width, 18, mouseX, mouseY)) {
            return -1;
        }
        int index = (int) ((mouseX - x) / ((double) width / count));
        return Math.max(0, Math.min(count - 1, index));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int closeX = panelX + panelWidth - 6 - 19;
        if (in(closeX, panelY + 5, 19, 23, mouseX, mouseY)) {
            backToAdmin();
            return true;
        }

        int listX = panelX + 6;
        int listY = contentTop;
        int top = listY + 24;
        int bottom = listY + (contentBottom - listY) - 6;
        int row = 30;
        int maxRows = Math.max(1, (bottom - top - 4) / row);
        int end = Math.min(entries.size(), listScroll + maxRows);
        for (int i = listScroll; i < end; i++) {
            int yy = top + 2 + (i - listScroll) * row;
            if (in(listX + 5, yy, sidebarWidth - 10, row - 2, mouseX, mouseY)) {
                selected = i;
                confirmDelete = false;
                return true;
            }
        }

        QuestEntry entry = selectedEntry();
        if (entry == null) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        QuestDefinition quest = entry.definition();
        int x = panelX + sidebarWidth + 12;
        int width = panelX + panelWidth - 6 - x;
        int y = contentTop;
        int height = contentBottom - y;
        int segY = y + height - 100;
        int seg = segmentAt(x + 12, segY, width - 24, QuestType.values().length, mouseX, mouseY);
        if (seg >= 0) {
            QuestType toggled = QuestType.values()[seg];
            java.util.LinkedHashSet<QuestType> newTypes = new java.util.LinkedHashSet<>(quest.types());
            if (newTypes.contains(toggled)) {
                if (newTypes.size() <= 1) {
                    warn("任务至少要属于一个任务组");
                    return true;
                }
                newTypes.remove(toggled);
            } else {
                newTypes.add(toggled);
            }
            QuestDefinition updated = new QuestDefinition(quest.id(), List.copyOf(newTypes), quest.title(), quest.description(),
                    quest.goalType(), quest.goalIds(), quest.target(), quest.rewards(), quest.enabled(),
                    quest.prerequisites(), quest.revision());
            QuestNetwork.CHANNEL.sendToServer(new SaveQuestC2S(updated));
            warn("任务组已更新（组变更会让玩家进度重新计算）");
            return true;
        }

        int bottomY = y + height;
        if (in(x + 10, bottomY - 56, 106, 20, mouseX, mouseY)) {
            QuestDefinition updated = new QuestDefinition(quest.id(), quest.types(), quest.title(), quest.description(),
                    quest.goalType(), quest.goalIds(), quest.target(), quest.rewards(), !quest.enabled(),
                    quest.prerequisites(), quest.revision());
            QuestNetwork.CHANNEL.sendToServer(new SaveQuestC2S(updated));
            return true;
        }
        if (in(x + width - 116, bottomY - 56, 106, 20, mouseX, mouseY)) {
            if (!confirmDelete) {
                confirmDelete = true;
            } else {
                confirmDelete = false;
                QuestNetwork.CHANNEL.sendToServer(new DeleteQuestC2S(quest.id()));
            }
            return true;
        }
        if (in(x + 10, bottomY - 30, 106, 20, mouseX, mouseY)) {
            ClientQuestState.open(false);
            return true;
        }
        if (in(x + width - 116, bottomY - 30, 106, 20, mouseX, mouseY)) {
            QuestNetwork.CHANNEL.sendToServer(new PublishQuestsC2S());
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int listX = panelX + 6;
        int listY = contentTop;
        if (in(listX, listY, sidebarWidth, contentBottom - listY, mouseX, mouseY)) {
            int top = listY + 24;
            int bottom = contentBottom - 6;
            int maxRows = Math.max(1, (bottom - top - 4) / 30);
            int maxScroll = Math.max(0, entries.size() - maxRows);
            listScroll = Math.max(0, Math.min(maxScroll, listScroll - (int) Math.signum(delta)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private void backToAdmin() {
        ClientQuestState.open(true);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            backToAdmin();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        backToAdmin();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private QuestEntry selectedEntry() {
        return selected >= 0 && selected < entries.size() ? entries.get(selected) : null;
    }

    private void warn(String message) {
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.literal("[任务池] " + message).withStyle(ChatFormatting.GOLD), false);
        }
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

    private String goalShort(QuestDefinition quest) {
        return switch (quest.goalType()) {
            case KILL -> "击杀";
            case COLLECT -> "收集";
            case MANUAL -> "手动";
            case BIOME -> "群系";
            case STRUCTURE -> "结构";
        };
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

    private String goalName(String id, QuestGoalType type) {
        ResourceLocation parsed = ResourceLocation.tryParse(id);
        if (parsed == null) {
            return id;
        }
        if (type == QuestGoalType.KILL) {
            var entityType = ForgeRegistries.ENTITY_TYPES.getValue(parsed);
            return entityType == null ? id : entityType.getDescription().getString();
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
}
