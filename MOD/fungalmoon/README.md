# 月球真菌入侵（Fungal Moon）

**把真菌感染事件搬到 Ad Astra 月球上的战斗模组。**

## 这个 mod 是干什么的

把「真菌感染：孢子（Fungal Infection: Spore）」的**真菌巢穴群系/刷怪注入 Ad Astra 的月球维度**：

- 月球上会生成真菌巢穴（biome / structure / spawn 全部走数据包：`fungalmoon`、`spore`、`inqui` 命名空间）
- 巢穴中的 **Proto Hivemind**（原型蜂巢意识）会周期性派遣跨维度攻击队，**从月球入侵主世界进攻玩家**
- 通过 `LunarRaidManager` 管理月亮突袭，`HivemindTracker` 追踪蜂巢意识，`FMConfig` 提供配置

## 基本信息

- modId：`fungalmoon`
- 包名：`com.fungalmoon`
- 游戏版本：Minecraft 1.20.1 / Forge 47.4.10 / Java 17
- 前置依赖：`ad_astra`（Ad Astra）、`spore`（真菌感染:孢子）
- 构建产物：`build/libs/fungalmoon-1.0.0.jar`（部署名：CAF[月球真菌入侵]fungalmoon-1.0.0.jar）

## 构建

```bash
JAVA_HOME=<jdk17路径> ./gradlew build --offline
```