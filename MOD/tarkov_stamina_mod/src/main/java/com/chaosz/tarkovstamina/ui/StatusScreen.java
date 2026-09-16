package com.chaosz.tarkovstamina.ui;

import com.chaosz.tarkovstamina.network.StatusScreenPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** CAF 生存档案：紧凑的分栏状态界面。 */
public final class StatusScreen extends Screen {
    private static final int PANEL = 0xD91C2020;
    private static final int PANEL_DARK = 0xD0131617;
    private static final int LINE = 0xFF4D5551;
    private static final int AMBER = 0xFFE0B35F;
    private static final int PAPER = 0xFFE6E5DE;
    private static final int MUTED = 0xFF9CA39B;
    private static final int RED = 0xFFE2766D;
    private static final int GREEN = 0xFFA6C38B;
    private static final int BLUE = 0xFFA6BBC6;

    private final StatusScreenPacket d;
    private int left, top, panelWidth, panelHeight, active;

    public StatusScreen(StatusScreenPacket data) {
        super(Component.literal("CAF 生存档案"));
        this.d = data;
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        // Match the global-market modal: a bounded panel centered in the viewport.
        panelWidth = Math.min(620, Math.max(300, this.width - 12));
        panelHeight = Math.min(344, Math.max(210, this.height - 12));
        left = (this.width - panelWidth) / 2;
        top = (this.height - panelHeight) / 2;
        g.fill(0, 0, this.width, this.height, 0x36000000);
        g.fill(left, top, left + panelWidth, top + panelHeight, PANEL);
        g.fill(left, top, left + 116, top + panelHeight, PANEL_DARK);
        g.fill(left, top, left + panelWidth, top + 2, AMBER);
        g.fill(left + 116, top, left + 117, top + panelHeight, LINE);

        g.drawString(this.font, "CAF", left + 18, top + 18, AMBER, false);
        g.drawString(this.font, "生存档案", left + 18, top + 31, MUTED, false);
        g.fill(left + 16, top + 48, left + 98, top + 49, LINE);
        nav(g, "体力状态", 0, top + 68, mx, my);
        nav(g, "技能记录", 1, top + 96, mx, my);
        nav(g, "身体状况", 2, top + 124, mx, my);
        nav(g, "生存需求", 3, top + 152, mx, my);
        g.drawString(this.font, "在线", left + 18, top + panelHeight - 27, GREEN, false);
        g.drawString(this.font, "按返回键关闭", left + 18, top + panelHeight - 14, MUTED, false);

        int contentX = left + 128;
        g.drawString(this.font, title(), contentX, top + 18, PAPER, false);
        g.drawString(this.font, "实时资料  /  服务器同步", contentX, top + 31, MUTED, false);
        g.fill(contentX, top + 46, left + panelWidth - 16, top + 47, LINE);
        switch (active) {
            case 0 -> stamina(g, contentX, top + 62, panelWidth - 150);
            case 1 -> skills(g, contentX, top + 62, panelWidth - 150);
            case 2 -> conditions(g, contentX, top + 62, panelWidth - 150);
            case 3 -> survival(g, contentX, top + 62, panelWidth - 150);
        }
        g.fill(contentX, top + panelHeight - 28, left + panelWidth - 16, top + panelHeight - 27, LINE);
        g.drawString(this.font, "/caf 重新打开", contentX, top + panelHeight - 17, MUTED, false);
        g.drawString(this.font, (active + 1) + " / 4", left + panelWidth - 45, top + panelHeight - 17, MUTED, false);
    }

    private void nav(GuiGraphics g, String label, int index, int y, int mx, int my) {
        boolean selected = active == index;
        if (selected) g.fill(left + 10, y - 5, left + 106, y + 17, 0xA24B4230);
        g.fill(left + 16, y + 2, left + 20, y + 6, selected ? AMBER : LINE);
        g.drawString(this.font, label, left + 28, y, selected ? PAPER : MUTED, false);
    }

    private String title() {
        return switch (active) { case 0 -> "体力状态"; case 1 -> "技能记录"; case 2 -> "身体状况"; default -> "生存需求"; };
    }

    private void stamina(GuiGraphics g, int x, int y, int w) {
        float max = Math.max(1, d.maximum());
        float f = Mth.clamp(d.stamina() / max, 0, 1);
        section(g, x, y, w, 82, "当前体力", f <= .2F ? RED : AMBER);
        g.drawString(this.font, Math.round(d.stamina()) + " / " + (int) max, x + 14, y + 27, PAPER, false);
        bar(g, x + 14, y + 45, w - 28, 10, f, f <= .2F ? RED : AMBER);
        g.drawString(this.font, "基因强化 " + d.injectionCount() + " / 3", x + 14, y + 66, MUTED, false);
        section(g, x, y + 94, w / 2 - 5, 66, "锻炼进度", GREEN);
        g.drawString(this.font, "等级 " + d.exerciseLevel() + " / 70", x + 14, y + 122, PAPER, false);
        bar(g, x + 14, y + 142, w / 2 - 33, 6, Mth.clamp(d.exerciseLevel() / 70F, 0, 1), GREEN);
        section(g, x + w / 2 + 5, y + 94, w / 2 - 5, 66, "恢复状态", BLUE);
        g.drawString(this.font, d.cooldown() > 0 ? "等待恢复" : "可恢复", x + w / 2 + 19, y + 122, PAPER, false);
        g.drawString(this.font, d.cooldown() + " 刻", x + w / 2 + 19, y + 142, MUTED, false);
    }

    private void skills(GuiGraphics g, int x, int y, int w) {
        row(g, x, y, w, "木工", d.woodcutCount(), 3000, GREEN);
        row(g, x, y + 48, w, "石工", d.stonecutCount(), 6000, AMBER);
        row(g, x, y + 96, w, "技工", d.mechanicCount(), 200, BLUE);
        row(g, x, y + 144, w, "钓鱼", d.fishExp(), 500, GREEN);
    }

    private void conditions(GuiGraphics g, int x, int y, int w) {
        row(g, x, y, w, "抑郁程度", d.depressionLevel(), 100, d.depressionLevel() >= 70 ? RED : AMBER);
        line(g, x, y + 58, "感冒", d.isSick() ? "已患病" : "健康", d.isSick() ? RED : GREEN);
        line(g, x, y + 88, "烟瘾", d.isSmokeAddicted() ? "已上瘾" : "无", d.isSmokeAddicted() ? AMBER : GREEN);
        line(g, x, y + 118, "酒瘾", d.isAlcoholAddicted() ? "已上瘾" : "无", d.isAlcoholAddicted() ? AMBER : GREEN);
        line(g, x, y + 148, "击杀记录", String.valueOf(d.monsterKills()), PAPER);
    }

    private void survival(GuiGraphics g, int x, int y, int w) {
        long ticks = d.timeSinceLastPoop();
        int need = ticks >= 36000 ? 100 : ticks >= 18000 ? 50 : (int) (ticks / 180.0);
        row(g, x, y, w, "排泄需求", need, 100, need >= 100 ? RED : need >= 50 ? AMBER : GREEN);
        line(g, x, y + 64, "状态", need >= 100 ? "紧急" : need >= 50 ? "有感觉" : "正常", need >= 100 ? RED : PAPER);
        line(g, x, y + 94, "建议", need >= 50 ? "蹲下或使用马桶" : "继续活动", MUTED);
    }

    private void section(GuiGraphics g, int x, int y, int w, int h, String label, int color) {
        g.fill(x, y, x + w, y + h, 0x70282D2C);
        g.fill(x, y, x + w, y + 1, color);
        g.drawString(this.font, label, x + 14, y + 10, color, false);
    }

    private void row(GuiGraphics g, int x, int y, int w, String label, int value, int max, int color) {
        g.drawString(this.font, label, x, y + 5, PAPER, false);
        g.drawString(this.font, value + " / " + max, x + w - this.font.width(value + " / " + max), y + 5, MUTED, false);
        bar(g, x, y + 25, w, 7, Mth.clamp(value / (float) max, 0, 1), color);
    }

    private void line(GuiGraphics g, int x, int y, String label, String value, int color) {
        g.fill(x, y + 23, left + panelWidth - 16, y + 24, 0x554D5551);
        g.drawString(this.font, label, x, y, MUTED, false);
        g.drawString(this.font, value, x + 100, y, color, false);
    }

    private void bar(GuiGraphics g, int x, int y, int w, int h, float f, int color) {
        g.fill(x, y, x + w, y + h, 0xFF101313);
        if (f > 0) g.fill(x, y, x + Math.round(w * f), y + h, color);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && mx >= left + 8 && mx <= left + 110) {
            int index = ((int) my - top - 63) / 28;
            if (index >= 0 && index < 4) { active = index; return true; }
        }
        return super.mouseClicked(mx, my, button);
    }
}
