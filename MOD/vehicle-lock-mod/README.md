# 车锁与加油（Vehicle Lock）

**Superb Warfare 模组载具的配套：上锁防盗 + 加油机加油。**

## 这个 mod 是干什么的

1. **车锁**：给载具上锁（钥匙扣），只有持对应钥匙扣的玩家能开走/使用，防止载具被偷。
   - `KeychainItem` 钥匙扣物品、`VehicleKeyHelper` 配钥匙逻辑、`VehicleLockHandler` 锁车判定、`VehicleLockData` 持久化数据
2. **加油**：加油机方块 + 油枪，为载具加汽油。
   - 继承自早期的独立原型 **gasoline-fuel-mod**（`com.caf.gasoline`），该原型已归档删除，加油逻辑全部吸收进本工程（常量集中在 `GasolineUtils.java`）

附带网络同步（`VehicleLockNetwork`）、中英文本地化、贴图、模型与合成配方。

## 基本信息

- modId：`vehiclelock`
- 包名：`com.vehiclelock.mod`
- 游戏版本：Minecraft 1.20.1 / Forge 47.4.10 / Java 17
- 前置依赖：`superbwarfare`（本地 `libs-repo` 已带 0.8.9 依赖 jar，可离线构建）、`createdieselgenerators`
- 构建产物：`build/libs/vehiclelock-1.0.0.jar`（部署名：[caf车锁]vehiclelock-1.0.0.jar）

## 构建

```bash
JAVA_HOME=<jdk17路径> ./gradlew build --offline
```