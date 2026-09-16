package com.november.mcphone.feature.market.client;

import com.november.mcphone.api.client.app.RequiredMod;
import com.november.mcphone.core.client.PhoneApp;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 「全球市场」App —— 主屏一格，点一下就是全球市场。
 *
 * 买卖挂单、成交、管理页都还是 gearsandflesh-market 在画，我们只提供入口，
 * 与玩家按市场快捷键进的是同一个界面。功能一字未动。
 *
 * 联动而非前置、预装且免费：说法与「任务书」一致。
 *
 * 贴图: assets/mcphone/textures/app/global_market.png (20×20)
 */
public final class MarketApp extends PhoneApp {

    public MarketApp() {
        super("global_market");
    }

    /**
     * 不预装：自制 App 统一进应用商店的「联动App」页，玩家在商店里点安装才上主屏。
     * 商店页对联动 App 的列出不看可用性（装了前置就显示），所以没装前置也不会丢。
     */
    @Override
    public boolean isPreinstalled() {
        return false;
    }

    @Override
    public List<RequiredMod> companionMods() {
        return List.of(new RequiredMod(MarketOpener.MODID,
                Component.translatable("mcphone.compat.gearsandflesh_market").getString()));
    }

    @Override
    public boolean isAvailable() {
        return MarketOpener.isLoaded();
    }

    @Override
    public void onPress() {
        MarketOpener.open();
    }
}
