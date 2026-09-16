package com.november.mcphone.feature.caf.client;

import com.november.mcphone.MCphone;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/**
 * CAF 生存档案 —— 手机里那一格通往的地方。
 *
 * 我们只做一件事：替玩家执行一次 /caf
 *
 * 状态面板的数据只有服务端凑得齐（体力、水分、体温、职业、技能全在服务端），
 * 面板本身是 {@code StatusScreenPacket} 从服务端带到客户端的。所以这一格不能
 * 自己 new 界面，必须走原路：往服务端递一句「打开状态面板」，服务端用
 * {@code StatusScreenPacket.from(player)} 凑好数据发回来，它自己的包处理器
 * 负责把界面弹出来。与玩家在聊天栏敲 /caf 进的是同一个界面，功能一字不差。
 *
 * 为什么不直接敲聊天命令
 *
 * {@code LocalPlayer.command("/caf")} 也能走通，但那条路把一句包发成了一次
 * 聊天解析，还受聊天延迟与命令冷却的约束。直接照抄 {@code StatusCommand}
 * 里那一行（StaminaNetwork.sendStatus），比绕聊天栏干净。
 *
 * 反射，不加编译依赖
 *
 * 我们要的只是两个类型：{@code StatusScreenPacket}（它有个
 * {@code static from(ServerPlayer)} 工厂）和 {@code StaminaNetwork}
 * （{@code static sendStatus(ServerPlayer, StatusScreenPacket)}）。
 * caf_stamina_core 不在任何公共 maven 上，为两句 invoke 加编译依赖不值。
 * 断了的代价也可控：那一格点了没反应，日志里留一行说明是哪个方法没对上。
 *
 * 类型隔离照旧：本类的字段与方法签名里一个 chaosz 的类型都不出现。
 */
public final class CafStatusPanel {

    /**
     * 真实 modid 是 tarkov_stamina，不是 jar 文件名那个 caf_stamina_core——
     * 拿文件名去问 ModList 永远是"没装"，这一格就永远不出现。
     */
    public static final String MODID = "tarkov_stamina";

    private static final String PACKET = "com.chaosz.tarkovstamina.network.StatusScreenPacket";
    private static final String NETWORK = "com.chaosz.tarkovstamina.network.StaminaNetwork";

    private CafStatusPanel() {}

    private static boolean resolved;
    private static Method from;
    private static Method sendStatus;

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MODID);
    }

    /**
     * 打开状态面板。
     *
     * @param player 服务端侧的玩家。开不开得成由数据凑不凑得齐决定，不归这里管；
     *               返回 false 只有一个含义：方法签名没对上，模组换了版本。
     */
    public static boolean open(net.minecraft.server.level.ServerPlayer player) {
        if (!resolve()) return false;
        try {
            Object packet = from.invoke(null, player);
            sendStatus.invoke(null, player, packet);
            return true;
        } catch (Throwable t) {
            MCphone.LOGGER.error("[MCphone] 打开 CAF 生存档案失败，这一次没开成", t);
            return false;
        }
    }

    private static boolean resolve() {
        if (resolved) return from != null && sendStatus != null;
        resolved = true;
        try {
            Class<?> packet = Class.forName(PACKET);
            from = packet.getMethod("from", net.minecraft.server.level.ServerPlayer.class);
            sendStatus = Class.forName(NETWORK)
                    .getMethod("sendStatus", net.minecraft.server.level.ServerPlayer.class, packet);
            return true;
        } catch (Throwable t) {
            MCphone.LOGGER.warn("[MCphone] 没对上 caf_stamina_core 的状态面板入口（{}），"
                    + "「生存档案」那一格将点不开。这多半是它改了网络接口，这一层需要跟进", t.toString());
            from = null;
            sendStatus = null;
            return false;
        }
    }
}
