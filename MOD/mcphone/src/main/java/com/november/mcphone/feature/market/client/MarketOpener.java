package com.november.mcphone.feature.market.client;

import com.november.mcphone.MCphone;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/**
 * 全球市场 —— 手机里那一格通往的地方。
 *
 * 我们只做一件事：替玩家按一下打开市场的那个键
 *
 * 挂单、成交、调价、管理页，全是 gearsandflesh-market 的。我们不读行情、不碰
 * 数据，只是在玩家点手机图标时，做一次他按市场快捷键时也会做的调用：
 *
 *     com.gearsandflesh.market.client.ClientMarketState.open(false);
 *
 * 传 false：与按键打开一致，不开管理页。进了界面之后，有权限的人照样能从界面里
 * 自己切进管理页——与原来一模一样，功能一字未动。
 *
 * 反射，不加编译依赖：这一层断了的代价是那一格点了没反应并在日志留一行，
 * 与「任务书」同一个取舍。类型隔离照旧：本类不出现任何 gearsandflesh 类型。
 *
 * 这是【客户端】类。
 */
public final class MarketOpener {

    public static final String MODID = "gearsandflesh_market";

    private static final String STATE = "com.gearsandflesh.market.client.ClientMarketState";

    private MarketOpener() {}

    private static boolean resolved;
    private static Method open;

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MODID);
    }

    /**
     * 打开市场。返回 false 只有一个含义：方法没对上，模组换了版本。开不开得成
     * （比如在主菜单里点）由它自己的界面逻辑兜着，不归这里管。
     */
    public static boolean open() {
        if (!resolve()) return false;
        try {
            open.invoke(null, false);
            return true;
        } catch (Throwable t) {
            MCphone.LOGGER.error("[MCphone] 打开全球市场失败，这一次没开成", t);
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
            MCphone.LOGGER.warn("[MCphone] 没对上全球市场的 {}.open(boolean)（{}），"
                    + "「全球市场」那一格将点不开。这多半是它改了客户端入口，这一层需要跟进",
                    STATE, t.toString());
            open = null;
            return false;
        }
    }
}
