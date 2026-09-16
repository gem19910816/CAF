# CAF 模组合集

**齿轮与腐肉（Gears and Flesh）整合包的全部自研模组源码，统一环境：Forge 1.20.1 / Forge 47.4.10 / Java 17。**

所有模组源码放在 [`MOD/`](MOD/) 目录，**一个模组一个子目录，每个子目录都有中文 README**，说明这个模组是什么、干什么用。

## 模组清单

| 目录 | 中文名 | modId | 一句话说明 |
| --- | --- | --- | --- |
| [MOD/quest-system](MOD/quest-system/) | 日常任务系统 | `gearsandflesh_quests` | 服务端权威的每日/每周/特殊任务，管理员可在游戏内编辑 |
| [MOD/global-market](MOD/global-market/) | 全球市场 | `gearsandflesh_market` | 服务端权威的玩家交易市场，`caf:money` 实物货币 |
| [MOD/tarkov_stamina_mod](MOD/tarkov_stamina_mod/) | CAF 生存核心 | `tarkov_stamina` | 体力/职业/状态/生存面板 + 108 格军用背包 |
| [MOD/vehicle-lock-mod](MOD/vehicle-lock-mod/) | 车锁与加油 | `vehiclelock` | Superb Warfare 载具上锁 + 加油机加油 |
| [MOD/bodybag](MOD/bodybag/) | 裹尸袋 | `bodybag` | Corpse 附属：长按右键 10 秒收取尸体 |
| [MOD/nofeetplace](MOD/nofeetplace/) | 禁止脚下放方块 | `nofeetplace` | 禁止腾空时放方块，防垫脚垫高 |
| [MOD/gunpack_filter](MOD/gunpack_filter/) | TaCZ 枪包筛选 | `gunpackfilter` | TaCZ 创造模式按枪包筛选按钮 |
| [MOD/no_vines_addon](MOD/no_vines_addon/) | 移除藤蔓和树叶 | `no_vines_addon` | 清理失落之城建筑里的藤蔓 |
| [MOD/fungalmoon](MOD/fungalmoon/) | 月球真菌入侵 | `fungalmoon` | 真菌巢穴注入 Ad Astra 月球，跨维度进攻主世界 |
| [MOD/mcphone](MOD/mcphone/) | MCphone 手机（CAF 定制版） | `mcphone` | 手机模组定制：红夜商店 + CAF 状态/市场/每日任务/商店 App |
| [MOD/caf-addon](MOD/caf-addon/) | caf 末日功能性附属 | `caf` | 空容器直接打开，不再弹「空的」 |

## 构建环境

- 本机没有系统 JDK，Java 17 在 `D:\blackmarket-build\.jdk17\jdk-17.0.19+10`（构建时用 `JAVA_HOME` 指定）
- 各工程支持离线构建：`JAVA_HOME=<jdk17> ./gradlew build --offline`
- 产物统一输出到各工程的 `build/libs/`，部署时按 `mods/` 下的 jar 中文前缀命名习惯改文件名

## 说明

- **mcphone** 基于上游 [november521/mcphone](https://github.com/november521/mcphone) 的 1.20.1-forge 分支（0.11.1）定制；
  ⚠️ 当前部署到服务器的 jar 与本源码构建产物有内容差异，替换前需核对
- 早期原型 gasoline-fuel-mod 与 doomsday_backpack 已分别并入 vehicle-lock-mod 与 tarkov_stamina_mod，归档在本地 `_archive/`