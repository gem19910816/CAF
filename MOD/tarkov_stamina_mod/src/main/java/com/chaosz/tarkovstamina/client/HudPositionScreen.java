package com.chaosz.tarkovstamina.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 体力条位置调整界面
 * <p>
 * 显示当前体力条位置，按住左键拖动即可移动，松开自动保存。
 * 偏移量使用屏幕比例存储，切换 GUI 缩放后体力条始终锚定在屏幕同一相对位置。
 * 条的大小与 StaminaHud 一致，按屏幕比例计算。
 * 默认位置：屏幕左下角。
 * </p>
 */
public final class HudPositionScreen extends Screen {
    private boolean dragging;

    public HudPositionScreen() {
        super(Component.literal("调整体力条位置"));
    }

    @Override public boolean isPauseScreen() { return false; }

    private int barWidth() {
        return HudConfig.width(this.width);
    }

    private int barHeight() {
        return HudConfig.height(this.height);
    }

    private int anchorX() {
        return HudConfig.anchorX(this.width);
    }

    private int anchorY() {
        return HudConfig.anchorY(this.height);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        int bw = barWidth();
        int bh = barHeight();
        int x = anchorX() + HudPositionConfig.offsetX(this.width);
        int y = anchorY() + HudPositionConfig.offsetY(this.height);

        // 体力条本体
        g.fill(x - 1, y - 1, x + bw + 1, y + bh + 1, 0xE0000000);
        g.fill(x, y, x + bw, y + bh, 0x80FFFFFF);
        g.fill(x, y, x + Math.round(bw * 0.8F), y + bh, 0xFF000000 | HudConfig.barColor());

        // 顶部提示
        g.drawString(this.font, "§l按住左键拖动体力条到想要的位置", (this.width - this.font.width("按住左键拖动体力条到想要的位置")) / 2, 20, 0xFFE8EEEB, false);
        g.drawString(this.font, "§7松开鼠标自动保存  ·  按返回键退出调整", (this.width - this.font.width("松开鼠标自动保存  ·  按返回键退出调整")) / 2, 32, 0xFF7A8A86, false);
        String position = "当前位置: X=" + HudPositionConfig.offsetX(this.width)
                + " Y=" + HudPositionConfig.offsetY(this.height);
        g.drawString(this.font, "§7" + position,
                (this.width - this.font.width(position)) / 2, 44, 0xFF7A8A86, false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn == 0) {
            int bw = barWidth();
            int bh = barHeight();
            int x = anchorX() + HudPositionConfig.offsetX(this.width);
            int y = anchorY() + HudPositionConfig.offsetY(this.height);
            if (mx >= x - 10 && mx <= x + bw + 30 && my >= y - 12 && my <= y + 12) {
                dragging = true;
                return true;
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dragX, double dragY) {
        if (dragging && btn == 0) {
            int newX = (int) Math.round(mx - HudConfig.anchorX(this.width) - barWidth() / 2.0);
            int newY = (int) Math.round(my - (HudConfig.anchorY(this.height) + barHeight() / 2.0));
            HudPositionConfig.set(newX, newY);
            return true;
        }
        return super.mouseDragged(mx, my, btn, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        dragging = false;
        return super.mouseReleased(mx, my, btn);
    }
}
