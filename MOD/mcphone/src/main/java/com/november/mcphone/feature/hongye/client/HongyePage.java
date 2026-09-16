package com.november.mcphone.feature.hongye.client;

import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 「红夜」商城页 —— 列出我们自制的那几个 App，点了就装上主屏。
 *
 * 与系统应用商店的关系：那边是全量目录（含付费、官方），这边只卖自家货——
 * 生存档案、全球市场、日常任务、商店。免费，点「安装」即装；已装的显示「打开」。
 *
 * 安装是纯客户端动作（PhoneScreenRegistry.install），与系统商店同一条 API，
 * 状态按存档存，关掉再开还在。
 */
public final class HongyePage implements IPhonePage {

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    /** 红夜里卖的 App。与 HongyeAppSource.GOODS 是同一份——那边是商店货源，这边是自家门面 */
    private static final List<String> GOODS = HongyeAppSource.GOODS;

    private final List<Hit> hitBoxes = new ArrayList<>();
    private int scroll;

    @Override
    public void render(PhoneCanvas c) {
        hitBoxes.clear();
        var g = c.graphics();
        int x = c.x(), y = c.y(), w = c.width(), h = c.height();

        g.drawString(c.font(), "红夜", x + 4, y + 4, c.style().titleColor(), false);
        g.drawString(c.font(), "自制软件 · 免费", x + 4, y + 14, c.style().subtleColor(), false);

        List<IPhoneApp> apps = new ArrayList<>();
        for (String path : GOODS) {
            IPhoneApp app = PhoneScreenRegistry.getApp(
                    ResourceLocation.fromNamespaceAndPath("mcphone", path));
            if (app != null) apps.add(app);
        }

        int ry = y + 26 - scroll;
        int rowH = 26;
        for (IPhoneApp app : apps) {
            if (ry + rowH > y && ry < y + h - 14) renderRow(c, app, x, ry, w);
            ry += rowH + 2;
        }

        if (apps.isEmpty()) {
            g.drawString(c.font(), "货架是空的", x + 4, ry, c.style().subtleColor(), false);
        }
    }

    private void renderRow(PhoneCanvas c, IPhoneApp app, int x, int ry, int w) {
        var g = c.graphics();
        g.fill(x + 2, ry, x + w - 2, ry + 26, 0x22FFFFFF);

        // 图标：走 App 自己的 renderIcon（带抗锯齿，与主屏同一画法）
        app.renderIcon(g, x + 5, ry + 4, 16, c.partialTick());

        // 名字 + 一句简介
        g.drawString(c.font(), app.getDisplayName().getString(), x + 24, ry + 2,
                c.style().bodyColor(), false);
        String desc = app.getDescription();
        if (desc.length() > 22) desc = desc.substring(0, 22) + "…";
        g.drawString(c.font(), desc, x + 24, ry + 12, c.style().subtleColor(), false);

        // 右侧按钮：装了→打开，没装→安装
        boolean installed = PhoneScreenRegistry.getApps().contains(app);
        int bw = 30;
        String label = installed ? "打开" : "安装";
        int bx = x + w - bw - 5;
        int by = ry + 7;
        int bg = c.hovered(bx, by, bw, 12) ? c.style().buttonHoverColor() : c.style().buttonColor();
        g.fill(bx, by, bx + bw, by + 12, bg);
        g.drawString(c.font(), label, bx + (bw - c.font().width(label)) / 2, by + 2,
                c.style().bodyColor(), false);
        hitBoxes.add(new Hit(bx, by, bw, 12, () -> {
            if (installed) {
                // 打开 = 退回主屏直接启动它
                if (net.minecraft.client.Minecraft.getInstance().screen
                        instanceof com.november.mcphone.core.client.PhoneScreen ps) {
                    ps.launchApp(app);
                }
            } else {
                PhoneScreenRegistry.install(app.getId());
            }
        }));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (int i = hitBoxes.size() - 1; i >= 0; i--) {
            Hit hb = hitBoxes.get(i);
            if (mx >= hb.x && mx < hb.x + hb.w && my >= hb.y && my < hb.y + hb.h) {
                hb.action.run();
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        scroll = Math.max(0, scroll - (int) (amount * 10));
        return true;
    }
}
