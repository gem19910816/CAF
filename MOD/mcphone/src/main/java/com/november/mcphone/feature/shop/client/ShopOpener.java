package com.november.mcphone.feature.shop.client;

import com.november.mcphone.MCphone;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/**
 * SDM 商店 —— 手机里那一格通往的地方。
 *
 * 我们只做一件事：替玩家执行一次 /sdmshop open_shop
 *
 * 商店界面是服务端触发的：/sdmshop open_shop 的字节码就是给目标玩家发一个
 * {@code SendOpenShopScreenS2C}，客户端收到后自己 openGui。所以这一格也走同一条
 * 路：客户端发个空包上来（OpenShopPacket），服务端照抄 openShop 里那两行——
 * new 一个 SendOpenShopScreenS2C 然后 sendTo(player)。商品、价格、库存、编辑模式
 * 全是 sdmshoprework 的，功能一字不差。
 *
 * 为什么不直接调客户端的 openGui
 *
 * 商店的数据（页签、条目）要服务端先同步过来，openGui 只是在有数据时把界面弹出来。
 * 走 S2C 包的话服务端会顺手把同步做全，与敲命令完全一致。
 *
 * 反射，不加编译依赖：断了的代价是那一格点了没反应并在日志留一行。
 * 类型隔离照旧：本类不出现任何 sixik 类型。
 *
 * 本类的 open() 在【服务端】线程上跑（从 NetworkHandler 的包处理里调）。
 */
public final class ShopOpener {

    public static final String MODID = "sdmshoprework";


    private ShopOpener() {}

    private static boolean resolved;
    private static Method sendTo;
    private static java.lang.reflect.Constructor<?> ctor;

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MODID);
    }

    /**
     * 给玩家打开商店，等价于 /sdmshop open_shop。在服务端线程上调。
     */
    public static boolean open(net.minecraft.server.level.ServerPlayer player) {
        if (!resolve()) return false;
        try {
            Object packet = ctor.newInstance();
            sendTo.invoke(packet, player);
            return true;
        } catch (Throwable t) {
            MCphone.LOGGER.error("[MCphone] 打开 SDM 商店失败，这一次没开成", t);
            return false;
        }
    }

    private static boolean resolve() {
        if (resolved) return sendTo != null;
        resolved = true;
        try {
            Class<?> pkt = Class.forName("net.sixik.sdmshoprework.network.server.misc.SendOpenShopScreenS2C");
            ctor = pkt.getConstructor();
            sendTo = pkt.getMethod("sendTo", net.minecraft.server.level.ServerPlayer.class);
            return true;
        } catch (Throwable t) {
            MCphone.LOGGER.warn("[MCphone] 没对上 SDM 商店的 SendOpenShopScreenS2C（{}），"
                    + "「商店」那一格将点不开。这多半是它改了网络接口，这一层需要跟进",
                    t.toString());
            sendTo = null;
            return false;
        }
    }
}
