package com.chaosz.tarkovstamina.backpack.client;

import com.chaosz.tarkovstamina.backpack.menu.BackpackMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/** CAF backpack screen. Military backpack keeps the original wide 12x9 layout;
 *  smaller packs use a self-drawn vanilla-style slot texture (no vanilla offset quirks). */
public class BackpackScreen extends AbstractContainerScreen<BackpackMenu> {
    private static final ResourceLocation CONTAINER_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");

    public BackpackScreen(BackpackMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        int rows = menu.getRows();
        if (menu.getColumns() == BackpackMenu.MILITARY_COLUMNS) {
            this.imageWidth = 238;
            this.imageHeight = 24 + 9 * 18 + 96;
            this.inventoryLabelY = 24 + 9 * 18 + 6;
        } else {
            this.imageWidth = 176;
            // 与原版 generic_54 布局一致: 17px 标题+rows*18 格子 + 96px 玩家背包区
            this.imageHeight = 17 + rows * 18 + 96;
            this.inventoryLabelY = 17 + rows * 18 + 6;
        }
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTicks, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;

        if (this.menu.getColumns() == BackpackMenu.MILITARY_COLUMNS) {
            // 军用背包: 12x9 宽版，标题栏加高到 24px（原版 17px 偏窄），格子区从 y=24 开始。
            int rows = this.menu.getRows();
            // 标题栏（宽版: 左7 + 中间重复 + 右7）
            blitWideStrip(g, x + 8, y + 7, 0, 24);
            // 背包格子区
            for (int row = 0; row < rows; row++) {
                boolean last = (row == rows - 1);
                blitWideStrip(g, x + 8, y + 24 + row * 18, last ? 107 : 17, 18);
            }
            // 玩家背包区（居中，原版 176 宽）
            int playerX = x + 36;
            int playerY = y + 24 + rows * 18;
            g.blit(CONTAINER_TEXTURE, playerX, playerY, 0, 126, 176, 73, 256, 256);
            g.blit(CONTAINER_TEXTURE, playerX, playerY + 73, 0, 197, 176, 18, 256, 256);
            g.blit(CONTAINER_TEXTURE, playerX, playerY + 91, 0, 215, 176, 7, 256, 256);
            return;
        }

        // 小背包：与军用背包完全一致的原版 generic_54 像素，只是行数更少。
        // 原版布局: 标题栏 0..17, 格子区 17..125 (6行18px), 玩家背包区 126..222。
        int rows = this.menu.getRows();
        g.blit(CONTAINER_TEXTURE, x, y, 0, 0, 176, 17, 256, 256);          // 标题栏
        for (int row = 0; row < rows; row++) {
            boolean last = (row == rows - 1);
            // 前 N-1 行用普通行纹理(17..35)，最后一行用末行纹理(107..125) 带下边框
            g.blit(CONTAINER_TEXTURE, x, y + 17 + row * 18, 0, last ? 107 : 17, 176, 18, 256, 256);
        }
        g.blit(CONTAINER_TEXTURE, x, y + 17 + rows * 18, 0, 126, 176, 96, 256, 256);  // 玩家背包区
    }

    private void blitWideStrip(GuiGraphics g, int x, int y, int sourceY, int height) {
        g.blit(CONTAINER_TEXTURE, x, y, 0, sourceY, 7, height, 256, 256);
        g.blit(CONTAINER_TEXTURE, x + 7, y, 7, sourceY, 162, height, 256, 256);
        g.blit(CONTAINER_TEXTURE, x + 169, y, 7, sourceY, 54, height, 256, 256);
        g.blit(CONTAINER_TEXTURE, x + 223, y, 169, sourceY, 7, height, 256, 256);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        boolean military = this.menu.getColumns() == BackpackMenu.MILITARY_COLUMNS;
        int titleX = military ? 15 : 8;
        int titleY = military ? 13 : 6;
        g.drawString(this.font, this.title, titleX, titleY, 0x404040, false);
        int inventoryLabelX = military ? 43 : 8;
        g.drawString(this.font, Component.translatable("container.inventory"), inventoryLabelX,
                this.inventoryLabelY, 0x404040, false);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (BackpackClientRegistration.isBackpackKey(keyCode, scanCode)) {
            this.minecraft.setScreen(null);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
