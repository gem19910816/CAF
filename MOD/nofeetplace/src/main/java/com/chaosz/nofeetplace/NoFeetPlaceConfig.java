package com.chaosz.nofeetplace;

import net.minecraftforge.common.ForgeConfigSpec;

public class NoFeetPlaceConfig {

    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.BooleanValue SHOW_MESSAGE;
    private static final ForgeConfigSpec.IntValue MESSAGE_COOLDOWN;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("禁止脚下放方块 / No Feet Place —— 服务端规则配置").push("nofeetplace");

        SHOW_MESSAGE = b.comment("被拦截时在动作栏显示提示。")
                .define("show_message", true);

        MESSAGE_COOLDOWN = b.comment("提示消息的冷却（tick），防止长按右键刷屏。")
                .defineInRange("message_cooldown_ticks", 10, 0, 200);

        b.pop();
        SPEC = b.build();
    }

    private NoFeetPlaceConfig() {
    }

    /**
     * 配置在客户端刚进游戏时可能还没加载完成，直接 get() 会抛异常。
     * 判定每 tick 都可能被调用，这里统一退回默认值而不是让游戏崩掉。
     */
    private static <T> T safe(ForgeConfigSpec.ConfigValue<T> value, T fallback) {
        try {
            T loaded = value.get();
            return loaded == null ? fallback : loaded;
        } catch (Exception e) {
            return fallback;
        }
    }

    public static boolean showMessage() {
        return safe(SHOW_MESSAGE, true);
    }

    public static int messageCooldownTicks() {
        return safe(MESSAGE_COOLDOWN, 10);
    }
}
