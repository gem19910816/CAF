package com.november.mcphone.feature.hongye.client;

import com.november.mcphone.api.client.store.AppInfo;
import com.november.mcphone.api.client.store.IAppSource;
import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 「红夜」货源：把系统应用商店里那四个自制 App 截到红夜来卖。
 *
 * 与 LocalAppSource（本机）的区别：本机列出"已发现但未安装"的全部 App，
 * 红夜只列自制的四个。四个都在红夜上架之后，本机那边自然一个也不剩——
 * 玩家不会在两个商店里看到同一批东西。
 *
 * 安装走的还是 {@link PhoneScreenRegistry#install}，与系统商店同一条 API，
 * 状态按存档存，价格没有（免费）。
 */
public final class HongyeAppSource implements IAppSource {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath("mcphone", "hongye");

    /** 红夜里卖的 App（mcphone 命名空间下的短名）。与 HongyePage 里那份保持一致 */
    public static final List<String> GOODS = List.of(
            "caf_status", "global_market", "daily_quests", "shop");

    @Override
    public ResourceLocation getId() { return ID; }

    @Override
    public Component getDisplayName() {
        return Component.translatable("mcphone.store.source.hongye");
    }

    @Override
    public void listAvailable(Consumer<List<AppInfo>> callback) {
        List<AppInfo> out = new ArrayList<>();
        for (String path : GOODS) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("mcphone", path);
            IPhoneApp app = PhoneScreenRegistry.getApp(id);
            // 已装的不上架（商店卖的是"还没装"的）
            if (app == null || PhoneScreenRegistry.getApps().contains(app)) continue;
            out.add(AppInfo.of(app, ID));
        }
        callback.accept(out);
    }

    @Override
    public void install(AppInfo info, Consumer<IPhoneApp> onSuccess, Consumer<Component> onError) {
        IPhoneApp app = PhoneScreenRegistry.getApp(info.id());
        if (app == null) {
            onError.accept(Component.translatable("mcphone.store.error.not_found", info.id().toString()));
            return;
        }
        if (!PhoneScreenRegistry.install(info.id())) {
            onError.accept(Component.translatable("mcphone.store.error.install_failed", info.id().toString()));
            return;
        }
        onSuccess.accept(app);
    }
}
