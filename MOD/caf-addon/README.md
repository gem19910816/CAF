# caf 末日功能性附属（CAF Doomsday Functionality Addon）

**Doomsday Functionality 模组的小附属。**

## 这个 mod 是干什么的

装了 **Doomsday Functionality** 的服务器里，玩家右键一个空容器时，原模组只会弹一句「空的」提示。
本附属通过 Mixin 拦截 `OpenLootableBlockPacket`，**让空容器直接打开**（跟原版普通容器行为一致），省去那一次多余的提示。

## 基本信息

- modId：`caf`
- 包名：`com.caf.addon`
- 游戏版本：Minecraft 1.20.1 / Forge 47.4.10 / Java 17
- 前置依赖：`doomsday_functionality`（必装）
- 构建产物：`build/libs/caf-doomsday-functionality-1.0.0.jar`

## 构建

```bash
JAVA_HOME=<jdk17路径> ./gradlew build --offline
```

源码结构：

- `CafAddon.java` — 主入口
- `mixin/OpenLootableBlockPacketMixin.java` — 空容器直接打开的 Mixin