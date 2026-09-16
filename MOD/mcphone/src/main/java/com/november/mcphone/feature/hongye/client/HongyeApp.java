package com.november.mcphone.feature.hongye.client;

import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.core.client.PhoneApp;

/**
 * 「红夜」商城 App —— 我们自己做的软件都在这儿装。
 *
 * 系统应用商店是全量目录，这一家只上架自制 App（生存档案、全球市场、日常任务、
 * 商店），免费，点「安装」就上主屏。它自己永远是预装的——装回其他 App 的入口
 * 不能被藏起来。
 *
 * 贴图: assets/mcphone/textures/app/hongye.png (20×20)
 */
public final class HongyeApp extends PhoneApp {

    public HongyeApp() {
        super("hongye");
    }

    @Override
    public IPhonePage openPage() {
        return new HongyePage();
    }

    @Override
    public void onPress() {
        // openPage 覆盖了，这里不会走到
    }
}
