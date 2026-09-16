package com.gearsandflesh.quests.client;

import com.gearsandflesh.quests.QuestConstants;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** Full-screen searchable multi-select picker for kill/collect goals, with live entity or item icons. */
public final class GoalPickerScreen extends Screen {
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
    private static final int ROW_HEIGHT = 24;

    private final QuestScreen parent;
    private final boolean itemMode;
    private final List<Option> allOptions;
    private final Set<String> selected = new LinkedHashSet<>();
    private final Consumer<List<String>> onDone;
    private List<Option> filtered = List.of();
    private EditBox searchBox;
    private int left;
    private int top;
    private int right;
    private int bottom;
    private int listTop;
    private int listBottom;
    private double scroll;
    private boolean dragging;
    private double dragStartY;
    private double dragStartScroll;

    public record Option(String value, String label, EntityType<?> entity, ItemStack icon) {
    }

    public GoalPickerScreen(QuestScreen parent, boolean itemMode, Component title, List<Option> options,
                            List<String> initialSelection, Consumer<List<String>> onDone) {
        super(title);
        this.parent = parent;
        this.itemMode = itemMode;
        this.onDone = onDone;
        this.allOptions = new ArrayList<>(options);
        this.selected.addAll(initialSelection);
        Set<String> known = new LinkedHashSet<>();
        for (Option option : allOptions) {
            known.add(option.value());
        }
        for (String value : initialSelection) {
            if (!known.contains(value)) {
                allOptions.add(new Option(value, value, null, null));
            }
        }
        allOptions.sort((a, b) -> a.value().compareToIgnoreCase(b.value()));
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(620, Math.max(300, width - 12));
        int panelHeight = Math.min(344, Math.max(210, height - 12));
        left = (width - panelWidth) / 2;
        right = left + panelWidth;
        top = (height - panelHeight) / 2;
        bottom = top + panelHeight;
        listTop = top + 50;
        listBottom = bottom - 32;

        removeWidget(searchBox);
        searchBox = new EditBox(font, left + 10, top + 28, panelWidth - 20, 12, Component.literal("搜索"));
        searchBox.setMaxLength(128);
        searchBox.setBordered(false);
        searchBox.setTextColor(TEXT);
        searchBox.setHint(Component.literal("搜索 ID 或名称").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        searchBox.setResponder(value -> applyFilter());
        addRenderableWidget(searchBox);
        applyFilter();
    }

    private void applyFilter() {
        String query = searchBox == null ? "" : searchBox.getValue().toLowerCase(Locale.ROOT).strip();
        List<Option> result = new ArrayList<>();
        for (Option option : allOptions) {
            if (query.isEmpty() || option.value().toLowerCase(Locale.ROOT).contains(query)
                    || option.label().toLowerCase(Locale.ROOT).contains(query)) {
                result.add(option);
            }
        }
        filtered = result;
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    private int contentHeight() {
        return filtered.size() * ROW_HEIGHT;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - (listBottom - listTop));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        calculateLayoutIfResized();
        graphics.fill(0, 0, width, height, BACKDROP);
        graphics.fill(left, top, right, bottom, PANEL);
        border(graphics, left, top, right - left, bottom - top, BORDER);
        graphics.fill(left, top, right, top + 2, GOLD);

        String heading = title.getString() + " · 已选 " + selected.size() + "/" + QuestConstants.MAX_GOALS;
        graphics.drawCenteredString(font, heading, (left + right) / 2, top + 10, TEXT);
        graphics.fill(left + 10, top + 42, right - 10, top + 43, BORDER_DIM);

        graphics.enableScissor(left + 8, listTop, right - 8, listBottom);
        int baseY = listTop - (int) scroll;
        for (int i = 0; i < filtered.size(); i++) {
            Option option = filtered.get(i);
            int rowY = baseY + i * ROW_HEIGHT;
            if (rowY + ROW_HEIGHT < listTop || rowY > listBottom) {
                continue;
            }
            boolean checked = selected.contains(option.value());
            boolean hovered = mouseX >= left + 8 && mouseX < right - 12 && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            graphics.fill(left + 8, rowY, right - 12, rowY + ROW_HEIGHT - 1,
                    checked ? CELL_HOVER : hovered ? CELL_HOVER : CELL);
            if (checked) {
                graphics.fill(left + 8, rowY, left + 10, rowY + ROW_HEIGHT - 1, GOLD);
            }
            int boxX = left + 16;
            graphics.fill(boxX, rowY + 6, boxX + 12, rowY + 18, checked ? ACCENT : PANEL_SOFT);
            border(graphics, boxX, rowY + 6, 12, 12, checked ? GOLD : BORDER_DIM);
            if (checked) {
                graphics.drawString(font, "✓", boxX + 2, rowY + 7, GOLD, false);
            }
            int iconX = boxX + 20;
            if (option.entity() != null) {
                EntityIconRenderer.renderEntity(graphics, option.entity(), iconX + 8, rowY + 21, 18);
            } else if (option.icon() != null) {
                graphics.renderItem(option.icon(), iconX, rowY + 3);
            }
            graphics.drawString(font, trim(option.label(), right - 12 - iconX - 30), iconX + 24, rowY + 8,
                    checked ? TEXT : MUTED, false);
        }
        graphics.disableScissor();

        if (maxScroll() > 0) {
            int visible = listBottom - listTop;
            int barHeight = Math.max(20, visible * visible / contentHeight());
            int barY = listTop + (int) (scroll / maxScroll() * (visible - barHeight));
            graphics.fill(right - 8, listTop, right - 4, listBottom, 0x40000000);
            graphics.fill(right - 8, barY, right - 4, barY + barHeight, 0xAAFFFFFF);
        }

        int buttonY = bottom - 26;
        button(graphics, left + 8, buttonY, 64, 18, TEXT, "全选可见");
        button(graphics, left + 76, buttonY, 48, 18, TEXT, "清空");
        if (itemMode) {
            button(graphics, left + 128, buttonY, 64, 18, GOLD, "取手持");
        }
        button(graphics, right - 124, buttonY, 56, 18, MUTED, "取消");
        button(graphics, right - 64, buttonY, 56, 18, GOLD, "确定");
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void calculateLayoutIfResized() {
        int expectedWidth = Math.min(620, Math.max(300, width - 12));
        int expectedHeight = Math.min(344, Math.max(210, height - 12));
        if (right - left == expectedWidth && bottom - top == expectedHeight) {
            return;
        }
        init(minecraft, width, height);
    }

    private void toggle(String value) {
        if (!selected.remove(value)) {
            selected.add(value);
        }
    }

    /** Adds the item the player is currently holding (main hand first, then off hand). */
    private void addHeldItem() {
        if (minecraft.player == null) {
            return;
        }
        ItemStack held = minecraft.player.getMainHandItem();
        if (held.isEmpty()) {
            held = minecraft.player.getOffhandItem();
        }
        if (held.isEmpty()) {
            return;
        }
        net.minecraft.resources.ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.ITEMS
                .getKey(held.getItem());
        if (id != null) {
            selected.add(id.toString());
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (maxScroll() > 0) {
            int visible = listBottom - listTop;
            int barHeight = Math.max(20, visible * visible / contentHeight());
            int barY = listTop + (int) (scroll / maxScroll() * (visible - barHeight));
            if (mouseX >= right - 10 && mouseX <= right - 2 && mouseY >= barY && mouseY < barY + barHeight) {
                dragging = true;
                dragStartY = mouseY;
                dragStartScroll = scroll;
                return true;
            }
        }
        if (mouseX >= left + 8 && mouseX < right - 12 && mouseY >= listTop && mouseY < listBottom) {
            int index = (int) ((mouseY - listTop + scroll) / ROW_HEIGHT);
            if (index >= 0 && index < filtered.size()) {
                toggle(filtered.get(index).value());
                return true;
            }
        }
        int buttonY = bottom - 26;
        if (in(left + 8, buttonY, 64, 18, mouseX, mouseY)) {
            for (Option option : filtered) {
                selected.add(option.value());
            }
            return true;
        }
        if (in(left + 76, buttonY, 48, 18, mouseX, mouseY)) {
            selected.clear();
            return true;
        }
        if (itemMode && in(left + 128, buttonY, 64, 18, mouseX, mouseY)) {
            addHeldItem();
            return true;
        }
        if (in(right - 124, buttonY, 56, 18, mouseX, mouseY)) {
            Minecraft.getInstance().setScreen(parent);
            return true;
        }
        if (in(right - 64, buttonY, 56, 18, mouseX, mouseY)) {
            onDone.accept(new ArrayList<>(selected));
            Minecraft.getInstance().setScreen(parent);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging) {
            int visible = listBottom - listTop;
            int barHeight = Math.max(20, visible * visible / contentHeight());
            int track = visible - barHeight;
            if (track > 0) {
                scroll = Math.max(0, Math.min(dragStartScroll + (mouseY - dragStartY) / track * maxScroll(), maxScroll()));
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= left && mouseX <= right && mouseY >= listTop && mouseY <= listBottom) {
            scroll = Math.max(0, Math.min(scroll - delta * ROW_HEIGHT * 2, maxScroll()));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_ESCAPE) {
            Minecraft.getInstance().setScreen(parent);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void button(GuiGraphics graphics, int x, int y, int w, int h, int color, String text) {
        graphics.fill(x, y, x + w, y + h, CELL);
        border(graphics, x, y, w, h, color);
        graphics.drawCenteredString(font, text, x + w / 2, y + (h - 8) / 2, color);
    }

    private void border(GuiGraphics graphics, int x, int y, int w, int h, int color) {
        graphics.fill(x, y, x + w, y + 1, color);
        graphics.fill(x, y + h - 1, x + w, y + h, color);
        graphics.fill(x, y, x + 1, y + h, color);
        graphics.fill(x + w - 1, y, x + w, y + h, color);
    }

    private String trim(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(1, maxWidth - 3)) + "...";
    }

    private boolean in(int x, int y, int w, int h, double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
