# 移除藤蔓和树叶（No Vines Addon）

**失落之城废墟外观修复附属 —— 只清理城市建筑里的藤蔓。**

## 这个 mod 是干什么的

失落之城（Lost Cities）生成的城市废墟里到处挂满藤蔓和被藤蔓缠住的树叶，看着又乱又挡视线。
本模组**只从失落之城的城市建筑中移除藤蔓**：

- 清理 ChaosZPack 自定义部件（`parts/`）里带藤蔓的建筑
- 关闭城市建筑随机的 `vineChance` 藤蔓生成
- **自然世界生成的藤蔓完全不受影响**（野外原始森林照旧）

## 基本信息

- modId：`no_vines_addon`
- 包名：`com.chaosz.novinesaddon`
- 游戏版本：Minecraft 1.20.1 / Forge 47.4.10 / Java 17
- 前置/联动：`lostcities`、`chaoszpack`、`keerdm_zombie_essentials`
- 构建产物：`build/libs/no_vines_addon-1.0.0.jar`

## 构建

```bash
JAVA_HOME=<jdk17路径> ./gradlew build --offline
```