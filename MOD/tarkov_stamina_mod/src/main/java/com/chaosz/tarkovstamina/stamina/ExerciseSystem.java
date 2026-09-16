package com.chaosz.tarkovstamina.stamina;

import com.chaosz.tarkovstamina.StaminaConfig;
import com.chaosz.tarkovstamina.StaminaSystem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 锻炼系统
 * <p>
 * 疾跑时积累锻炼进度，达到 100 就升级，每级 +1% 体力上限。
 * 挖掘系统已移除（石工职业已覆盖挖掘速度加成）。
 * </p>
 */
public final class ExerciseSystem {
    private static final String K_EXERCISE_PROGRESS = "exerciseProgress";
    private static final String K_EXERCISE_LEVEL = "exerciseLevel";

    private ExerciseSystem() {
    }

    /**
     * 锻炼：疾跑且还有体力时累积进度
     */
    @SubscribeEvent
    public static void onTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide()) {
            return;
        }
        ServerPlayer player = (ServerPlayer) event.player;
        CompoundTag state = StaminaSystem.state(player, true);

        float stamina = state.getFloat("stamina");
        if (player.isSprinting() && stamina > 0) {
            double progress = state.getDouble(K_EXERCISE_PROGRESS);
            progress += StaminaConfig.EXERCISE_PROGRESS_PER_TICK.get();
            int level = state.getInt(K_EXERCISE_LEVEL);
            int maxLevel = StaminaConfig.MAX_EXERCISE_LEVEL.get();

            if (progress >= 100.0 && level < maxLevel) {
                progress -= 100.0;
                level++;
                state.putInt(K_EXERCISE_LEVEL, level);
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "§a✓ 锻炼效果提升！ §f当前锻炼等级: " + level + "/" + maxLevel
                                        + " | 体力上限提升: +" + level + "%"),
                        false);
                player.playSound(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 2.0F, 1.0F);
            }
            state.putDouble(K_EXERCISE_PROGRESS, Math.min(progress, 100.0));
        }
    }
}