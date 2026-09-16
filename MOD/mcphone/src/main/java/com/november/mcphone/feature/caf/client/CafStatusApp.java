package com.november.mcphone.feature.caf.client;

import com.november.mcphone.api.client.app.RequiredMod;
import com.november.mcphone.core.client.PhoneApp;
import com.november.mcphone.core.net.MCphoneNetwork;
import com.november.mcphone.feature.caf.net.OpenCafStatusPacket;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 「生存档案」App —— 主屏一格，点一下就是 /caf 的综合状态面板。
 *
 * 体力、水分、体温、职业、技能那一屏，原来要敲 /caf 才看得见；现在开机点一下
 * 就是。数据与界面都还是 caf_stamina_core 自己的，我们只在手机与它之间递一句话
 * （一个空包 → 服务端照抄 {@code StatusCommand} 里那一行 → 它自己的
 * StatusScreenPacket 把界面带回来）。功能一字未动。
 *
 * caf_stamina_core 是【联动】，不是前置：没装它，少的是这一格，不是手机开不了机。
 * 所以声明 companionMods 并自己覆盖 isAvailable()，与「任务书」同一套说法。
 *
 * 预装且免费：这个面板本来就是开局白送的命令，卖它没有对应物。
 *
 * 贴图: assets/mcphone/textures/app/caf_status.png (20×20)
 */
public final class CafStatusApp extends PhoneApp {

    public CafStatusApp() {
        super("caf_status");
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
        return List.of(new RequiredMod(CafStatusPanel.MODID,
                Component.translatable("mcphone.compat.caf_stamina_core").getString()));
    }

    /** 没装 caf_stamina_core 就没有内容可给，这一格不该出现。理由同「任务书」 */
    @Override
    public boolean isAvailable() {
        return CafStatusPanel.isLoaded();
    }

    @Override
    public void onPress() {
        // 只发包不自己开界面：面板数据只有服务端凑得齐，界面由它自己的
        // StatusScreenPacket 带回来，与 /caf 一模一样
        MCphoneNetwork.sendToServer(new OpenCafStatusPacket());
    }
}
