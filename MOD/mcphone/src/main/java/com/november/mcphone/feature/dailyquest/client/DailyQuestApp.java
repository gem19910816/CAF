package com.november.mcphone.feature.dailyquest.client;

import com.november.mcphone.api.client.app.RequiredMod;
import com.november.mcphone.core.client.PhoneApp;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 「日常任务」App —— 主屏一格，点一下就是日常任务面板。
 *
 * 任务、目标、奖励、追踪都还是 gearsandflesh-quests 在管，我们只提供入口，
 * 与玩家按任务快捷键进的是同一个界面。功能一字未动。
 *
 * 联动而非前置、预装且免费：说法与「任务书」一致。
 *
 * 贴图: assets/mcphone/textures/app/daily_quests.png (20×20)
 */
public final class DailyQuestApp extends PhoneApp {

    public DailyQuestApp() {
        super("daily_quests");
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
        return List.of(new RequiredMod(DailyQuestOpener.MODID,
                Component.translatable("mcphone.compat.gearsandflesh_quests").getString()));
    }

    @Override
    public boolean isAvailable() {
        return DailyQuestOpener.isLoaded();
    }

    @Override
    public void onPress() {
        DailyQuestOpener.open();
    }
}
