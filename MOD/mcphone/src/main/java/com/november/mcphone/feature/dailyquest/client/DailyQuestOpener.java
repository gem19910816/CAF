package com.november.mcphone.feature.dailyquest.client;

import com.november.mcphone.MCphone;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/**
 * 日常任务 —— 手机里那一格通往的地方。
 *
 * 我们只做一件事：替玩家按一下打开任务面板的那个键
 *
 * 任务池、目标、奖励、追踪，全是 gearsandflesh-quests 的。我们不读任务档案、
 * 不碰任何进度，只是在玩家点手机图标时，做一次他按任务快捷键时也会做的调用：
 *
 *     com.gearsandflesh.quests.client.ClientQuestState.open(false);
 *
 * 传 false：与按键打开一致。功能一字未动。
 *
 * 反射与类型隔离的取舍同 {@code MarketOpener}。这是【客户端】类。
 */
public final class DailyQuestOpener {

    public static final String MODID = "gearsandflesh_quests";

    private static final String STATE = "com.gearsandflesh.quests.client.ClientQuestState";

    private DailyQuestOpener() {}

    private static boolean resolved;
    private static Method open;

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MODID);
    }

    public static boolean open() {
        if (!resolve()) return false;
        try {
            open.invoke(null, false);
            return true;
        } catch (Throwable t) {
            MCphone.LOGGER.error("[MCphone] 打开日常任务失败，这一次没开成", t);
            return false;
        }
    }

    private static boolean resolve() {
        if (resolved) return open != null;
        resolved = true;
        try {
            open = Class.forName(STATE).getMethod("open", boolean.class);
            return true;
        } catch (Throwable t) {
            MCphone.LOGGER.warn("[MCphone] 没对上日常任务的 {}.open(boolean)（{}），"
                    + "「日常任务」那一格将点不开。这多半是它改了客户端入口，这一层需要跟进",
                    STATE, t.toString());
            open = null;
            return false;
        }
    }
}
