package com.chaosz.tarkovstamina.condition;

import com.chaosz.tarkovstamina.StaminaConfig;
import com.chaosz.tarkovstamina.StaminaSystem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 疾病系统（感冒 + 雨伞）
 * <p>
 * 淋雨 + 无伞 + 室外 → 累计淋雨时间，达到阈值后按概率感冒。
 * 感冒状态下：体力恢复减半，室内静养/火源加速康复，睡觉康复。
 * 物品：caf:umbrella（防雨）、caf:ganmaoyao（感冒药，由KJS处理或后续版本）
 * </p>
 */
public final class DiseaseSystem {
    private static final String K_IS_SICK = "isSick";
    private static final String K_RAIN_TICKS = "rainTicks";
    private static final String K_RECOVERY_TICKS = "recoveryTicks";
    private static final String K_NOTIFIED_FIRE = "hasNotifiedFire";

    private static final ResourceLocation UMBRELLA_ID = ResourceLocation.tryBuild("caf", "umbrella");

    private DiseaseSystem() {
    }

    @SubscribeEvent
    public static void onTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide()) {
            return;
        }
        if (event.player.tickCount % 20 != 0) return;

        ServerPlayer player = (ServerPlayer) event.player;
        Level level = player.level();
        CompoundTag state = StaminaSystem.state(player, true);

        if (!state.contains(K_IS_SICK)) state.putBoolean(K_IS_SICK, false);
        if (!state.contains(K_RAIN_TICKS)) state.putInt(K_RAIN_TICKS, 0);
        if (!state.contains(K_RECOVERY_TICKS)) state.putInt(K_RECOVERY_TICKS, 0);

        // ── 淋雨判定 ──
        boolean holdingUmbrella = isUmbrella(player.getMainHandItem())
                || isUmbrella(player.getOffhandItem());
        boolean exposed = level.isRaining() && level.canSeeSky(player.blockPosition())
                && !holdingUmbrella;

        if (exposed) {
            state.putInt(K_RAIN_TICKS, state.getInt(K_RAIN_TICKS) + 20);
            if (state.getInt(K_RAIN_TICKS) >= StaminaConfig.COLD_RAIN_TICKS.get()
                    && !state.getBoolean(K_IS_SICK)) {
                if (level.random.nextDouble() < StaminaConfig.COLD_CHANCE.get()) {
                    state.putBoolean(K_IS_SICK, true);
                    player.displayClientMessage(Component.literal("§c你觉得有点冷... 似乎感冒了。"), false);
                }
                state.putInt(K_RAIN_TICKS, 0);
            }
        } else if (state.getInt(K_RAIN_TICKS) > 0) {
            state.putInt(K_RAIN_TICKS, state.getInt(K_RAIN_TICKS) - 20);
        }

        // ── 患病后逻辑 ──
        if (state.getBoolean(K_IS_SICK)) {
            // 睡觉康复
            if (player.isSleeping() && player.getSleepTimer() >= 99) {
                cure(state);
                player.displayClientMessage(Component.literal("§a睡了一觉，感觉身体好多了。"), false);
                return;
            }

            // 附近火源
            boolean hasFire = hasFireNearby(level, player);
            if (hasFire && !state.getBoolean(K_NOTIFIED_FIRE)) {
                state.putBoolean(K_NOTIFIED_FIRE, true);
                player.displayClientMessage(Component.literal("§a靠近火源让你感到温暖，康复速度加快了。"), false);
            } else if (!hasFire && state.getBoolean(K_NOTIFIED_FIRE)) {
                state.putBoolean(K_NOTIFIED_FIRE, false);
            }

            // 室内静养
            if (!level.canSeeSky(player.blockPosition())) {
                int amount = hasFire ? 30 : 20;
                state.putInt(K_RECOVERY_TICKS, state.getInt(K_RECOVERY_TICKS) + amount);
                int threshold = hasFire ? 24000 : 36000;
                if (state.getInt(K_RECOVERY_TICKS) >= threshold) {
                    cure(state);
                    player.displayClientMessage(Component.literal("§a经过静养，你的感冒痊愈了。"), false);
                }
            } else if (state.getInt(K_RECOVERY_TICKS) > 0) {
                state.putInt(K_RECOVERY_TICKS, state.getInt(K_RECOVERY_TICKS) - 10);
            }
        }
    }

    private static boolean isUmbrella(net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation key = stack.getItem().builtInRegistryHolder().key().location();
        return key.equals(UMBRELLA_ID);
    }

    private static boolean hasFireNearby(Level level, ServerPlayer player) {
        var pos = player.blockPosition();
        for (int x = -5; x <= 5; x++) {
            for (int y = -5; y <= 5; y++) {
                for (int z = -5; z <= 5; z++) {
                    String id = level.getBlockState(pos.offset(x, y, z)).getBlock()
                            .builtInRegistryHolder().key().location().toString();
                    if (id.equals("minecraft:campfire") || id.equals("minecraft:soul_campfire")
                            || id.equals("minecraft:fire") || id.equals("minecraft:soul_fire")
                            || id.equals("minecraft:lava") || id.equals("minecraft:magma_block")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void cure(CompoundTag state) {
        state.putBoolean(K_IS_SICK, false);
        state.putInt(K_RECOVERY_TICKS, 0);
        state.putInt(K_RAIN_TICKS, 0);
    }
}