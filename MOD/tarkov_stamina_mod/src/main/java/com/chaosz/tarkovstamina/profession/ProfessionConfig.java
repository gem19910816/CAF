package com.chaosz.tarkovstamina.profession;

import java.util.ArrayList;
import java.util.List;

/**
 * 职业系统配置（木工 / 石工 / 技工 / 钓鱼）
 * <p>
 * 精确复刻 KJS 木工.js / 石工.js / 技工.js / 钓鱼升级.js 的配置。
 * </p>
 */
public final class ProfessionConfig {
    // ── 木工 ──
    public static final int[][] WOODCUTTER_STAGES = {
            {500, 0},   // count, amplifier
            {1500, 1},
            {3000, 2}
    };

    // ── 石工 ──
    public static final int[][] STONECUTTER_STAGES = {
            {1000, 0},
            {3000, 1},
            {6000, 2}
    };

    // ── 技工 ──
    public static final int[][] MECHANIC_STAGES = {
            {50, 0},
            {100, 1},
            {200, 2}
    };

    public static final String[] AMPLIFIER_NAMES = {"I", "II", "III"};
    public static final String SCREWDRIVER_ID = "survival_instinct:screwdriver_rapier";

    // ── 钓鱼升级 ──
    public static final int[] FISH_EXP_CONFIG = {200, 500}; // 升2级需要200次, 升3级需要500次
    public static final int FISH_CD = 100;                   // 抛竿检测冷却 5秒
    public static final int WAIT_LV2 = 100;                  // 2级咬钩等待 5秒
    public static final int WAIT_LV3 = 20;                   // 3级咬钩等待 1秒
    public static final int FISH_MAX = FISH_EXP_CONFIG.length + 1; // 3

    // ── 技工掉落表 ──
    /** 方块ID → 掉落项列表，懒初始化避免静态块过大 */
    private static java.util.Map<String, List<String[]>> MECHANIC_DROPS = null;

    public static java.util.Map<String, List<String[]>> getMechanicDrops() {
        if (MECHANIC_DROPS == null) {
            MECHANIC_DROPS = new java.util.HashMap<>();
            initDrops();
        }
        return MECHANIC_DROPS;
    }

    /** 车辆统一掉落 */
    public static final List<String[]> VEHICLE_ITEMS = List.of(
            new String[]{"create:iron_sheet", "1", "0.5"},
            new String[]{"create:cogwheel", "1", "0.7"},
            new String[]{"minecraft:iron_ingot", "1", "0.7"}
    );

    public static final List<String> VEHICLE_BLOCKS = List.of(
            "doomsday_decoration:discardwhitecar_1", "doomsday_decoration:discardwhitecar_2",
            "doomsday_decoration:discardredcar_1", "doomsday_decoration:discardredcar_2",
            "doomsday_decoration:discardbluecar_1", "doomsday_decoration:discardbluecar_2",
            "doomsday_decoration:discardgreencar_1", "doomsday_decoration:discardgreencar_2",
            "doomsday_decoration:discardgreycar_1", "doomsday_decoration:discardgreycar_2",
            "doomsday_decoration:discardblackcar_1", "doomsday_decoration:discardblackcar_2",
            "doomsday_decoration:discardkhakicar_1", "doomsday_decoration:discardkhakicar_2",
            "doomsday_decoration:discardwhitestationwagon", "doomsday_decoration:discardredstationwagon",
            "doomsday_decoration:discardbluestationwagon", "doomsday_decoration:discardgreenstationwagon",
            "doomsday_decoration:discardgreystationwagon", "doomsday_decoration:discardblackstationwagon",
            "doomsday_decoration:discardkhakistationwagon",
            "doomsday_decoration:discardpolicecar_1", "doomsday_decoration:discardpolicecar_2",
            "doomsday_decoration:discardpolicecar_3", "doomsday_decoration:discardpolicecar_4",
            "doomsday_decoration:discard_white_jeep_1", "doomsday_decoration:discard_white_jeep_2",
            "doomsday_decoration:discardredjeep_1", "doomsday_decoration:discardredjeep_2",
            "doomsday_decoration:discardbluejeep_1", "doomsday_decoration:discardbluejeep_2",
            "doomsday_decoration:discardgreenjeep_1", "doomsday_decoration:discardgreenjeep_2",
            "doomsday_decoration:discardgreyjeep_1", "doomsday_decoration:discardgreyjeep_2",
            "doomsday_decoration:discardblackjeep_1", "doomsday_decoration:discardblackjeep_2",
            "doomsday_decoration:discardbrownjeep_1", "doomsday_decoration:discardbrownjeep_2",
            "doomsday_decoration:discardwhitepickuptruck", "doomsday_decoration:discardredpickuptruck",
            "doomsday_decoration:discardbluepickuptruck", "doomsday_decoration:discardgreenpickuptruck",
            "doomsday_decoration:discardgreypickuptruck", "doomsday_decoration:discardblackpickuptruck",
            "doomsday_decoration:discardbrownpickuptruck",
            "doomsday_decoration:discardwhitevan_1", "doomsday_decoration:discardwhitevan_2",
            "doomsday_decoration:discard_redvan_1", "doomsday_decoration:discard_redvan_2",
            "doomsday_decoration:discard_bluevan_1", "doomsday_decoration:discard_bluevan_2",
            "doomsday_decoration:discardgreenvan_1", "doomsday_decoration:discardgreenvan_2",
            "doomsday_decoration:discardgreyvan_1", "doomsday_decoration:discardgreyvan_2",
            "doomsday_decoration:discardblackvan_1", "doomsday_decoration:discardblackvan_2",
            "doomsday_decoration:discardyellowvan_1", "doomsday_decoration:discardyellowvan_2",
            "doomsday_decoration:frontwhitesedan", "doomsday_decoration:rearwhitesedan",
            "doomsday_decoration:frontredsedan", "doomsday_decoration:rearredsedan",
            "doomsday_decoration:frontkhakisedan", "doomsday_decoration:rearkhakisedan",
            "doomsday_decoration:frontblacksedan", "doomsday_decoration:rearblacksedan",
            "doomsday_decoration:front_blue_sedan", "doomsday_decoration:rear_blue_sedan",
            "doomsday_decoration:frontgreensedan", "doomsday_decoration:reargreensedan",
            "doomsday_decoration:frontgraysedan", "doomsday_decoration:reargraysedan",
            "doomsday_decoration:whitestationwagon", "doomsday_decoration:redstationwagon",
            "doomsday_decoration:khakistationwagon", "doomsday_decoration:bluestationwagon",
            "doomsday_decoration:greentravelcar", "doomsday_decoration:greywagon",
            "doomsday_decoration:blackstationwagon",
            "doomsday_decoration:policecar_1", "doomsday_decoration:policecar_2",
            "doomsday_decoration:policecar_3", "doomsday_decoration:policecar_4",
            "doomsday_decoration:white_jeep_1", "doomsday_decoration:white_jeep_2",
            "doomsday_decoration:red_jeep_1", "doomsday_decoration:red_jeep_2",
            "doomsday_decoration:brown_jeep_1", "doomsday_decoration:brown_jeep_2",
            "doomsday_decoration:green_jeep_1", "doomsday_decoration:green_jeep_2",
            "doomsday_decoration:grey_jeep_1", "doomsday_decoration:grey_jeep_2",
            "doomsday_decoration:black_jeep_1", "doomsday_decoration:black_jeep_2",
            "doomsday_decoration:blue_jeep_1", "doomsday_decoration:blue_jeep_2"
    );

    private ProfessionConfig() {
    }

    /** 将 KJS 掉落表数据装入 Map（懒加载，避免静态初始化器过大） */
    private static void putDrop(String block, String item, int count, double chance) {
        MECHANIC_DROPS.computeIfAbsent(block, k -> new ArrayList<>())
                .add(new String[]{item, String.valueOf(count), String.valueOf(chance)});
    }

    // KJS 中 MECHANIC_DROPS 的简化映射（在 getMechanicDrops() 时懒加载）
    private static void initDrops() {
        putDrop("doomsday_decoration:office_chair", "create:andesite_alloy", 1, 0.7);
        putDrop("doomsday_decoration:office_chair", "create:cogwheel", 1, 0.5);
        putDrop("doomsday_decoration:classroomchairs", "minecraft:oak_planks", 1, 1.0);
        putDrop("doomsday_decoration:classroomchairs", "create:andesite_alloy", 1, 0.5);
        putDrop("doomsday_decoration:classroomchairs", "create:cogwheel", 1, 0.2);
        putDrop("doomsday_decoration:pottedplant", "minecraft:oak_planks", 1, 0.5);
        putDrop("doomsday_decoration:pottedplant", "zombiekit:plastics", 1, 0.5);
        putDrop("doomsday_decoration:lockers", "minecraft:oak_planks", 1, 1.0);
        putDrop("doomsday_decoration:lockers", "create:andesite_alloy", 1, 0.7);
        putDrop("doomsday_decoration:bed", "minecraft:oak_planks", 1, 0.7);
        putDrop("doomsday_decoration:bed", "create:andesite_alloy", 1, 0.5);
        putDrop("refurbished_furniture:spruce_toilet", "minecraft:oak_planks", 1, 0.7);
        putDrop("refurbished_furniture:spruce_toilet", "create:andesite_alloy", 1, 0.5);
        putDrop("doomsday_decoration:lockers_2", "minecraft:oak_planks", 1, 1.0);
        putDrop("doomsday_decoration:damageddoor_61", "minecraft:oak_planks", 1, 1.0);
        putDrop("doomsday_decoration:damageddoor_61", "create:shaft", 1, 0.5);
        putDrop("refurbished_furniture:white_kitchen_storage_cabinet", "minecraft:oak_planks", 1, 1.0);
        putDrop("refurbished_furniture:white_kitchen_storage_cabinet", "create:andesite_alloy", 1, 0.7);
        putDrop("refurbished_furniture:white_kitchen_drawer", "minecraft:oak_planks", 1, 0.7);
        putDrop("refurbished_furniture:white_kitchen_drawer", "create:andesite_alloy", 1, 0.5);
        putDrop("refurbished_furniture:white_kitchen_cabinetry", "minecraft:oak_planks", 1, 0.7);
        putDrop("refurbished_furniture:white_kitchen_cabinetry", "zombiekit:plastics", 1, 0.5);
        putDrop("doomsday_decoration:table", "minecraft:oak_planks", 1, 1.0);
        putDrop("doomsday_decoration:table", "minecraft:stick", 1, 1.0);
        putDrop("doomsday_decoration:damageddoor_71", "minecraft:oak_planks", 1, 1.0);
        putDrop("doomsday_decoration:damageddoor_71", "create:shaft", 1, 0.5);
        putDrop("refurbished_furniture:jungle_kitchen_storage_cabinet", "minecraft:oak_planks", 1, 1.0);
        putDrop("doomsday_decoration:goodsshelves_5", "minecraft:oak_planks", 1, 1.0);
        putDrop("doomsday_decoration:goodsshelves_5", "zombiekit:plastics", 1, 0.5);
        putDrop("doomsday_decoration:goodsshelves_5", "create:cogwheel", 1, 0.2);
        putDrop("doomsday_decoration:board", "minecraft:oak_planks", 1, 1.0);
        putDrop("doomsday_decoration:acrate_3", "minecraft:oak_planks", 1, 1.0);
        putDrop("doomsday_decoration:acrate_2", "minecraft:oak_planks", 1, 1.0);
        putDrop("doomsday_decoration:cabinet_2", "minecraft:oak_planks", 1, 0.7);
        putDrop("doomsday_decoration:cabinet_2", "minecraft:glass_pane", 1, 0.5);
        putDrop("doomsday_decoration:cabinet_3", "minecraft:oak_planks", 1, 0.7);
        putDrop("doomsday_decoration:cabinet_3", "minecraft:glass_pane", 1, 0.5);
        putDrop("doomsday_decoration:sofa_2", "minecraft:oak_planks", 1, 1.0);
        putDrop("doomsday_decoration:sofa_2", "minecraft:white_wool", 1, 1.0);
        putDrop("doomsday_decoration:sofa", "minecraft:oak_planks", 1, 1.0);
        putDrop("doomsday_decoration:sofa", "minecraft:white_wool", 1, 1.0);
        putDrop("refurbished_furniture:red_sofa", "minecraft:oak_planks", 1, 1.0);
        putDrop("refurbished_furniture:red_sofa", "minecraft:red_wool", 1, 1.0);
        putDrop("doomsday_decoration:watch", "create:andesite_alloy", 1, 0.9);
        putDrop("doomsday_decoration:watch", "create:precision_mechanism", 1, 0.5);
        putDrop("doomsday_decoration:watch", "create:electron_tube", 1, 0.5);
        putDrop("doomsday_decoration:pan", "create:andesite_alloy", 1, 0.9);
        putDrop("doomsday_decoration:pan", "create:iron_sheet", 1, 0.7);
        putDrop("doomsday_decoration:pan", "minecraft:iron_nugget", 1, 0.5);
        putDrop("doomsday_decoration:radiator", "create:andesite_alloy", 1, 0.9);
        putDrop("doomsday_decoration:radiator", "create:cogwheel", 1, 0.7);
        putDrop("doomsday_decoration:radiator", "create:iron_sheet", 1, 0.5);
        putDrop("refurbished_furniture:white_toilet", "create:andesite_alloy", 1, 0.7);
        putDrop("refurbished_furniture:white_toilet", "create:electron_tube", 1, 0.5);
        putDrop("refurbished_furniture:white_toilet", "create:cogwheel", 1, 0.3);
        putDrop("refurbished_furniture:light_fridge", "create:andesite_alloy", 1, 0.9);
        putDrop("refurbished_furniture:light_fridge", "create:electron_tube", 1, 0.5);
        putDrop("refurbished_furniture:black_kitchen_sink", "create:andesite_alloy", 1, 0.9);
        putDrop("refurbished_furniture:black_kitchen_sink", "create:iron_sheet", 1, 0.7);
        putDrop("doomsday_decoration:metaldrawer_2", "create:andesite_alloy", 1, 0.9);
        putDrop("doomsday_decoration:metaldrawer_2", "create:iron_sheet", 1, 0.7);
        putDrop("doomsday_decoration:extractor", "create:andesite_alloy", 1, 0.9);
        putDrop("doomsday_decoration:extractor", "create:iron_sheet", 1, 0.7);
        putDrop("doomsday_decoration:extractor", "create:cogwheel", 1, 0.5);
        putDrop("doomsday_decoration:metaldrawer", "create:andesite_alloy", 1, 0.9);
        putDrop("doomsday_decoration:metaldrawer", "create:iron_sheet", 1, 0.7);
        putDrop("doomsday_decoration:metaldrawer", "create:large_cogwheel", 1, 0.3);
        putDrop("doomsday_decoration:desk_2", "create:andesite_alloy", 1, 0.9);
        putDrop("doomsday_decoration:desk_2", "create:iron_sheet", 1, 0.7);
        putDrop("doomsday_decoration:insulationcabinet", "create:andesite_alloy", 1, 0.7);
        putDrop("doomsday_decoration:insulationcabinet", "create:iron_sheet", 1, 0.5);
        putDrop("doomsday_decoration:insulationcabinet", "create:cogwheel", 1, 0.3);
        putDrop("doomsday_decoration:mailbox_1", "create:andesite_alloy", 1, 0.9);
        putDrop("doomsday_decoration:mailbox_1", "create:iron_sheet", 1, 0.7);
        putDrop("doomsday_decoration:basketballhoop", "create:andesite_alloy", 1, 0.9);
        putDrop("doomsday_decoration:basketballhoop", "create:iron_sheet", 1, 0.7);
        putDrop("doomsday_decoration:browning_m_2", "create:precision_mechanism", 1, 0.7);
        putDrop("doomsday_decoration:browning_m_2", "create:andesite_alloy", 1, 0.5);
        putDrop("doomsday_decoration:browning_m_2", "create:large_cogwheel", 1, 0.3);
        putDrop("doomsday_decoration:firehydrant_2", "create:andesite_alloy", 1, 0.9);
        putDrop("doomsday_decoration:firehydrant_2", "create:iron_sheet", 1, 0.7);
        putDrop("doomsday_decoration:firehydrant_2", "create:cogwheel", 1, 0.3);
        putDrop("doomsday_decoration:shutter_3", "create:iron_sheet", 1, 1.0);
        putDrop("doomsday_decoration:host_2", "create:precision_mechanism", 1, 1.0);
        putDrop("doomsday_decoration:host_2", "create:electron_tube", 1, 0.7);
        putDrop("doomsday_decoration:host_2", "create:cogwheel", 1, 0.5);
        putDrop("doomsday_decoration:crt_3", "create:andesite_alloy", 1, 0.7);
        putDrop("doomsday_decoration:crt_3", "create:precision_mechanism", 1, 0.5);
        putDrop("doomsday_decoration:crt_3", "create:cogwheel", 1, 0.3);
        putDrop("doomsday_decoration:oldtv", "create:precision_mechanism", 1, 0.7);
        putDrop("doomsday_decoration:oldtv", "create:electron_tube", 1, 0.5);
        putDrop("doomsday_decoration:oldtv", "create:large_cogwheel", 1, 0.2);
        putDrop("doomsday_decoration:washingmachine", "create:andesite_alloy", 1, 0.9);
        putDrop("doomsday_decoration:washingmachine", "create:precision_mechanism", 1, 0.5);
        putDrop("doomsday_decoration:washingmachine", "create:electron_tube", 1, 0.5);
        putDrop("doomsday_decoration:washingmachine", "create:cogwheel", 1, 0.3);
        putDrop("doomsday_decoration:electricoven", "create:andesite_alloy", 1, 0.9);
        putDrop("doomsday_decoration:electricoven", "create:precision_mechanism", 1, 0.7);
        putDrop("doomsday_decoration:electricoven", "create:cogwheel", 1, 0.5);
        putDrop("doomsday_decoration:vendingmachine_2", "create:andesite_alloy", 1, 0.9);
        putDrop("doomsday_decoration:vendingmachine_2", "create:precision_mechanism", 1, 0.7);
        putDrop("doomsday_decoration:vendingmachine_2", "create:cogwheel", 1, 0.5);
        putDrop("doomsday_decoration:printer", "create:precision_mechanism", 1, 0.7);
        putDrop("doomsday_decoration:printer", "create:electron_tube", 1, 0.7);
        putDrop("doomsday_decoration:printer", "create:cogwheel", 1, 0.5);
        putDrop("doomsday_decoration:phone", "create:precision_mechanism", 1, 0.7);
        putDrop("doomsday_decoration:phone", "create:electron_tube", 1, 0.7);
        putDrop("doomsday_decoration:phone", "zombiekit:plastics", 1, 0.5);
        putDrop("doomsday_decoration:goodsshelves_6", "minecraft:oak_planks", 1, 0.7);
        putDrop("doomsday_decoration:goodsshelves_6", "minecraft:iron_ingot", 1, 0.5);
        putDrop("doomsday_decoration:plasticchair", "zombiekit:plastics", 1, 0.7);
        putDrop("doomsday_decoration:plasticchair", "create:andesite_alloy", 1, 0.5);
        putDrop("doomsday_decoration:plasticchair", "create:cogwheel", 1, 0.2);
        putDrop("doomsday_decoration:tabletop", "minecraft:oak_planks", 1, 0.7);
        putDrop("doomsday_decoration:tabletop", "create:andesite_alloy", 1, 0.5);
        putDrop("doomsday_decoration:grill", "create:iron_sheet", 1, 0.7);
        putDrop("doomsday_decoration:grill", "create:andesite_alloy", 1, 0.5);
        putDrop("doomsday_decoration:airconditioning", "create:iron_sheet", 1, 0.7);
        putDrop("doomsday_decoration:airconditioning", "create:precision_mechanism", 1, 0.5);
        putDrop("doomsday_decoration:airconditioning", "create:electron_tube", 1, 0.5);
        putDrop("doomsday_decoration:airconditioning", "create:cogwheel", 1, 0.3);
        putDrop("doomsday_decoration:crt", "minecraft:glass_pane", 1, 0.5);
        putDrop("doomsday_decoration:crt", "create:precision_mechanism", 1, 0.5);
        putDrop("doomsday_decoration:crt", "create:electron_tube", 1, 0.5);
        putDrop("doomsday_decoration:crt", "create:andesite_alloy", 1, 0.3);
        putDrop("doomsday_decoration:shelf_4", "minecraft:oak_planks", 1, 0.7);
        putDrop("doomsday_decoration:shelf_4", "minecraft:iron_ingot", 1, 0.5);
        putDrop("doomsday_decoration:shelf_4", "zombiekit:plastics", 1, 0.2);
    }
}