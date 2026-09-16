# 禁止脚下放方块（No Feet Place）

**反「垫脚」反「塔柱」模组 —— 禁止玩家腾空时放方块。**

## 这个 mod 是干什么的

限制玩家在空中（脚下没有任何支撑物）放置方块，防止利用垫脚方块叠高（塔柱/垫脚）逃出战斗或跳过地形。

- **判定规则（v1.2.0 纯玩家状态模型）**：非创造、不在地面、不在水里、不爬梯子、不在载具上、不在岩浆里 → 取消右键放置
- 水里、梯子上、载具里、岩浆里**不拦**，创造模式**硬编码豁免**，不受任何限制
- 只拦截放置，不影响开箱子、按按钮等交互

> 历史：v1.1.x 曾用包围盒 + 落点投影 + 豁免列表的几何判定，被用户评价「不伦不类」已整体删除；
> 当前实现是玩家状态一行判定 + 取消事件，简单可靠。

## 基本信息

- modId：`nofeetplace`
- 包名：`com.chaosz.nofeetplace`
- 游戏版本：Minecraft 1.20.1 / Forge 47.4.10 / Java 17
- 构建产物：`build/libs/nofeetplace-1.2.0.jar`

## 配置（`config/nofeetplace.toml`）

- `show_message` — 拦截时是否提示玩家
- `message_cooldown_ticks` — 提示冷却（刻）

## 构建

```bash
JAVA_HOME=<jdk17路径> ./gradlew build --offline
```