package com.chaosz.tarkovstamina;

import com.chaosz.tarkovstamina.network.StaminaNetwork;
import com.chaosz.tarkovstamina.network.StaminaSyncPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 核心体力系统
 * <p>
 * 精确复刻 KJS 原版行为：
 * - 疾跑消耗 stamina--/tick，regenDelay=60
 * - 体力归零时立即取消疾跑，并在耗尽期间持续禁止疾跑
 * - 恢复：regenTimer 倒计时 + baseRegen×倍率 + 注射加成
 * - 睡眠直接回满
 * - 上限 = 基础 × (1 + 注射% + 锻炼%)
 * </p>
 */
public final class StaminaSystem {
    private static final String DATA_KEY = TarkovStamina.MOD_ID;

    // 持久化键名
    private static final String K_STAMINA = "stamina";
    private static final String K_COOLDOWN = "cooldown";     // 对应 KJS staminaRegenTimer
    private static final String K_INJECTION_COUNT = "injectionCount";
    private static final String K_EXERCISE_PROGRESS = "exerciseProgress";
    private static final String K_EXERCISE_LEVEL = "exerciseLevel";
    private static final String K_MINING_PROGRESS = "miningProgress";
    private static final String K_MINING_LEVEL = "miningLevel";
    private static final String K_IS_OVERWEIGHT = "isOverweight";

    private StaminaSystem() {
    }

    // ═══════════════════════════════════════════════════════════════
    //  核心 Tick — 精确复刻 KJS 体力度数和恢复
    // ═══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.player.level().isClientSide()) return;

        ServerPlayer player = (ServerPlayer) event.player;

        // Phase.START: 体力为0时强制锁死疾跑
        if (event.phase == TickEvent.Phase.START) {
            CompoundTag s = state(player, false);
            if (!player.isCreative() && !player.isSpectator()
                    && !s.isEmpty() && s.getFloat(K_STAMINA) <= 0.0F) {
                player.setSprinting(false);
            }
            return;
        }

        // Phase.END: 体力消耗与恢复逻辑（双层保险：再拦截一次疾跑包）
        CompoundTag state = state(player, true);
        float maximum = maximumFor(player, state);
        float stamina = Mth.clamp(state.getFloat(K_STAMINA), 0.0F, maximum);
        int cooldown = Math.max(0, state.getInt(K_COOLDOWN));
        boolean unrestricted = player.isCreative() || player.isSpectator();

        // 体力耗尽时强制锁死疾跑
        if (!unrestricted && stamina <= 0.0F) {
            player.setSprinting(false);
        }

        if (!unrestricted && player.isSprinting() && stamina > 0.0F) {
            stamina = Math.max(0.0F, stamina - StaminaConfig.SPRINT_DRAIN.get().floatValue());
            cooldown = StaminaConfig.REGEN_DELAY_TICKS.get();
            // 本 tick 消耗后归零时立即取消疾跑，不等下一 tick 或松开按键。
            if (stamina <= 0.0F) {
                player.setSprinting(false);
            }
        } else {
            if (stamina <= 0.0F) stamina = 0.0F;
            // 恢复
            if (!player.isSleeping()) {
                if (cooldown > 0) {
                    cooldown--;
                } else if (stamina < maximum) {
                    // KJS 恢复公式:
                    //   baseRegen = isSitting ? 3.0 : 1.5
                    //   injectionBonus = isSitting ? (injectionCount * 0.5) : (injectionCount * 0.25)
                    //   maxStaminaPercent = (100 + totalBonus) / 100
                    //   regen = (baseRegen + injectionBonus) * maxStaminaPercent
                    boolean resting = player.isCrouching() || player.isPassenger();
                    int injections = state.getInt(K_INJECTION_COUNT);
                    double baseRegen = resting
                            ? StaminaConfig.REGEN_BASE_SITTING.get()
                            : StaminaConfig.REGEN_BASE_STANDING.get();
                    double injectionBonus = resting
                            ? (injections * 0.5)
                            : (injections * 0.25);
                    int totalBonus = totalBonusPercent(state);
                    double maxStaminaPercent = (100.0 + totalBonus) / 100.0;
                    double regen = (baseRegen + injectionBonus) * maxStaminaPercent;

                    // 疾病拖慢恢复
                    if (state.getBoolean("isSick")) {
                        regen *= 0.5;
                    }
                    stamina = Math.min(maximum, stamina + (float) regen);
                }
            } else {
                // 睡眠直接回满
                stamina = maximum;
                cooldown = 0;
            }
        }

        state.putFloat(K_STAMINA, stamina);
        state.putInt(K_COOLDOWN, cooldown);

        // 同步客户端：状态标记必须反映本 tick 的真实结果，HUD 才能正确显示耗尽警报。
        sync(player, stamina, maximum, cooldown,
                !unrestricted && player.isSprinting(),
                !unrestricted && stamina <= 0.0F,
                unrestricted);
    }

    // ═══════════════════════════════════════════════════════════════
    //  跳跃消耗（KJS 无跳跃消耗，默认关闭，可通过 config 开启）
    // ═══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onJump(LivingEvent.LivingJumpEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || player.isCreative() || player.isSpectator()) {
            return;
        }
        float cost = StaminaConfig.JUMP_COST.get().floatValue();
        if (cost <= 0.0F) return;

        CompoundTag state = state(player, true);
        float stamina = Math.max(0.0F, state.getFloat(K_STAMINA) - cost);
        state.putFloat(K_STAMINA, stamina);
        state.putInt(K_COOLDOWN, StaminaConfig.REGEN_DELAY_TICKS.get());
        sync(player, stamina, maximumFor(player, state),
                StaminaConfig.REGEN_DELAY_TICKS.get(),
                player.isSprinting(), stamina <= 0.0F, false);
    }

    // ═══════════════════════════════════════════════════════════════
    //  登录 / 维度切换 / 重生克隆
    // ═══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CompoundTag state = state(player, false);
            if (state.isEmpty() && StaminaConfig.USE_LEGACY_BONUSES.get()) {
                migrateLegacyData(player);
            }
            syncCurrent(player);
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncCurrent(player);
        }
    }

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        CompoundTag oldData = event.getOriginal().getPersistentData();
        if (oldData.contains(DATA_KEY, Tag.TAG_COMPOUND)) {
            event.getEntity().getPersistentData()
                    .put(DATA_KEY, oldData.getCompound(DATA_KEY).copy());
        }
        // 重生后体力给满（仅死亡重生，不含维度切换）
        if (event.isWasDeath() && event.getEntity() instanceof ServerPlayer player) {
            CompoundTag state = state(player, true);
            float max = maximumFor(player, state);
            state.putFloat(K_STAMINA, max);
            state.putInt(K_COOLDOWN, 0);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  数据访问
    // ═══════════════════════════════════════════════════════════════

    public static CompoundTag state(Player player, boolean createIfAbsent) {
        CompoundTag persistent = player.getPersistentData();
        if (persistent.contains(DATA_KEY, Tag.TAG_COMPOUND)) {
            return persistent.getCompound(DATA_KEY);
        }
        if (!createIfAbsent) return new CompoundTag();

        CompoundTag s = new CompoundTag();
        s.putFloat(K_STAMINA, StaminaConfig.BASE_MAX.get().floatValue());
        s.putInt(K_COOLDOWN, 0);
        s.putInt(K_INJECTION_COUNT, 0);
        s.putDouble(K_EXERCISE_PROGRESS, 0.0);
        s.putInt(K_EXERCISE_LEVEL, 0);
        s.putDouble(K_MINING_PROGRESS, 0.0);
        s.putInt(K_MINING_LEVEL, 0);
        s.putBoolean(K_IS_OVERWEIGHT, false);
        s.putLong("timeSinceSleep", 0);
        s.putInt("depressionLevel", 0);
        s.putString("depressionMsg", "");
        s.putBoolean("isSick", false);
        s.putInt("rainTicks", 0);
        s.putInt("recoveryTicks", 0);
        s.putInt("monsterKills", 0);
        s.putLong("calmUntil", 0);
        s.putBoolean("isSmokeAddicted", false);
        s.putBoolean("isAlcoholAddicted", false);
        s.putLong("timeSinceLastPoop", 0);
        persistent.put(DATA_KEY, s);
        return s;
    }

    /** 计算最大体力值 = 基础 × (1 + 注射% + 锻炼%) */
    public static float maximumFor(Player player, CompoundTag state) {
        float base = StaminaConfig.BASE_MAX.get().floatValue();
        int totalBonus = totalBonusPercent(state);
        return (float) (base * (1.0 + totalBonus / 100.0));
    }

    /** 总加成百分比 = 注射% + 锻炼% */
    private static int totalBonusPercent(CompoundTag state) {
        int injections = Mth.clamp(state.getInt(K_INJECTION_COUNT), 0, 100);
        int exercise = Mth.clamp(state.getInt(K_EXERCISE_LEVEL), 0, 1000);
        double injectionPct = injections * StaminaConfig.INJECTION_BONUS_PERCENT.get();
        injectionPct = Mth.clamp(injectionPct, 0.0, 100.0);
        double exercisePct = Math.min(exercise, StaminaConfig.MAX_EXERCISE_LEVEL.get());
        return (int) Math.round(injectionPct + exercisePct);
    }

    public static float staminaFraction(Player player) {
        CompoundTag state = state(player, false);
        if (state.isEmpty()) return 1.0F;
        float max = maximumFor(player, state);
        if (max <= 0) return 0;
        return Mth.clamp(state.getFloat(K_STAMINA) / max, 0.0F, 1.0F);
    }

    // ═══════════════════════════════════════════════════════════════
    //  同步
    // ═══════════════════════════════════════════════════════════════

    private static void syncCurrent(ServerPlayer player) {
        CompoundTag state = state(player, true);
        float stamina = state.getFloat(K_STAMINA);
        boolean unrestricted = player.isCreative() || player.isSpectator();
        sync(player, state.getFloat(K_STAMINA), maximumFor(player, state),
                state.getInt(K_COOLDOWN),
                !unrestricted && player.isSprinting(),
                !unrestricted && stamina <= 0.0F,
                unrestricted);
    }

    private static void sync(ServerPlayer player, float stamina, float maximum,
                             int cooldown, boolean sprinting, boolean exhausted, boolean hidden) {
        StaminaNetwork.send(player, new StaminaSyncPacket(
                stamina, maximum, cooldown,
                StaminaConfig.REGEN_DELAY_TICKS.get(),
                sprinting, exhausted, hidden
        ));
    }

    // ═══════════════════════════════════════════════════════════════
    //  旧数据迁移（从 KJS 的顶层 persistentData 键读取）
    // ═══════════════════════════════════════════════════════════════

    private static void migrateLegacyData(ServerPlayer player) {
        CompoundTag persistent = player.getPersistentData();
        CompoundTag state = state(player, true);

        String[] legacyInts = {"injectionCount", "exerciseLevel", "miningLevel",
                "depressionLevel", "monsterKills"};
        for (String key : legacyInts) {
            if (persistent.contains(key, Tag.TAG_ANY_NUMERIC))
                state.putInt(key, persistent.getInt(key));
        }
        String[] legacyDoubles = {"exerciseProgress", "miningProgress"};
        for (String key : legacyDoubles) {
            if (persistent.contains(key, Tag.TAG_ANY_NUMERIC))
                state.putDouble(key, persistent.getDouble(key));
        }
        if (persistent.contains("isSick", Tag.TAG_ANY_NUMERIC))
            state.putBoolean("isSick", persistent.getBoolean("isSick"));
        if (persistent.contains("calm_until", Tag.TAG_ANY_NUMERIC))
            state.putLong("calmUntil", persistent.getLong("calm_until"));
        if (persistent.contains("is_smoke_addicted", Tag.TAG_ANY_NUMERIC))
            state.putBoolean("isSmokeAddicted", persistent.getBoolean("is_smoke_addicted"));
        if (persistent.contains("is_alcohol_addicted", Tag.TAG_ANY_NUMERIC))
            state.putBoolean("isAlcoholAddicted", persistent.getBoolean("is_alcohol_addicted"));
        if (persistent.contains("timeSinceLastPoop", Tag.TAG_ANY_NUMERIC))
            state.putLong("timeSinceLastPoop", persistent.getLong("timeSinceLastPoop"));
    }

    // ═══════════════════════════════════════════════════════════════
    //  公开工具方法（供其他系统读取）
    // ═══════════════════════════════════════════════════════════════

    public static int getInjectionCount(Player player) {
        return state(player, false).getInt(K_INJECTION_COUNT);
    }

    public static int getExerciseLevel(Player player) {
        return state(player, false).getInt(K_EXERCISE_LEVEL);
    }

    public static int getMiningLevel(Player player) {
        return state(player, false).getInt(K_MINING_LEVEL);
    }

    public static boolean isOverweight(Player player) {
        return state(player, false).getBoolean(K_IS_OVERWEIGHT);
    }
}
