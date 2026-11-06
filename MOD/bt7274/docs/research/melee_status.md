# BB30 近战实现与原版机制核对

核对对象：`work/bt_bb30` 当前源码及资源。方法为只读代码与本地原版脚本核对；本次没有运行游戏、没有改动生产源码或数值，因此下述绑定存在不等于本版实机命中与画面已验收。

## 结论

当前 BT 有普通近战攻击：驾驶时默认按 **V**，可在游戏按键设置中重绑。它已有服务端伤害、全身动作、驾驶第一人称动作和内外音效绑定。当前没有原版“对濒毁敌方泰坦的双人同步处决”系统；不能把单人挥拳动作称为完整处决。

## 当前完整调用链

| 环节 | 实现及证据 |
|---|---|
| 输入 | `TitanClient.java:35,45,62,67` 注册 `MELEE`，默认 `GLFW_KEY_V`；按住时写入输入位 `16`，随 `TitanInputPayload` 发送。 |
| 服务端权限 | `TitanInputPayload.java` 只接受实际乘坐 `TitanEntity` 的 `ServerPlayer`；客户端不提交伤害、目标或命中位置。 |
| 触发 | `TitanEntity.java:462` 只在位 `16` 从未按下变为按下时调用 `meleeStrike()`，长按不会每 tick 连发。 |
| 状态限制 | `TitanEntity.java:573` 拒绝客户端执行、身体动作锁、换弹、Vortex、核心充能/活动和冷却期；成功后触发 `action/melee`。 |
| 伤害时点 | `BtAnimationTimings.java` 定义总长/冷却 `60 tick`，接触 `20 tick`；服务端递减 `meleeWindup` 到零时调用 `applyMeleeImpact()`。 |
| 命中形状 | `TitanEntity.java:579–584` 使用武器/驾驶员水平朝向前方 `3` 格处为中心，X/Z 各 ±2、Y 为机体脚底到 +5 的 AABB，遍历其中 `LivingEntity`。这是固定盒形区域判定，不是拳头骨骼扫掠或原版可见锥体判定。 |
| 目标排除 | `TitanEntity.java:506` 排除自己、当前乘客、主人、盟友、死亡实体以及创造/旁观玩家。 |
| 命中效果 | 对每个合格目标请求 `42` 伤害、`1.5` 击退、加 `4` 核心能量；盒内有目标即播放 `impact`。本次不调整这些玩法数值。 |
| 第三人称 | `TitanRenderer.java:92` 将 `melee` 指向全身动作索引 `10`；该动作拥有身体优先级。 |
| 第一人称 | `NativeFirstPersonRenderer.java:121–126` 选 `FirstPersonState.MELEE`；用 `BtActionClock.meleeCycle()` 读取原版 `melee_seq`，把首个原动作命中事件对齐 MC/BB 第 `20 tick`。 |
| 时钟数据 | `motion/bb_action_events.json` 的 `melee`：`total_ticks=60`、`contact_tick=20`、原第一人称首接触 cycle `10/24`；`0 s→0`、`1 s→10/24`、`3 s→1`，用单调连续 Hermite 曲线。加载时与服务端常量交叉检查。 |
| 挥拳声音 | `NativeAudioClient.bodyActionAudio()` 在动作龄 `.18 s` 跨过时触发一次；`sounds.json` 的 `melee` 与 `melee_1p` 分别绑定 `native_synced/world/` 和 `native_synced/cockpit/bt_melee_high_elbow_forward_bb`。这是原音频的 BB 时序适配版。 |
| 自动驾驶 | `autoPilotTick()` 目前对敌人调用 `mainFire()`，没有主动调用 `meleeStrike()`；所以“玩家能按 V 近战”不意味着自动 BT 会近身挥拳。 |

当前实现还有两个需如实保留的边界：命中盒遍历没有逐目标视线/墙体遮挡检测；增加核心能量和播放命中声前没有检查 `hurt()` 的成功返回值。以上是源码行为记录，本次没有擅自修订。

## 本地原版证据：普通挥拳与处决是两条机制

原版证据根目录：`F:/自制武器源文件/08_BT7274/research/native_bt/mechanics/local_sp_beacon/scripts/`。这里使用实际提取的本地 SP 脚本，未用 Northstar 分支参数冒充原版 BT。

1. `vscripts/melee/sh_melee_titan.gnut` 的 `TitanUnsyncedMelee()` 启动 `PLAYER_MELEE_STATE_TITAN_MELEE_JAB`。`TitanMeleeAttack()` 通过 `PlayerMelee_FindVisibleEntitiesInCone()` 寻找可见目标、分人形/泰坦/其他目标处理，再触发对应撞击效果和命中反馈。当前 MC AABB 仅是既有适配，不能称为同一判定。
2. `weapons/melee_titan_punch.txt` 的通用挥拳 SP 配置为范围 `260` Source 单位、角度 `30°`，`melee_attack_animtime=.8`、恢复 `1.0`。这些是通用武器配置；本次未找到并核实 Expedition 负载最终选择该武器或变体的完整映射，不能据此断言它就是每个剧情阶段 BT 的最终时长，也不将其直接换算覆盖当前 MC 动作。
3. `vscripts/melee/sh_melee.gnut:1039` 的 `ShouldPlayerExecuteTarget()`：泰坦攻击者要求目标也是泰坦，通常要求目标处于 `GetDoomedState()`；`ForceSyncedMelee` 为特殊强制例外，还会考虑处决许可及可配置的护盾限制。
4. 同文件 `CodeCallback_IsValidMeleeExecutionTarget()` 另检验存活、无敌、阵营、忙碌/处决状态、垂直距离、同步动作选择和 `PlayerMelee_IsExecutionReachable(...,.3)`。`GetLungeTargetForPlayer()` 还明确写了泰坦近战不使用人类式 lunge；不要凭空加一段人类近战冲刺冒充原版。
5. `vscripts/melee/sh_melee_synced_titan.gnut` 建立 `titan→titan` 同步处决 chooser，距离用 `TITAN_EXECUTION_RANGE`。`_melee_synced_titan.gnut` 建立攻击者、受击者的上下文锁、动作同步及执行收尾；根据负载与 `buddy` 类型选择处决方式，包含攻击方/受击方第一人称与第三人称序列。
6. `players/mp/titan_buddy.set` 明确 `subclass buddy`、`titanCharacterName bt`、`execution_anim titan_buddy`，说明 BT 属于上述原版体系，但文件存在并不能证明 MC 已实现其处决。

当前 MC `src/main/java` 针对 `execution|takedown|fatality` 的检索未发现对应实现，且现有攻击函数没有敌方泰坦濒毁资格、双实体配对、处决锁定或处决致死时点。上述原版机制因此应列为未实现，而非说“全部近战已还原”。

## 验证范围与后续验收

本次能够确认按键、网络、伤害和动作/音频的静态绑定链。尚未确认：实际 V 键操作后拳头与目标接触画面、隔墙命中、空挥/被免疫目标反馈、服务端低 TPS 下时序，以及当前内外音轨的实际听感。

若要将“有近战”升级为实机验收，应用同一个隔离客户端依次核对空挥、合格目标命中、主人/乘客/盟友排除、墙后目标、长按/重复按、换弹/Vortex 互斥及命中帧音画同步。完整泰坦处决需要独立设计两实体动作与状态恢复，不能仅增加一条动画命名或复用普通 `meleeStrike()` 宣称完成。
