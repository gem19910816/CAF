# 齿腐当前弹药生产链与 BT 补给接入边界

调查日期：2026-11-06。目标实例：`D:/PCL2/.minecraft/versions/齿腐新版本/`。已读 `ftb-quest-authoring/SKILL.md`。只读核对当前脚本、任务、数据目录，并定点读取两个 BT JAR 与 Zombie Survival Kit 的相关配方；没有全量解包、启动软件、添加配方或调整数值。

## 实况与主建议

当前包已经有弹壳/弹头、Create 序列装填、T3 工程钢、T3/T4 火控，以及按精确 ID 放行的载具炮弹/制导弹药。BT 补给应接这些真实产线，而非新增一套脱节的原料体系。

但**现存 T2 普通重型弹不应直接无成本转换成高阶 BT 20mm 机炮弹；T3 小火箭也不应直接充高阶跟踪导弹**。可利用现成材料和工位，另核对实际机炮/导弹的消耗与生产成本。BT 是否定位 T4 仍待用户决定，本报告没有将 T4 视为已批准。

本轮都是调查与建议，尚未实施补给接入。配方存在不证明最终运行加载；没有启动当前实例做 JEI 或实际生产验收。

## 当前有依据的获取链

下文脚本均相对实例 `kubejs/server_scripts/`。

### 火药

- 已装 `Zombie_Survival_Kit-2.1.8-neoforge-1.21.1.jar` 的 `data/zombiekit/recipe/gunpowder.json`：1 `zombiekit:sulfur` + 2 `zombiekit:saltpeter` + 3 `minecraft:charcoal` → 3 `minecraft:gunpowder`。没有在本次已查脚本找到移除这一精确配方的操作。
- 硝土：该 JAR 的 `saltpeter_soil.json` 由小麦+黏土球合成 `zombiekit:saltpeter_soil`。当前 `kubejs/data/create/recipe/mixing/saltpeter.json` 明确覆盖为硝土+1000mB 水、加热搅拌 → 2 `zombiekit:saltpeter`。
- 另有 `里德尔/CVIScript.js:251` 的离心配方：2 安山岩 → 7 `zombiekit:saltpeter`。离心机自身含工程钢板，不能把这条路线当开局手搓。
- `配方/机械动力合成.js:83`：玄武岩粉碎概率产硫磺（25%），并有煤/深板岩副产。`物品统一.js:50` 增加 `caf:unify/sulfur_grinding`，1 `survival_instinct:sulfur` → 3 `zombiekit:sulfur`。
- `配方/移除配方.js:126–127` 移除手工产 `zombiekit:sulfur` 的 shaped/shapeless 配方；不能沿用原 JAR 的四玄武岩手搓硫磺。统一脚本后增硫粉配方的最终先后加载需实机日志/配方计数确认。
- 同一移除文件禁用 `kaleidoscope_cookery:millstone/gunpowder_from_flint` 与 `tacz:gunpowder`。不能推荐这两条已移除捷径。

任务书 `config/ftbquests/quests/lang/zh_cn.snbt:2606` 也明确讲硫、硝石、木炭路线，与上述配方方向吻合。

### 弹壳、弹头与通用弹药

来源：`於笙芷寐配方/制枪系统配方.js` 的 `entries`；物品注册见 `kubejs/startup_scripts/於笙芷寐注册/制枪系统注册.js`。

| 配方 ID（`caf:firearms/ammo/parts/` 下） | 输入及工序 | 输出 |
|---|---|---|
| `small_caliber_casing` | 铜板冲压 | `caf:small_caliber_casing` ×1 |
| `medium_caliber_casing` | 黄铜板冲压 | `caf:medium_caliber_casing` ×1 |
| `large_caliber_casing` | `ad_astra:steel_plate` 冲压 | `caf:large_caliber_casing` ×1 |
| `small_caliber_warhead` | 铁锭切割 | `caf:small_caliber_warhead` ×2 |
| `medium_caliber_warhead` | `ad_astra:steel_ingot` 切割 | `caf:medium_caliber_warhead` ×2 |
| `large_caliber_warhead` | `ad_astra:steel_rod` 切割 | `caf:large_caliber_warhead` ×1 |

同脚本 `caf:firearms/ammo/line/` 的序列组装均 loops=1，最后冲压。下面数量不包含作为底料的弹壳组：

| 路线 | 输入 | 成品 |
|---|---|---|
| `pistol` | 小弹壳+2铜板+2火药+小弹头组 | 8 `tacz:ammo`，AmmoId=`tacz_unidict:pistol` |
| `rifle` | 中弹壳+3铁板+2火药+中弹头组 | 6 `tacz:ammo`，AmmoId=`tacz_unidict:rifle` |
| `sniper` | 大弹壳+3钢板+3火药+大弹头组 | 4 `tacz:ammo`，AmmoId=`tacz_unidict:sniper` |
| `shot` | 霰弹壳+2铁板+2火药+2铁粒 | 6 `tacz:ammo`，AmmoId=`tacz_unidict:shot` |
| `barrel` | 工程钢板作底+2TNT+红石火把+2钢板 | 2 `tacz:ammo`，AmmoId=`tacz_unidict:barrel` |

AmmoId 存在 `minecraft:custom_data` 组件内。它们不是不同注册物品 ID，不能仅以 `tacz:ammo` 匹配 BT 补给，也不能把通用“barrel”炮弹自动当 XO16 的 20mm。

该脚本关闭原 `tacz:gun_smith_table_crafting` 并按受控结果/配方白名单收口；设计新路线必须考虑这一收口机制，不能只写一个配方文件便假定保留。

任务对应：`chapters/2f5ddfe2e996f52a.snbt`（“枪械制造与供弹”）；语言说明列出的8/6/4/6批量与实际脚本一致。不能要求玩家收集所有弹种来证明一条线可用。

### 工业材料与机床

- `CAF工业主线.js`：T2 `caf:industry/t2/form_steel_ingot` 将生钢液压制成 `ad_astra:steel_ingot`，`.../steel_plate` 冲压钢板。
- T3 工程钢实际用 `caf:manganese_steel_*` ID：`engineering_billet` 混合4钢锭、煤类、2黑曜石粉和250mB岩浆（加热）→ 4坯；`engineering_forging` 锤锻6次（铁块砧）；`engineering_quench` 250mB水淬；`engineering_plate` 冲压出板。不能按旧名字把它视作普通铁板。
- 制枪脚本 `caf:firearms/parts/steel_spring`：钢杆经 `createvintageneoforged:coiling` → `caf:gun_steel_spring`。
- `.../fire_control_t3`：2工程钢板+精密机构+2钢弹簧 → `caf:gun_fire_control_t3`。
- `.../fire_control_t4`：2工程钢板+2 `caf:melee_precision_fasteners`+2精密机构+1 `create_new_age:overcharged_gold`+2钢弹簧 → `caf:gun_fire_control_t4`。
- 任务 T2 `caf_technology_t2.snbt` 有 `createvintageneoforged:lathe`、`belt_grinder`；T3 有 `helve_hammer`、`spring_coiling_machine`；T4 章展示离心机、激光、真空设备。任务说明也承认离心机可在具备T3工程钢时提前准备，不能仅凭放在哪章断言硬锁为T4。
- 当前工位入口受 `CAF工业工位分级修正.js`、`CAF工业核心入口收口.js`、`CAF工业配方接管.js` 等脚本管控。这里只确认上述具体材料链，没有逐项证明所有高级设备在最终加载状态下可生产。

## 当前载具炮弹/导弹：可作为 BT 成本对照

真实脚本：`zzz_CAF停用卓越前线与电力制造.js`，注释及配方为 2026-11-06 版本。保留17种载具弹药和普通空箱；`recipes` 先关闭旧制造再加精确 ID，`afterRecipes` 再清非白名单。它继续禁用卓越整车制造，不代表恢复了整个 superbwarfare 制造体系。

| 现存 recipe ID（`caf:vehicle_ammo/` 下） | 成品 ID、批量 | 实际材料边界 |
|---|---|---|
| `t2/rifle_ammo` | `superbwarfare:rifle_ammo` ×12 | 中弹壳、3铁板、2火药、中弹头，序列装填 |
| `t2/heavy_ammo` | `superbwarfare:heavy_ammo` ×8 | 大弹壳、3钢板、3火药、大弹头，序列装填 |
| `t2/small_shell_ap` | `superbwarfare:small_shell_ap` ×8 | 2钢板、2钢杆、2黄铜板、4火药，机械合成 |
| `t2/medium_anti_ground_missile` | `superbwarfare:medium_anti_ground_missile` ×1 | 4钢板、2黄铜板、精密机构、2TNT、4火药 |
| `t3/small_rocket` | `superbwarfare:small_rocket` ×4 | 2钢板、工程钢板、2黄铜板、4火药、TNT |
| `t3/medium_rocket_ap` | `superbwarfare:medium_rocket_ap` ×1 | 2小火箭、2工程钢板、2钢板、2火药；无火控组件 |
| `t3/medium_anti_air_missile` | `superbwarfare:medium_anti_air_missile` ×2 | T3火控、4钢板、2小火箭、2TNT |
| `t4/large_anti_ground_missile` | `superbwarfare:large_anti_ground_missile` ×2 | T4火控、4工程钢板、4钢板、4小火箭、4TNT |
| `t4/swarm_drone` | `superbwarfare:swarm_drone` ×4 | T4火控、4精密机构、4钢板、4TNT、4红石 |
| `t2/ammo_box` | `superbwarfare:ammo_box` ×1 | 5铁锭+绿染料；配方只给空箱，不附弹数 |

任务对应：`chapters/14938c1690bceb21.snbt`，“载具与补给”。`2FB6DA7C3A07EA51` 装填载具弹药；`EA1383DE1BA890CC` T3装甲与航空弹药；`982B38CD03E8826B` T4重炮与制导弹药。语言文件明确把高阶导弹与T3/T4火控衔接，和实际配方方向一致。

## BT 获取、现行数据包与尚未接上的部分

- 当前 `mods` 同时存在 `CAF-BT7274-1.21.1-1.0.0.jar` 和 `CAF-BT7274-Cube-1.21.1-2.1.0-bb27.jar`，均非 disabled。只记录实况，没有处理它们。文件并存不证明二者可共同加载。
- 两 JAR 都有 `data/bt7274/recipe/titanfall_beacon.json`：4铁锭+2铜锭+2红石块+下界之星 → `bt7274:titanfall_beacon`。
- 两 JAR 都有 `titan_battery.json`：4铁锭+2铜锭+2红石粉+萤石粉 → `bt7274:titan_battery`。当前没有从已查任务/脚本发现把它接成工程钢/军工链的逻辑。
- 未在当前 FTB章节/语言中找到 `bt7274`、BT-7274或机甲获取教学。`军用电台召唤机甲BOSS.js` 的 `caf:junyongdiantai` 实际召唤 `corundumguardian:corundum_guardian`，不是 BT，不能混用。
- 根 `datapacks/` 为空；“新的世界”及“新的世界 (1)”的 datapacks 为空。旧存档 `MC Zombie Apocalypse - Update #17.1` 有 bukkit/functions/owza_datapack，未将其当当前生产链依据，也未修改存档。当前活动世界及 enabled datapack 列表未做运行验证。
- 当前 BB30 源码的 reload/rocket 仍是 BT 内部状态机制；本报告没有实现读取上述弹药物品、20mm专用弹箱或追踪导弹补给。原 JAR 两个物品配方也不证明已经有实体耗弹供应系统。

## 推荐的选择边界（尚未实施）

**机炮：** `superbwarfare:heavy_ammo` 可作为现有重弹原料链和量产节奏的参照，甚至将来作为一部分加工底料；但它当前是T2批量8发，缺少高阶炮弹区分，不建议直接等量装满BT。若要保留XO16 20mm定位，专用20mm弹箱/弹药组件更能隔开普通机枪与机炮供给；新ID、箱容量、每发成本、是否回收空箱均待方案批准。可对照现有大弹壳/钢板/火药链以及 `small_shell_ap` 的成本，不能因为ID叫heavy就断言足够。

**锁定导弹：** 优先比较 `t3/medium_anti_air_missile` 的T3火控+小火箭+战斗部路线与 `t4/large_anti_ground_missile` 的T4火控+工程钢+多火箭路线。前者提供已有T3制导门槛，后者适合高阶多目标锁定弹的成本参照；`swarm_drone` 可辅助比较多目标/自主制导消耗。最终取哪级取决于用户定BT定位和实战供弹规模，不能自行宣布T4已定。

**不能直接替代：** 普通 `tacz_unidict:rifle`、`superbwarfare:rifle_ammo`、不带火控的 `small_rocket` / `medium_rocket_ap` 都不能单凭名称或现成可造就当BT高阶跟踪弹。弹体、推进剂与制导控制应分开核对；若重用现成导弹物品，也应精确匹配ID并验证补给扣除与批量，不能把“背包有任意导弹”视作可装填。

后续实施前需要最终确定BT阶段定位、20mm消耗与弹箱语义、锁定弹批量；然后只增量接入当前受控配方/任务，并核查实际加载、物品扣除与可生产性。本次未执行这些改动。
