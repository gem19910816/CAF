# 全球市场（Gears and Flesh Global Market）

**服务端权威的全球玩家交易市场，用 `caf:money` 实物货币交易。**

## 这个 mod 是干什么的

玩家可以把物品按自己定的价格**上架**到全球市场，挂牌物品由**服务器托管**（杜绝离线/复制漏洞），其他玩家用 `caf:money` 货币（KubeJS 注册的物理物品）**购买**。买卖流程完全服务端权威（服务端查价、扣款、发货），客户端只是展示界面。

**规则速览：**

- 上架：每个玩家每小时可创建 10 个挂牌；上架费为标价的 2%（最低 5 货币，不可退）；成交后再从卖家所得扣 3%
- 交付：买到的物品 / 卖出所得 / 取消 / 过期的挂牌，10 分钟后送入安全的邮箱；邮箱限 256 格（含在途占用），离线时物品安全托管
- 查询：搜索、排序、分类、分页全部在服务端完成
- 入口：`/market` 或配置的市场键打开界面
- 管理：权限等级 2 的操作员可用 `/market edit`，右键挂牌执行审计操作（复制物品 / 移除并退回 / 永久销毁异常物品）

客户端与服务端必须**同时安装**本模组；注册 `caf:money` 的 KubeJS 启动脚本两端也要一致。

## 基本信息

- modId：`gearsandflesh_market`
- 包名：`com.gearsandflesh.market`
- 游戏版本：Minecraft 1.20.1 / Forge 47.4.10 / Java 17
- 构建产物：`build/libs/gearsandflesh-global-market-0.1.0.jar`（部署名：[CAF市场]gearsandflesh-global-market-0.1.0.jar）

## 构建

```bash
JAVA_HOME=<jdk17路径> ./gradlew build --offline
```

---

# Gears and Flesh Global Market

Forge 1.20.1 player marketplace for the Gears and Flesh modpack.

## Behavior

- Players list inventory items at a price they choose.
- Listed items are held by the server until sold, cancelled, or expired.
- Purchases are paid with the physical item `caf:money`.
- Each player can create 10 listings per rolling hour. One listing counts once,
  regardless of how many individual items are in its stack, and cancellation
  does not restore the quota.
- Listing costs 2% of the asking price (minimum 5 `caf:money`, non-refundable).
  A completed sale also deducts 3% from the seller's proceeds.
- Purchased items, sale proceeds, cancelled listings, and expired listings are
  delivered to the safe mailbox after 10 minutes.
- A player's mailbox is limited to 256 stack slots, including slots reserved by
  deliveries that are still in transit.
- Sale proceeds and returned items are held safely when a player is offline or
  has no inventory space.
- Listing queries are searched, sorted, categorized, and paginated on the server.
- `/market` or the configurable market key opens the screen.
- Operators with permission level 2 can use `/market edit`. In this mode,
  right-clicking a listing opens audited actions to copy the item, remove and
  return the original to the seller, or permanently destroy an abnormal item
  without returning it.

The client and dedicated server must both install this mod. The KubeJS startup script that registers
`caf:money` must also be present on both sides.