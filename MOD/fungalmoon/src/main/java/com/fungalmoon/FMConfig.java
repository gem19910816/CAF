package com.fungalmoon;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

import java.util.List;

/**
 * 月球真菌入侵 - 全权配置
 * 弃用 Spore 原版配置，所有逻辑由此模组控制
 */
public class FMConfig {

    public static final ForgeConfigSpec SPEC;

    // 袭击周期
    public static final ForgeConfigSpec.IntValue RAID_CYCLE_DAYS;
    // 感染点设置
    public static final ForgeConfigSpec.IntValue SPAWN_RADIUS_MIN;
    public static final ForgeConfigSpec.IntValue SPAWN_RADIUS_MAX;
    public static final ForgeConfigSpec.IntValue INFECTION_RADIUS;
    public static final ForgeConfigSpec.IntValue INFECTION_SPREAD_TIME;
    public static final ForgeConfigSpec.IntValue INFECTION_SPAWN_TIME;
    // 阶梯强度
    public static final ForgeConfigSpec.IntValue ESCALATION_THRESHOLD;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> TIER1_MOBS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> TIER2_MOBS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> TIER3_MOBS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> TIER4_MOBS;
    // 红夜
    public static final ForgeConfigSpec.DoubleValue REDNIGHT_HEALTH;
    public static final ForgeConfigSpec.DoubleValue REDNIGHT_ARMOR;
    // 广播
    public static final ForgeConfigSpec.BooleanValue ANNOUNCE_RAIDS;
    public static final ForgeConfigSpec.BooleanValue ANNOUNCE_CLEAN;
    public static final ForgeConfigSpec.BooleanValue ANNOUNCE_ESCALATION;

    // 真菌怪属性接管（替代 Spore 原版配置）
    public static final ForgeConfigSpec.BooleanValue OVERRIDE_MOB_STATS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> MOB_STATS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("lunar_raids");
        b.comment("月球真菌入侵 - 袭击设置");

        RAID_CYCLE_DAYS = b.comment("袭击周期（游戏日）。每 N 天触发一次感染点")
                .defineInRange("raidCycleDays", 7, 1, 365);
        SPAWN_RADIUS_MIN = b.comment("感染点生成位置距目标玩家的最小距离（格）")
                .defineInRange("spawnRadiusMin", 24, 8, 256);
        SPAWN_RADIUS_MAX = b.comment("感染点生成位置距目标玩家的最大距离（格）")
                .defineInRange("spawnRadiusMax", 48, 8, 256);
        INFECTION_RADIUS = b.comment("感染点初始半径（格）")
                .defineInRange("infectionRadius", 3, 1, 16);
        INFECTION_SPREAD_TIME = b.comment("感染点扩散时间（tick，2400=2分钟）")
                .defineInRange("infectionSpreadTime", 2400, 200, 72000);
        INFECTION_SPAWN_TIME = b.comment("感染点刷怪时间（tick，3600=3分钟）")
                .defineInRange("infectionSpawnTime", 3600, 200, 72000);

        ESCALATION_THRESHOLD = b.comment("每成功处理多少次袭击后，怪物强度提升一级")
                .defineInRange("escalationThreshold", 3, 1, 100);

        TIER1_MOBS = b.comment("第1级怪物（初期弱怪）")
                .defineList("tier1Mobs",
                        List.of("spore:inf_human", "spore:inf_villager", "spore:scamper"),
                        o -> o instanceof String);
        TIER2_MOBS = b.comment("第2级怪物（进化感染体）")
                .defineList("tier2Mobs",
                        List.of("spore:leaper", "spore:stalker", "spore:spitter", "spore:bloater"),
                        o -> o instanceof String);
        TIER3_MOBS = b.comment("第3级怪物（强力进化体/器官体）")
                .defineList("tier3Mobs",
                        List.of("spore:brute", "spore:knight", "spore:slasher", "spore:howler", "spore:vigil"),
                        o -> o instanceof String);
        TIER4_MOBS = b.comment("第4级怪物（超级感染体/灾害级）")
                .defineList("tier4Mobs",
                        List.of("spore:usurper", "spore:umarmed", "spore:gastgaber", "spore:specter", "spore:reconstructor",
                                "spore:gargoyle", "spore:gorgon", "spore:reaper", "spore:vanguard"),
                        o -> o instanceof String);

        REDNIGHT_HEALTH = b.comment("红夜（5岁土丘）最大生命值")
                .defineInRange("rednightHealth", 500.0D, 100.0D, 10000.0D);
        REDNIGHT_ARMOR = b.comment("红夜护甲值")
                .defineInRange("rednightArmor", 20.0D, 0.0D, 100.0D);

        ANNOUNCE_RAIDS = b.comment("袭击触发时是否向全服玩家广播警告")
                .define("announceRaids", true);
        ANNOUNCE_CLEAN = b.comment("感染点被清理时是否广播")
                .define("announceClean", true);
        ANNOUNCE_ESCALATION = b.comment("怪物强度升级时是否广播")
                .define("announceEscalation", true);
        b.pop();

        // ==================== 真菌怪属性接管 ====================
        b.push("mob_stats_override");
        b.comment("接管 Spore 真菌怪属性（血量/伤害/护甲），格式：实体ID|血量|伤害|护甲");
        OVERRIDE_MOB_STATS = b.comment("是否启用属性接管（true=用下面的列表覆盖 Spore 原版数值）")
                .define("overrideMobStats", true);
        MOB_STATS = b.comment("属性列表，格式：spore:实体名|最大生命|攻击伤害|护甲值")
                .defineList("mobStats",
                        List.of(
                                // 基础感染体
                                "spore:inf_human|20|3|0",
                                "spore:inf_villager|20|3|0",
                                "spore:scamper|16|2|0",
                                "spore:leaper|24|4|2",
                                "spore:stalker|24|4|2",
                                "spore:spitter|24|3|2",
                                "spore:bloater|30|5|4",
                                // 进化体
                                "spore:brute|40|7|6",
                                "spore:knight|35|6|8",
                                "spore:slasher|30|6|4",
                                "spore:howler|28|5|4",
                                "spore:vigil|35|6|6",
                                "spore:mephitic|30|5|4",
                                "spore:thorn|32|6|5",
                                "spore:griefer|35|7|5",
                                "spore:nuclea|40|8|6",
                                "spore:volatile|25|6|3",
                                "spore:jagd|30|6|4",
                                "spore:braiomil|35|6|5",
                                "spore:busser|30|5|4",
                                "spore:scavenger|25|4|3",
                                "spore:naiad|28|5|4",
                                // 器官体
                                "spore:umarmed|50|8|8",
                                "spore:usurper|45|7|7",
                                "spore:braurei|40|6|6",
                                "spore:verva|35|5|5",
                                // 超级感染体/灾害
                                "spore:gastgaber|60|9|10",
                                "spore:specter|55|8|9",
                                "spore:reconstructor|70|10|12",
                                "spore:gargoyle|50|8|8",
                                "spore:gorgon|55|9|9",
                                "spore:reaper|65|10|10",
                                "spore:vanguard|60|9|9",
                                "spore:sieger|80|12|15",
                                "spore:hindenburg|70|10|12",
                                "spore:howitzer|75|11|13",
                                "spore:gazenbreacher|85|12|14",
                                // 特殊
                                "spore:mound|20|0|2",
                                "spore:proto|100|10|10"
                        ),
                        o -> o instanceof String);
        b.pop();

        SPEC = b.build();
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC, "fungalmoon-raids.toml");
    }
}
