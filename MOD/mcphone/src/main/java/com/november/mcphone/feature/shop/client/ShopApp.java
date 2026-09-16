package com.november.mcphone.feature.shop.client;

import com.november.mcphone.api.client.app.RequiredMod;
import com.november.mcphone.core.client.PhoneApp;
import com.november.mcphone.core.net.MCphoneNetwork;
import com.november.mcphone.feature.shop.net.OpenShopPacket;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 「商店」App —— 主屏一格，点一下就是 SDM 商店。
 *
 * 商品、购买、库存都还是 sdmshoprework 在画，我们只提供入口，
 * 与玩家按商店快捷键进的是同一个界面。功能一字未动。
 *
 * 联动而非前置、预装且免费：说法与「任务书」一致。
 *
 * 贴图: assets/mcphone/textures/app/shop.png (20×20)
 */
public final class ShopApp extends PhoneApp {

    public ShopApp() {
        super("shop");
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
        return List.of(new RequiredMod(ShopOpener.MODID,
                Component.translatable("mcphone.compat.sdmshoprework").getString()));
    }

    @Override
    public boolean isAvailable() {
        return ShopOpener.isLoaded();
    }

    @Override
    public void onPress() {
        // 只发包不自己开界面：商店要服务端先同步数据，界面由它自己的
        // SendOpenShopScreenS2C 带回来，与 /sdmshop open_shop 一模一样
        MCphoneNetwork.sendToServer(new OpenShopPacket());
    }
}
