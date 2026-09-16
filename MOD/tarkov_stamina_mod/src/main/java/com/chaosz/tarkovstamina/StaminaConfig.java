package com.chaosz.tarkovstamina;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 所有参数集中配置，默认值精确复刻 KJS 原版行为。
 * 可随时通过配置文件调整。
 */
public final class StaminaConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // ────────────────────────── 体力基础 ──────────────────────────
    public static final ForgeConfigSpec.DoubleValue BASE_MAX = BUILDER
            .comment("Base stamina points. (KJS default: 800)")
            .defineInRange("baseMax", 800.0, 10.0, 10000.0);

    public static final ForgeConfigSpec.DoubleValue SPRINT_DRAIN = BUILDER
            .comment("Stamina drained per tick while sprinting. (KJS: 1)")
            .defineInRange("sprintDrainPerTick", 1.0, 0.0, 1000.0);

    public static final ForgeConfigSpec.DoubleValue JUMP_COST = BUILDER
            .comment("Stamina cost of a single jump. (Original: 4.0)")
            .defineInRange("jumpCost", 4.0, 0.0, 10000.0);

    // KJS 恢复公式: baseRegen * maxStaminaPercent + injectionBonus
    // baseRegen = 1.5(站立) / 3.0(坐下)
    // injectionBonus = 0.25*注射次数(站立) / 0.5*注射次数(坐下)
    // maxStaminaPercent = (100 + totalBonus) / 100
    public static final ForgeConfigSpec.DoubleValue REGEN_BASE_STANDING = BUILDER
            .comment("Base regen per tick while standing. (KJS: 1.5)")
            .defineInRange("regenBaseStanding", 1.5, 0.0, 1000.0);

    public static final ForgeConfigSpec.DoubleValue REGEN_BASE_SITTING = BUILDER
            .comment("Base regen per tick while crouching/riding. (KJS: 3.0)")
            .defineInRange("regenBaseSitting", 3.0, 0.0, 1000.0);

    public static final ForgeConfigSpec.IntValue REGEN_DELAY_TICKS = BUILDER
            .comment("Ticks before stamina starts regenerating after exertion. (KJS: 60)")
            .defineInRange("regenDelayTicks", 60, 0, 1200);

    // ────────────────────────── 基因强化 ──────────────────────────
    public static final ForgeConfigSpec.IntValue MAX_INJECTIONS = BUILDER
            .comment("Maximum number of gene enhancement injections. (KJS: 3)")
            .defineInRange("maxInjections", 3, 0, 10);

    public static final ForgeConfigSpec.DoubleValue INJECTION_BONUS_PERCENT = BUILDER
            .comment("Stamina cap increase per injection (percentage points). (KJS: 10)")
            .defineInRange("injectionBonusPercent", 10.0, 0.0, 100.0);

    // ────────────────────────── 锻炼系统 ──────────────────────────
    public static final ForgeConfigSpec.IntValue MAX_EXERCISE_LEVEL = BUILDER
            .comment("Max exercise level. Each level grants +1% stamina cap. (KJS: 70)")
            .defineInRange("maxExerciseLevel", 70, 0, 200);

    public static final ForgeConfigSpec.DoubleValue EXERCISE_PROGRESS_PER_TICK = BUILDER
            .comment("Exercise progress per sprinting tick (100 = one level). (KJS: 0.0333)")
            .defineInRange("exerciseProgressPerTick", 0.03333, 0.0, 10.0);

    // ────────────────────────── 挖掘系统 ──────────────────────────
    public static final ForgeConfigSpec.IntValue MAX_MINING_LEVEL = BUILDER
            .comment("Max mining level. Each level grants +1% mining speed. (KJS: 50)")
            .defineInRange("maxMiningLevel", 50, 0, 100);

    public static final ForgeConfigSpec.DoubleValue MINING_PROGRESS_PER_BREAK = BUILDER
            .comment("Mining progress per block broken (100 = one level).")
            .defineInRange("miningProgressPerBreak", 0.5, 0.0, 100.0);

    // ────────────────────────── 负重系统 ──────────────────────────
    public static final ForgeConfigSpec.IntValue OVERWEIGHT_BACKPACK_LIMIT = BUILDER
            .comment("Backpacks in inventory before overweight triggers.")
            .defineInRange("overweightBackpackLimit", 2, 1, 64);

    public static final ForgeConfigSpec.IntValue OVERWEIGHT_TENT_LIMIT = BUILDER
            .comment("Tents in inventory before overweight triggers.")
            .defineInRange("overweightTentLimit", 1, 1, 64);

    // ────────────────────────── 抑郁系统 ──────────────────────────
    public static final ForgeConfigSpec.IntValue DEPRESSION_SLEEP_TICK_LIGHT = BUILDER
            .comment("Ticks without sleep for light depression. (36000 = 30min)")
            .defineInRange("depressionSleepTicksLight", 36000, 0, 240000);

    public static final ForgeConfigSpec.IntValue DEPRESSION_SLEEP_TICK_HEAVY = BUILDER
            .comment("Ticks without sleep for heavy depression. (72000 = 60min)")
            .defineInRange("depressionSleepTicksHeavy", 72000, 0, 240000);

    // ────────────────────────── 疾病系统 ──────────────────────────
    public static final ForgeConfigSpec.IntValue COLD_RAIN_TICKS = BUILDER
            .comment("Ticks in rain (without umbrella) to risk cold. (4800 = 4min)")
            .defineInRange("coldRainTicks", 4800, 0, 240000);

    public static final ForgeConfigSpec.DoubleValue COLD_CHANCE = BUILDER
            .comment("Chance of catching a cold after enough rain exposure.")
            .defineInRange("coldChance", 0.3, 0.0, 1.0);

    // ────────────────────────── 恐慌系统 ──────────────────────────
    public static final ForgeConfigSpec.IntValue PANIC_MONSTER_COUNT = BUILDER
            .comment("Nearby monsters to trigger panic.")
            .defineInRange("panicMonsterCount", 10, 1, 100);

    public static final ForgeConfigSpec.DoubleValue PANIC_RADIUS = BUILDER
            .comment("Radius to check for monsters.")
            .defineInRange("panicRadius", 12.0, 1.0, 64.0);

    public static final ForgeConfigSpec.IntValue NUMB_KILL_THRESHOLD = BUILDER
            .comment("Monster kills to become numb (immune to panic).")
            .defineInRange("numbKillThreshold", 100, 1, 10000);

    public static final ForgeConfigSpec.IntValue ADDICTION_TRIGGER_COUNT = BUILDER
            .comment("Consumptions to trigger addiction.")
            .defineInRange("addictionTriggerCount", 3, 1, 100);

    public static final ForgeConfigSpec.IntValue ADDICTION_CRAVING_TICKS = BUILDER
            .comment("Ticks without substance before craving. (36000 = 30min)")
            .defineInRange("addictionCravingTicks", 36000, 0, 240000);

    public static final ForgeConfigSpec.IntValue ADDICTION_QUIT_TICKS = BUILDER
            .comment("Ticks without substance to overcome addiction. (86400 = 72min)")
            .defineInRange("addictionQuitTicks", 86400, 0, 480000);

    public static final ForgeConfigSpec.IntValue CALM_DURATION_TICKS = BUILDER
            .comment("Duration of calm effect from smoking/drinking. (6000 = 5min)")
            .defineInRange("calmDurationTicks", 6000, 0, 240000);

    // ────────────────────────── 老数据兼容 ──────────────────────────
    public static final ForgeConfigSpec.BooleanValue USE_LEGACY_BONUSES = BUILDER
            .comment("Read injectionCount/exerciseLevel from KubeJS persistent data when mod data is absent.")
            .define("useLegacyProgressionBonuses", true);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private StaminaConfig() {
    }
}