# 车辆锁模组 (Vehicle Lock)

给卓越前线(Superb Warfare)的载具添加僵尸毁灭工程风格的车辆锁系统。

## 功能
- 每辆载具独立钥匙（车牌号标识，如 `M1A2-4821`）
- 第一次上车自动认领车辆并发放钥匙
- 没钥匙不能上车（`EntityMountEvent` 拦截）
- 车主按 **Y** 批准其他玩家上车（60秒临时许可）
- 引擎锁：没钥匙强制熄火
- 钥匙串（收纳/取出钥匙）
- 管理员指令

## 管理员指令
- `/vehiclelock query <车牌>` — 查询车主
- `/vehiclelock list` — 列出所有已认领车辆
- `/vehiclelock player <玩家>` — 查某玩家的车
- `/vehiclelock remove <车牌>` — 删除登记

## 兼容
使用官方 `superbwarfare:vehicle_key` 作为钥匙物品，附加 NBT 字段 `VLUuid` / `VLPlate`。

## 构建
```bash
# 需要把 superbwarfare jar 放到 libs-repo，然后:
./gradlew build
# 输出: build/libs/vehiclelock-1.0.0.jar
```
