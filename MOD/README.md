# MOD — CAF 自研模组源码

**一个模组一个子目录，全部为 Forge 1.20.1 / Java 17 工程，每个子目录内有中文 README 介绍。**

| 子目录 | 中文名 | modId | 是什么 |
| --- | --- | --- | --- |
| [quest-system](quest-system/) | 日常任务系统 | `gearsandflesh_quests` | 服务端权威的每日/每周/特殊任务，管理员游戏内编辑 |
| [global-market](global-market/) | 全球市场 | `gearsandflesh_market` | 服务端权威玩家交易市场，`caf:money` 实物货币 |
| [tarkov_stamina_mod](tarkov_stamina_mod/) | CAF 生存核心 | `tarkov_stamina` | 体力/职业/状态/生存 + 108 格军用背包 |
| [vehicle-lock-mod](vehicle-lock-mod/) | 车锁与加油 | `vehiclelock` | Superb Warfare 载具上锁 + 加油机 |
| [bodybag](bodybag/) | 裹尸袋 | `bodybag` | Corpse 附属，长按右键收取尸体 |
| [nofeetplace](nofeetplace/) | 禁止脚下放方块 | `nofeetplace` | 禁止腾空放方块，防垫脚 |
| [gunpack_filter](gunpack_filter/) | TaCZ 枪包筛选 | `gunpackfilter` | TaCZ 创造模式按枪包筛选 |
| [no_vines_addon](no_vines_addon/) | 移除藤蔓和树叶 | `no_vines_addon` | 清理失落之城建筑藤蔓 |
| [fungalmoon](fungalmoon/) | 月球真菌入侵 | `fungalmoon` | 真菌巢穴注入月球，跨维度进攻 |
| [mcphone](mcphone/) | MCphone（CAF 定制） | `mcphone` | 手机模组定制版，含红夜商店与 CAF App |
| [caf-addon](caf-addon/) | caf 末日功能性附属 | `caf` | 空容器直接打开 |

## 通用构建方式

```bash
JAVA_HOME=<jdk17路径> ./gradlew build --offline
```

产物在各自 `build/libs/`。