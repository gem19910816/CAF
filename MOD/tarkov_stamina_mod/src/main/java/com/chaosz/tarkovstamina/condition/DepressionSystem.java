package com.chaosz.tarkovstamina.condition;

import com.chaosz.tarkovstamina.StaminaSystem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 抑郁系统
 * <p>
 * 熬夜计时 + 室内幽闭 → 抑郁等级 50/100
 * 重度抑郁 → 缓慢 I + 无法入睡
 * 恢复：洗澡（水中 + 营火 30 秒）或睡觉
 * </p>
 */
public final class DepressionSystem {
    private static final String K_TIME_SINCE_SLEEP = "timeSinceSleep";
    private static final String K_DEPRESSION_LEVEL = "depressionLevel";
    private static final String K_DEPRESSION_MSG = "depressionMsg";
    private static final String K_ENCLOSED_DEPRESSION = "enclosedDepression";
    private static final String K_BATHING_TIMER = "bathingTimer";
    private static final String K_WAS_SLEEPING = "wasSleeping";
    private static final String K_CHAT_COOLDOWN = "depressionChatCooldown";

    private DepressionSystem() {
    }

    @SubscribeEvent
    public static void onTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide()) {
            return;
        }
        if (event.player.tickCount % 5 != 0) return;

        ServerPlayer player = (ServerPlayer) event.player;
        Level level = player.level();
        CompoundTag state = StaminaSystem.state(player, true);

        // ── 睡觉时不计时，睡醒清除抑郁 ──
        boolean sleeping = player.isSleeping();
        if (!sleeping) {
            state.putLong(K_TIME_SINCE_SLEEP, state.getLong(K_TIME_SINCE_SLEEP) + 1);
        }

        if (state.getBoolean(K_WAS_SLEEPING) && !sleeping) {
            clearDepression(state);
            player.displayClientMessage(Component.literal("§a睡了一觉，精神焕发！"), false);
        }
        state.putBoolean(K_WAS_SLEEPING, sleeping);

        // ── 洗澡检测 ──
        boolean inWater = player.isInWaterOrRain() || player.isInWater();
        boolean hasFire = false;
        if (inWater) {
            var pos = player.blockPosition();
            for (int x = -5; x <= 5 && !hasFire; x++) {
                for (int y = -2; y <= 2 && !hasFire; y++) {
                    for (int z = -5; z <= 5 && !hasFire; z++) {
                        var block = level.getBlockState(pos.offset(x, y, z));
                        String id = block.getBlock().builtInRegistryHolder().key().location().toString();
                        if (id.contains("campfire") || id.contains("soul_campfire") || id.contains("fire")) {
                            hasFire = true;
                        }
                    }
                }
            }
        }

        if (inWater && hasFire) {
            int timer = state.getInt(K_BATHING_TIMER) + 1;
            state.putInt(K_BATHING_TIMER, timer);
            if (timer >= 150) {
                clearDepression(state);
                player.displayClientMessage(Component.literal("§a洗了个热水澡，心情好多了！"), false);
            }
        } else {
            state.putInt(K_BATHING_TIMER, 0);
        }

        // ── 抑郁程度计算 ──
        long timeSinceSleep = state.getLong(K_TIME_SINCE_SLEEP);
        boolean enclosed = !level.canSeeSky(player.blockPosition());
        int factors = 0;
        if (timeSinceSleep >= 36000) factors++;
        if (enclosed && timeSinceSleep >= 18000) {
            state.putBoolean(K_ENCLOSED_DEPRESSION, true);
        }
        if (state.getBoolean(K_ENCLOSED_DEPRESSION)) factors++;

        int cooldown = state.getInt(K_CHAT_COOLDOWN);
        if (cooldown > 0) state.putInt(K_CHAT_COOLDOWN, cooldown - 1);

        if (factors >= 2) {
            state.putInt(K_DEPRESSION_LEVEL, 100);
            state.putString(K_DEPRESSION_MSG, "悲伤淹没了你...洗个澡吧");
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 45, 0, false, false, false));
        } else if (factors >= 1) {
            state.putInt(K_DEPRESSION_LEVEL, 50);
            state.putString(K_DEPRESSION_MSG, "你感到有点低落...洗个澡或者睡一觉吧");
        } else {
            clearDepression(state);
        }

        // 镜像到顶层键（KJS 木工/石工读取兼容）
        player.getPersistentData().putInt("depressionLevel", state.getInt(K_DEPRESSION_LEVEL));

        // ── 聊天提示 ──
        if (state.getInt(K_DEPRESSION_LEVEL) > 0 && state.getInt(K_CHAT_COOLDOWN) <= 0) {
            player.displayClientMessage(
                    Component.literal("§7" + state.getString(K_DEPRESSION_MSG)), false);
            state.putInt(K_CHAT_COOLDOWN, 120);
        }
    }

    private static void clearDepression(CompoundTag state) {
        state.putLong(K_TIME_SINCE_SLEEP, 0);
        state.putInt(K_DEPRESSION_LEVEL, 0);
        state.putString(K_DEPRESSION_MSG, "");
        state.putBoolean(K_ENCLOSED_DEPRESSION, false);
        state.putInt(K_BATHING_TIMER, 0);
    }
}