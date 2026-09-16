# CAF 生存核心（Tarkov Stamina）

**服务器权威体力系统 + 职业/状态/生存面板 + 军用背包 —— 整合包的核心生存模组。**

## 这个 mod 是干什么的

早期只是塔科夫式体力系统，后来陆续融合了 CAF 整合包的多个生存功能，成为现在的「CAF 生存核心」（部署名 `caf_stamina_core-1.0.0.jar`）：

- **体力系统**：服务端权威。默认约 40 秒连续疾跑；跳跃耗体力；停止 3 秒后开始恢复；蹲伏/骑乘恢复更快、睡觉瞬间回满；体力归零后疾跑锁定，直到恢复 15%
- **`/caf` 面板**：体力 / 职业 / 状态 / 生存 四个分页（多分页终端 UI），命令、笔记本（caf:bijibendiannao）、监测终端统一入口
- **职业系统**：木工 / 石工 / 技工 / 钓鱼 已全部迁入 mod
- **军用背包**：108 格（12×9，旧 doomsday_backpack 45 格已融合升级），支持滚动、Curios 背部槽、3D 模型与配方
- **其他**：拉屎系统（PoopSystem）、屎球投掷、技能终端兼容、身体监测终端、创造标签页、塔科夫风格 HUD（可拖拽）

## 基本信息

- modId：`tarkov_stamina`
- 包名：`com.chaosz.tarkovstamina`
- 游戏版本：Minecraft 1.20.1 / Forge 47.4.10 / Java 17
- 前置依赖：`curios`（饰品栏）
- 构建产物：`build/libs/tarkov_stamina-1.0.0.jar`（部署名：caf_stamina_core-1.0.0.jar）

## 配置

首次启动生成 `config/tarkov_stamina-common.toml`。

## 构建

```bash
JAVA_HOME=<jdk17路径> ./gradlew build --offline
```

---

# Tarkov Stamina

Forge 1.20.1 server-authoritative stamina with a compact tactical HUD.

## Behavior

- About 40 seconds of continuous sprinting at default settings.
- Jumping costs stamina.
- Regeneration starts after a 3 second delay.
- Crouching and riding recover faster; sleeping refills immediately.
- At zero stamina, sprinting stays locked until 15% has recovered.
- HUD fades out when stamina is full and idle.
- Existing `injectionCount` and `exerciseLevel` Forge persistent values are read as optional bonuses.

Configuration is generated at `config/tarkov_stamina-common.toml` after the first launch.