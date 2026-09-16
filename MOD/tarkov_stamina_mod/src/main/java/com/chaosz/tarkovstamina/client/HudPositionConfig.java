package com.chaosz.tarkovstamina.client;

import com.chaosz.tarkovstamina.TarkovStamina;
import net.minecraft.client.Minecraft;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * HUD 体力条位置配置
 * <p>
 * 偏移量以屏幕宽高的比例存储，在不同 GUI 缩放下始终锚定在屏幕的同一相对位置。
 * 旧文件 (无 refW/refH) 自动按当前窗口缩放比例迁移。
 * 保存格式参考：{@code hudX = -297, hudY = 15, refW = 960, refH = 540}
 * </p>
 */
public final class HudPositionConfig {
    private static final String FILE_NAME = "tarkov_stamina_hud_pos.properties";

    // 偏移量 ÷ 保存时的参考缩放屏宽/高
    private static double fracX = 0.0;
    private static double fracY = 0.0;

    private HudPositionConfig() {
    }

    /** 当前缩放下的水平偏移（GUI 像素） */
    public static int offsetX(int screenWidth) {
        return (int) Math.round(fracX * screenWidth);
    }

    /** 当前缩放下的垂直偏移（GUI 像素） */
    public static int offsetY(int screenHeight) {
        return (int) Math.round(fracY * screenHeight);
    }

    /** 兼容旧 API：返回当前缩放下的偏移（需配合 StaminaHud 的 screenWidth / HudPositionScreen 的 this.width） */
    @Deprecated
    public static int getOffsetX() {
        return offsetX(scaledWidth());
    }

    /** 兼容旧 API */
    @Deprecated
    public static int getOffsetY() {
        return offsetY(scaledHeight());
    }

    /** 设置并保存偏移（在当前 GUI 缩放下） */
    public static void set(int x, int y) {
        double w = scaledWidth();
        double h = scaledHeight();
        fracX = w > 0 ? x / w : 0.0;
        fracY = h > 0 ? y / h : 0.0;
        save();
    }

    /** 从文件加载配置 */
    public static void load() {
        Path configDir = Minecraft.getInstance().gameDirectory.toPath().resolve("config");
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.exists(file)) return;
        var props = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
            int hudX = parseInt(props.getProperty("hudX", "0"));
            int hudY = parseInt(props.getProperty("hudY", "0"));
            // 读取参考缩放分辨率（旧文件没有 → 用当前窗口实时换算一次）
            int refW = parseInt(props.getProperty("refW", "0"));
            int refH = parseInt(props.getProperty("refH", "0"));
            if (refW > 0 && refH > 0) {
                fracX = (double) hudX / refW;
                fracY = (double) hudY / refH;
            } else {
                // 旧文件：用当前窗口的缩放尺寸作为参考，一次性迁移
                double w = scaledWidth();
                double h = scaledHeight();
                fracX = w > 0 ? hudX / w : 0.0;
                fracY = h > 0 ? hudY / h : 0.0;
                // 立即重写文件，带上 refW/refH，下次加载稳定
                save();
            }
        } catch (IOException e) {
            // ignore
        }
    }

    /** 重置到默认位置并保存 */
    public static void reset() {
        fracX = 0.0;
        fracY = 0.0;
        save();
    }

    /** 保存到文件 */
    private static void save() {
        Path configDir = Minecraft.getInstance().gameDirectory.toPath().resolve("config");
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            return;
        }
        Path file = configDir.resolve(FILE_NAME);
        double w = scaledWidth();
        double h = scaledHeight();
        int hudX = w > 0 ? (int) Math.round(fracX * w) : 0;
        int hudY = h > 0 ? (int) Math.round(fracY * h) : 0;
        var props = new Properties();
        props.setProperty("hudX", String.valueOf(hudX));
        props.setProperty("hudY", String.valueOf(hudY));
        props.setProperty("refW", String.valueOf((int) Math.round(w)));
        props.setProperty("refH", String.valueOf((int) Math.round(h)));
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            props.store(writer, "CAF Stamina HUD Position");
        } catch (IOException e) {
            // ignore
        }
    }

    /** 当前缩放后的 GUI 宽度 */
    private static int scaledWidth() {
        try {
            return Minecraft.getInstance().getWindow().getGuiScaledWidth();
        } catch (Exception e) {
            return 960;
        }
    }

    /** 当前缩放后的 GUI 高度 */
    private static int scaledHeight() {
        try {
            return Minecraft.getInstance().getWindow().getGuiScaledHeight();
        } catch (Exception e) {
            return 540;
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}