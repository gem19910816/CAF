# 裹尸袋 (Body Bag) — Corpse 附属模组

一个简单的 [Corpse (为遗体)](https://github.com/henkelmax/corpse) 模组附属，添加了 **裹尸袋** 物品。

## 功能

- 手持裹尸袋在尸体附近**长按右键 10 秒**即可收取该尸体。
- 收取过程中如果**离开尸体 5 格**范围，自动取消。
- **一个裹尸袋只能收取一具尸体**，收取后袋子上会显示死者姓名。
- 收取的尸体**所有物品**（主手、副手、盔甲、额外物品）和**经验**都会转移到玩家身上。
- 背包满时剩余物品会掉落在尸体位置。
- 配方：8 任意羊毛 + 1 线 → 1 裹尸袋。

## 依赖

- **Minecraft 1.20.1** / **Forge 47.4.10+**
- **[Corpse 1.20.1-1.0.23+](https://www.curseforge.com/minecraft/mc-mods/corpse)**

## 构建

```bash
cd bodybag
# 确保已安装 Java 17 JDK
./gradlew build
```

构建产物在 `build/libs/bodybag-1.0.0.jar`。

## 配置

服务器配置文件 `bodybag-server.toml`：

- `collect_range`：收取范围（格），默认 5.0
- `collect_duration_ticks`：收取所需刻数，默认 200（10 秒）

---

# Body Bag — Corpse Addon

A simple addon for the [Corpse](https://github.com/henkelmax/corpse) mod that adds the **Body Bag** item.

## Features

- Hold right-click for **10 seconds** near a corpse to collect it.
- **Moving out of range** (5 blocks) cancels the collection.
- **One bag, one corpse** — the bag is marked with the deceased's name after collection.
- Transfers **all items** (main, offhand, armor, additional) and **XP** to the player.
- Overflow items drop at the corpse site.
- Recipe: 8 any wool + 1 string → 1 body bag.

## Dependencies

- **Minecraft 1.20.1** / **Forge 47.4.10+**
- **[Corpse 1.20.1-1.0.23+](https://www.curseforge.com/minecraft/mc-mods/corpse)**

## Build

```bash
./gradlew build
```

Output: `build/libs/bodybag-1.0.0.jar`

## Configuration

`bodybag-server.toml`:

- `collect_range`: collection range in blocks (default 5.0)
- `collect_duration_ticks`: ticks to hold right-click (default 200 = 10 s)