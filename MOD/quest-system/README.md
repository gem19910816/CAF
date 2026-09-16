# Gears and Flesh Quest System

首版任务板，适用于 Minecraft Forge 1.20.1。

## 玩家入口

- 按 `J` 打开任务面板
- 或使用 `/quests`
- 日常、周常会按服务器日期自动换周期；特殊任务持续存在
- 完成后在右侧详情点击“领取奖励”

## 管理员入口

- 使用 `/quests edit` 打开管理员编辑台（需要权限等级 2）
- 可以新建、编辑、停用/删除任务；定义会保存到世界的 SavedData
- 字段：ID、标题、描述、目标类型、目标生物/物品、数量、奖励货币
- “管理池”只显示当前启用的任务；“任务池”显示全部模板
- 目标类型支持“击杀生物”“收集物品”和“手动推进”
- 管理员可在目标搜索框里输入 ID 或名称，点击候选目标完成配置
- 使用 `/quests progress <玩家> <任务ID> <数量>` 可为测试或其他玩法手动推进进度

## 玩法接入

其他服务可以调用 `QuestService.recordProgress(player, questId, amount)`。
击杀进度已接入 `LivingDeathEvent`，收集进度已接入 `EntityItemPickupEvent`。
