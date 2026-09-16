# TaCZ 枪包筛选（Gunpack Filter）

**TaCZ（Timeless and Classics Zero 枪械工艺）的创造模式附属模组。**

## 这个 mod 是干什么的

整合包装了多个 TaCZ 枪包（Warzone、CS2、LRTactical 等）时，创造模式物品列表里不同枪包的枪械、配件、弹药全混在一起很难找。
本模组**给 TaCZ 的创造模式页签加上「按枪包筛选」按钮**，把物品按所属枪包区分开，选哪个枪包就只看哪个包的东西。

- 自动扫描 TaCZ 枪包目录（目录型 + zip 型包都支持），判定每个命名空间归属哪个枪包
- 归属冲突时按「文件数多的包优先」判定
- 攻击性弱、无配置、对原版无侵入，只改创造模式页签

⚠️ 本模组是 **CAF 自研模组里唯一带 Mixin 的**，改动了 TaCZ 的创造模式界面。

## 基本信息

- modId：`gunpackfilter`
- 包名：`com.chaosz.gunpackfilter`
- 游戏版本：Minecraft 1.20.1 / Forge 47.4.10 / Java 17
- 前置依赖：`tacz`（必装）
- 构建产物：`build/libs/gunpackfilter-1.0.0.jar`

## 构建

```bash
JAVA_HOME=<jdk17路径> ./gradlew build --offline
```