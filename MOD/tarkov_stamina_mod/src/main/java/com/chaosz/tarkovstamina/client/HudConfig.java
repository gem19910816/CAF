package com.chaosz.tarkovstamina.client;

import net.minecraftforge.common.ForgeConfigSpec;

/** Client-only stamina HUD settings. Values use a 960x540 GUI reference size. */
public final class HudConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    static {
        BUILDER.push("hud");
    }

    public static final ForgeConfigSpec.IntValue LEFT = BUILDER
            .comment("Distance from the left edge of the screen, in GUI pixels.")
            .defineInRange("left", 8, -4000, 4000);
    public static final ForgeConfigSpec.IntValue BOTTOM = BUILDER
            .comment("Distance from the bottom edge of the screen, in GUI pixels.")
            .defineInRange("bottom", 84, -4000, 4000);
    public static final ForgeConfigSpec.IntValue WIDTH = BUILDER
            .comment("Width of the stamina line before scale is applied.")
            .defineInRange("width", 72, 20, 1000);
    public static final ForgeConfigSpec.IntValue HEIGHT = BUILDER
            .comment("Height of the stamina line before scale is applied.")
            .defineInRange("height", 2, 1, 20);
    public static final ForgeConfigSpec.DoubleValue SCALE = BUILDER
            .comment("HUD scale multiplier.")
            .defineInRange("scale", 1.0, 0.25, 4.0);
    public static final ForgeConfigSpec.BooleanValue SHOW_ONLY_WHEN_CHANGED = BUILDER
            .comment("Hide the HUD when stamina is full and the recovery delay is over.")
            .define("showOnlyWhenChanged", false);
    private static final boolean HUD_GROUP_CLOSED = closeHudGroup();

    private static boolean closeHudGroup() {
        BUILDER.pop();
        BUILDER.push("colors");
        return true;
    }

    public static final ForgeConfigSpec.ConfigValue<String> BAR_COLOR = BUILDER
            .comment("Stamina line color, hex RRGGBB.")
            .define("barColor", "9DFF00");

    static {
        BUILDER.pop();
    }

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private HudConfig() {
    }

    public static int width(int screenWidth) {
        return scaled(HUD_WIDTH(), screenWidth, 960);
    }

    public static int height(int screenHeight) {
        return Math.max(1, scaled(HudHeight(), screenHeight, 540));
    }

    public static int anchorX(int screenWidth) {
        return scaled(LEFT.get(), screenWidth, 960);
    }

    public static int anchorY(int screenHeight) {
        return screenHeight - scaled(BOTTOM.get(), screenHeight, 540) - height(screenHeight);
    }

    public static int barColor() {
        String value = BAR_COLOR.get();
        if (value == null) return 0xD7DEDC;
        String hex = value.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        try {
            if (hex.length() != 6) return 0xD7DEDC;
            return Integer.parseInt(hex, 16) & 0xFFFFFF;
        } catch (NumberFormatException ignored) {
            return 0xD7DEDC;
        }
    }

    public static boolean showOnlyWhenChanged() {
        return SHOW_ONLY_WHEN_CHANGED.get();
    }

    private static int HUD_WIDTH() {
        return Math.max(20, (int) Math.round(WIDTH.get() * SCALE.get()));
    }

    private static int HudHeight() {
        return Math.max(1, (int) Math.round(HEIGHT.get() * SCALE.get()));
    }

    private static int scaled(int value, int actual, int reference) {
        return (int) Math.round(value * (actual / (double) reference));
    }
}
