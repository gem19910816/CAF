package com.gearsandflesh.quests.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.function.Consumer;

/** Reads the player's live inventory in a grid and hands back the picked item's registry id. */
public final class InventoryPickerScreen extends Screen {
    private static final int BACKDROP = 0x75000000;
    private static final int PANEL = 0xEE0D1A22;
    private static final int CELL = 0xB8152731;
    private static final int CELL_HOVER = 0xE11B3441;
    private static final int BORDER = 0xFF37596A;
    private static final int TEXT = 0xFFE8F1F4;
    private static final int MUTED = 0xFF9CB0B9;
    private static final int GOLD = 0xFFF4CE59;

    private static final int CELL_SIZE = 22;
    private static final int COLS = 9;

    private final QuestScreen parent;
    private final Consumer<String> onPick;
    private int left;
    private int top;
    private int right;
    private int bottom;
    private int gridTop;

    public InventoryPickerScreen(QuestScreen parent, Consumer<String> onPick) {
        super(Component.literal("选择背包物品"));
        this.parent = parent;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        int rows = 4;
        int gridWidth = COLS * CELL_SIZE;
        int panelWidth = gridWidth + 20;
        int panelHeight = 44 + rows * CELL_SIZE + CELL_SIZE + 12;
        left = (width - panelWidth) / 2;
        right = left + panelWidth;
        top = (height - panelHeight) / 2;
        bottom = top + panelHeight;
        gridTop = top + 36;
    }

    private ItemStack slotAt(int index) {
        var player = minecraft == null ? null : minecraft.player;
        if (player == null) {
            return ItemStack.EMPTY;
        }
        if (index < 36) {
            return player.getInventory().getItem(index);
        }
        return player.getOffhandItem();
    }

    private int slotCount() {
        return 37;
    }

    private int indexOf(double mouseX, double mouseY) {
        int col = (int) ((mouseX - (left + 10)) / CELL_SIZE);
        int row = (int) ((mouseY - gridTop) / CELL_SIZE);
        if (col < 0 || col >= COLS || row < 0 || row > 4) {
            return -1;
        }
        int index;
        if (row < 3) {
            index = 9 + row * COLS + col;
        } else if (row == 3) {
            index = col;
        } else {
            index = col == 4 ? 36 : -1;
        }
        return index;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, BACKDROP);
        graphics.fill(left, top, right, bottom, PANEL);
        border(graphics, left, top, right - left, bottom - top, BORDER);
        graphics.fill(left, top, right, top + 2, GOLD);

        graphics.drawCenteredString(font, "选择背包物品 · 点一下即选用", (left + right) / 2, top + 12, TEXT);
        graphics.drawString(font, "物品栏", left + 10, gridTop - 10, MUTED, false);

        for (int index = 0; index < slotCount(); index++) {
            int row = index < 36 ? (index < 9 ? 3 + 0 : (index - 9) / COLS) : 4;
            int col = index < 36 ? (index < 9 ? index : (index - 9) % COLS) : 4;
            int cellX = left + 10 + col * CELL_SIZE;
            int cellY = gridTop + row * CELL_SIZE;
            boolean hovered = index == indexOf(mouseX, mouseY);
            graphics.fill(cellX, cellY, cellX + CELL_SIZE - 2, cellY + CELL_SIZE - 2,
                    hovered ? CELL_HOVER : CELL);
            border(graphics, cellX, cellY, CELL_SIZE - 2, CELL_SIZE - 2, hovered ? GOLD : BORDER);
            ItemStack stack = slotAt(index);
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, cellX + 2, cellY + 2);
                if (stack.getCount() > 1) {
                    graphics.drawString(font, Integer.toString(stack.getCount()),
                            cellX + CELL_SIZE - 2 - font.width(Integer.toString(stack.getCount())) - 1,
                            cellY + CELL_SIZE - 11, stack.getCount() > 99 ? GOLD : TEXT, false);
                }
            }
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int index = indexOf(mouseX, mouseY);
        if (index >= 0) {
            ItemStack stack = slotAt(index);
            if (!stack.isEmpty()) {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (id != null) {
                    onPick.accept(id.toString());
                    Minecraft.getInstance().setScreen(parent);
                    return true;
                }
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
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

    private void border(GuiGraphics graphics, int x, int y, int w, int h, int color) {
        graphics.fill(x, y, x + w, y + 1, color);
        graphics.fill(x, y + h - 1, x + w, y + h, color);
        graphics.fill(x, y, x + 1, y + h, color);
        graphics.fill(x + w - 1, y, x + w, y + h, color);
    }
}
