package com.gearsandflesh.quests;

import com.gearsandflesh.quests.data.QuestDefinition;
import com.gearsandflesh.quests.data.QuestEntry;
import com.gearsandflesh.quests.data.QuestGoalType;
import com.gearsandflesh.quests.data.QuestReward;
import com.gearsandflesh.quests.data.QuestRewardKind;
import com.gearsandflesh.quests.data.QuestSavedData;
import com.gearsandflesh.quests.data.QuestType;
import com.gearsandflesh.quests.network.QuestNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class QuestService {
    private QuestService() {
    }

    public static List<QuestEntry> snapshot(ServerPlayer player, boolean admin) {
        QuestSavedData data = QuestSavedData.get(player.server);
        List<QuestEntry> result = new ArrayList<>();
        for (QuestDefinition definition : data.definitions(admin)) {
            if (!admin && !prerequisitesMet(player.server, player.getUUID(), definition)) {
                continue;
            }
            // One independent instance per pool the quest belongs to. Admins additionally get the
            // idle staging instance; players never do. UIs dedupe to one row per quest.
            for (QuestType group : definition.types()) {
                if (!admin && group == QuestType.IDLE) {
                    continue;
                }
                var state = data.state(player.getUUID(), stateKey(definition.id(), group),
                        periodFor(group), definition.revision());
                result.add(new QuestEntry(definition, group.name(), state.progress(), state.claimed()));
            }
        }
        return result;
    }

    /** Progress/claim records are keyed per pool instance: "questId@GROUP". */
    public static String stateKey(String questId, QuestType group) {
        return questId + "@" + group.name();
    }

    /** The reset cadence of one pool. */
    public static String periodFor(QuestType group) {
        return switch (group) {
            case DAILY -> LocalDate.now().toString();
            case WEEKLY -> {
                LocalDate date = LocalDate.now();
                WeekFields fields = WeekFields.of(Locale.ROOT);
                yield date.get(fields.weekBasedYear()) + "-W" + date.get(fields.weekOfWeekBasedYear());
            }
            case SPECIAL -> "special";
            case IDLE -> "idle"; // staging pool: no reset, players never see it
        };
    }

    /** The fastest-resetting group of a quest (daily beats weekly beats special beats idle). */
    public static QuestType fastestGroup(QuestDefinition definition) {
        if (definition.types().contains(com.gearsandflesh.quests.data.QuestType.DAILY)) {
            return com.gearsandflesh.quests.data.QuestType.DAILY;
        }
        if (definition.types().contains(com.gearsandflesh.quests.data.QuestType.WEEKLY)) {
            return com.gearsandflesh.quests.data.QuestType.WEEKLY;
        }
        if (definition.types().contains(com.gearsandflesh.quests.data.QuestType.SPECIAL)) {
            return com.gearsandflesh.quests.data.QuestType.SPECIAL;
        }
        return com.gearsandflesh.quests.data.QuestType.IDLE;
    }

    /** Progress cadence of a quest overall: the fastest pool it belongs to. */
    public static String periodKey(QuestDefinition definition) {
        return periodFor(fastestGroup(definition));
    }

    /** A quest becomes visible/claimable once every prerequisite quest has been claimed in any pool. */
    public static boolean prerequisitesMet(MinecraftServer server, UUID playerId, QuestDefinition definition) {
        if (definition.prerequisites().isEmpty()) {
            return true;
        }
        QuestSavedData data = QuestSavedData.get(server);
        for (String prereqId : definition.prerequisites()) {
            QuestDefinition prereq = data.definition(prereqId);
            if (prereq == null) {
                continue;
            }
            boolean anyClaimed = false;
            for (QuestType group : prereq.types()) {
                if (data.state(playerId, stateKey(prereq.id(), group), periodFor(group), prereq.revision()).claimed()) {
                    anyClaimed = true;
                    break;
                }
            }
            if (!anyClaimed) {
                return false;
            }
        }
        return true;
    }

    /** Gameplay integrations call this whenever a matching action occurs. */
    public static void recordProgress(ServerPlayer player, String questId, int amount) {
        recordProgress(player, questId, amount, null);
    }

    /** Applies progress to every pool instance of the quest; each instance counts and claims separately. */
    public static void recordProgress(ServerPlayer player, String questId, int amount, String foundId) {
        QuestSavedData data = QuestSavedData.get(player.server);
        QuestDefinition definition = data.definition(questId);
        if (definition == null || !definition.enabled() || amount <= 0) return;
        boolean changed = false;
        boolean completed = false;
        int shownProgress = 0;
        for (QuestType group : definition.types()) {
            if (group == QuestType.IDLE) {
                continue; // idle is a staging pool: no progress until pushed to a real group
            }
            String key = stateKey(questId, group);
            String period = periodFor(group);
            QuestSavedData.PlayerQuestState old = data.state(player.getUUID(), key, period, definition.revision());
            if (foundId != null && old.found().contains(foundId)) {
                continue; // this pool instance already credited that place
            }
            int next = Math.min(definition.target(), old.progress() + amount);
            if (next == old.progress()) {
                continue;
            }
            java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>(old.found());
            if (foundId != null) {
                found.add(foundId);
            }
            data.update(player.getUUID(), key,
                    new QuestSavedData.PlayerQuestState(period, definition.revision(), next, old.claimed(), List.copyOf(found)));
            changed = true;
            completed |= old.progress() < definition.target() && next >= definition.target();
            shownProgress = next;
        }
        if (!changed) {
            return;
        }
        if (completed) {
            player.displayClientMessage(Component.literal("[任务] ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(definition.title()).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" 已完成，快去领取奖励！").withStyle(ChatFormatting.GREEN)), true);
            player.level().playSound(null, BlockPos.containing(player.position()),
                    SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.5f, 1.5f);
            QuestNetwork.sendSnapshot(player, isAdmin(player), true);
        } else {
            player.displayClientMessage(Component.literal("[任务] ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(definition.title() + " ").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(shownProgress + "/" + definition.target()).withStyle(ChatFormatting.AQUA)), true);
            QuestNetwork.sendSnapshot(player, isAdmin(player), false);
        }
    }

    public static void recordKill(ServerPlayer player, net.minecraft.world.entity.LivingEntity victim) {
        if (victim == null) return;
        String id = ForgeRegistries.ENTITY_TYPES.getKey(victim.getType()).toString();
        recordGoalProgress(player, QuestGoalType.KILL, id, 1);
    }

    /** Inventory-diff based collection: counts items gained by any means (pickup, chest, crafting, trading). */
    public static void recordCollectedDelta(ServerPlayer player, String itemId, int amount) {
        if (itemId == null || amount <= 0) return;
        recordGoalProgress(player, QuestGoalType.COLLECT, itemId, amount);
    }

    /** Exploration progress: entering a tracked biome or structure (once per distinct id per period). */
    public static void recordExplore(ServerPlayer player, QuestGoalType type, String id) {
        if (id == null) return;
        recordGoalProgress(player, type, id, 1);
    }

    private static void recordGoalProgress(ServerPlayer player, QuestGoalType goalType, String goalId, int amount) {
        QuestSavedData data = QuestSavedData.get(player.server);
        for (QuestDefinition definition : data.definitions(false)) {
            if (definition.goalType() != goalType || !definition.goalIds().contains(goalId)) {
                continue;
            }
            if (goalType == QuestGoalType.BIOME || goalType == QuestGoalType.STRUCTURE) {
                // Exploration counts each distinct place once per pool instance; recordProgress
                // dedupes per instance internally, so standing still cannot farm progress.
                recordProgress(player, definition.id(), 1, goalId);
            } else {
                // Kill/collect accumulate amounts: every kill and every gained item counts.
                recordProgress(player, definition.id(), amount, null);
            }
        }
    }

    public static OperationResult claim(ServerPlayer player, String questId, String group) {
        QuestSavedData data = QuestSavedData.get(player.server);
        QuestDefinition definition = data.definition(questId);
        OperationResult result = claimInternal(player, data, definition, group);
        if (result.success()) {
            player.level().playSound(null, BlockPos.containing(player.position()),
                    SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.8f, 1.0f);
        }
        return result;
    }

    /** Claims every finished, unclaimed pool instance; returns a summary. */
    public static OperationResult claimAll(ServerPlayer player) {
        QuestSavedData data = QuestSavedData.get(player.server);
        List<String> claimed = new ArrayList<>();
        for (QuestDefinition definition : data.definitions(false)) {
            for (QuestType group : definition.types()) {
                if (group == QuestType.IDLE) {
                    continue; // not published — nothing to claim
                }
                QuestSavedData.PlayerQuestState state = data.state(player.getUUID(),
                        stateKey(definition.id(), group), periodFor(group), definition.revision());
                if (state.claimed() || state.progress() < definition.target()) {
                    continue;
                }
                OperationResult result = claimInternal(player, data, definition, group.name());
                if (result.success()) {
                    claimed.add(definition.title() + "（" + group.label() + "）");
                }
            }
        }
        if (claimed.isEmpty()) {
            return OperationResult.error("没有可领取的奖励");
        }
        player.level().playSound(null, BlockPos.containing(player.position()),
                SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.8f, 1.0f);
        return OperationResult.ok("已领取 " + claimed.size() + " 个任务：" + String.join("、", claimed));
    }

    private static OperationResult claimInternal(ServerPlayer player, QuestSavedData data,
                                                 QuestDefinition definition, String groupName) {
        if (definition == null || !definition.enabled()) return OperationResult.error("任务不存在或已停用");
        QuestType group;
        try {
            group = QuestType.valueOf(groupName);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            group = fastestGroup(definition);
        }
        if (!definition.types().contains(group)) {
            return OperationResult.error("任务不在该任务组中");
        }
        if (group == QuestType.IDLE) {
            return OperationResult.error("任务还在闲置组，尚未推送到任何任务组");
        }
        if (!prerequisitesMet(player.server, player.getUUID(), definition)) {
            return OperationResult.error("前置任务尚未完成");
        }
        String key = stateKey(definition.id(), group);
        String period = periodFor(group);
        QuestSavedData.PlayerQuestState state = data.state(player.getUUID(), key, period, definition.revision());
        if (state.claimed()) return OperationResult.error("这个周期的奖励已经领取");
        if (state.progress() < definition.target()) return OperationResult.error("任务尚未完成");

        for (QuestReward reward : definition.rewards()) {
            if (reward.kind() == QuestRewardKind.MONEY && resolveItem(QuestConstants.MONEY_ID) == null) {
                return OperationResult.error("未找到 caf:money，暂时无法发放奖励");
            }
            if (reward.kind() == QuestRewardKind.ITEM && resolveItem(ResourceLocation.tryParse(reward.value())) == null) {
                return OperationResult.error("奖励物品无效：" + reward.value());
            }
        }

        List<String> granted = new ArrayList<>();
        for (QuestReward reward : definition.rewards()) {
            switch (reward.kind()) {
                case MONEY -> {
                    giveItem(player, resolveItem(QuestConstants.MONEY_ID), reward.amount());
                    granted.add("货币 ×" + reward.amount());
                }
                case ITEM -> {
                    giveItem(player, resolveItem(ResourceLocation.tryParse(reward.value())), reward.amount());
                    granted.add(reward.value() + " ×" + reward.amount());
                }
                case COMMAND -> {
                    String command = reward.value().replace("%player%", player.getGameProfile().getName());
                    player.server.getCommands().performPrefixedCommand(player.server.createCommandSourceStack(), command);
                    granted.add("命令已执行");
                }
                case XP -> {
                    player.giveExperiencePoints(reward.amount());
                    granted.add("经验 +" + reward.amount());
                }
            }
        }
        data.update(player.getUUID(), key,
                new QuestSavedData.PlayerQuestState(period, definition.revision(), state.progress(), true, state.found()));
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        String summary = granted.isEmpty() ? "（未配置奖励）" : String.join("、", granted);
        return OperationResult.ok(definition.title() + "：" + summary);
    }

    private static Item resolveItem(ResourceLocation id) {
        if (id == null) return null;
        Item item = ForgeRegistries.ITEMS.getValue(id);
        return item == null || item == net.minecraft.world.item.Items.AIR ? null : item;
    }

    private static void giveItem(ServerPlayer player, Item item, int count) {
        if (item == null || count <= 0) return;
        ItemStack stack = new ItemStack(item, count);
        player.getInventory().add(stack);
        if (stack.getCount() > 0) {
            player.drop(stack, false);
        }
    }

    public static OperationResult saveDefinition(ServerPlayer player, QuestDefinition definition) {
        if (!isAdmin(player)) {
            return OperationResult.error("你没有任务管理权限");
        }
        OperationResult valid = validateDefinition(player.server, definition);
        if (!valid.success()) {
            return valid;
        }
        QuestSavedData data = QuestSavedData.get(player.server);
        boolean saved = data.upsert(definition);
        if (saved) {
            // Persist to config so the quest list travels with the modpack.
            QuestStorage.saveDefinitions(player.server, data.definitions(true));
        }
        return saved ? OperationResult.ok("任务已保存，点“立即推送”发布给玩家")
                : OperationResult.error("任务数量已达到上限或 ID 无效");
    }

    /** Shared rules for the editor, the import command, and preset loading. */
    public static OperationResult validateDefinition(MinecraftServer server, QuestDefinition definition) {
        if (definition.id().isBlank()) {
            return OperationResult.error("任务 ID 不能为空");
        }
        for (String prereqId : definition.prerequisites()) {
            if (prereqId.equals(definition.id())) {
                return OperationResult.error("任务不能以自己作为前置");
            }
        }
        if (definition.goalType() != QuestGoalType.MANUAL) {
            if (definition.goalIds().isEmpty()) {
                return OperationResult.error("请至少选择一个目标");
            }
            for (String goalId : definition.goalIds()) {
                ResourceLocation parsed = ResourceLocation.tryParse(goalId);
                boolean valid = switch (definition.goalType()) {
                    case KILL -> parsed != null && ForgeRegistries.ENTITY_TYPES.containsKey(parsed);
                    case COLLECT -> parsed != null && ForgeRegistries.ITEMS.containsKey(parsed);
                    case BIOME -> parsed != null && server.registryAccess()
                            .registryOrThrow(net.minecraft.core.registries.Registries.BIOME).containsKey(parsed);
                    case STRUCTURE -> parsed != null && server.registryAccess()
                            .registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE).containsKey(parsed);
                    default -> false;
                };
                if (!valid) {
                    return OperationResult.error("目标 ID 无效：" + goalId);
                }
            }
        }
        for (QuestReward reward : definition.rewards()) {
            if (reward.kind() == QuestRewardKind.ITEM && resolveItem(ResourceLocation.tryParse(reward.value())) == null) {
                return OperationResult.error("奖励物品无效：" + reward.value());
            }
            if (reward.kind() == QuestRewardKind.COMMAND && reward.value().isBlank()) {
                return OperationResult.error("命令奖励不能为空");
            }
        }
        return OperationResult.ok("ok");
    }

    public static OperationResult deleteDefinition(ServerPlayer player, String id) {
        if (!isAdmin(player)) {
            return OperationResult.error("你没有任务管理权限");
        }
        QuestSavedData data = QuestSavedData.get(player.server);
        boolean removed = data.remove(id);
        if (removed) {
            QuestStorage.saveDefinitions(player.server, data.definitions(true));
        }
        return removed ? OperationResult.ok("任务已删除，点“立即推送”同步给玩家")
                : OperationResult.error("任务不存在");
    }

    /** Pushes a fresh per-player snapshot to everyone online; returns the player count. */
    public static int broadcastUpdate(MinecraftServer server) {
        int count = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            QuestNetwork.sendSnapshot(player, isAdmin(player));
            player.displayClientMessage(Component.literal("[任务] 任务列表已更新").withStyle(ChatFormatting.GOLD), true);
            count++;
        }
        return count;
    }

    /** Clears one player's progress for a quest (or all quests when questId is "all"). */
    public static OperationResult resetPlayer(ServerPlayer admin, ServerPlayer target, String questId) {
        if (!isAdmin(admin)) {
            return OperationResult.error("你没有任务管理权限");
        }
        QuestSavedData data = QuestSavedData.get(admin.server);
        int reset = 0;
        for (QuestDefinition definition : data.definitions(true)) {
            if (!questId.equals("all") && !definition.id().equals(questId)) {
                continue;
            }
            // Reset every pool instance of the quest (the idle staging copy holds no real progress).
            for (QuestType group : definition.types()) {
                if (group == QuestType.IDLE) {
                    continue;
                }
                data.update(target.getUUID(), stateKey(definition.id(), group),
                        new QuestSavedData.PlayerQuestState(periodFor(group), definition.revision(), 0, false, List.of()));
                reset++;
            }
        }
        if (reset == 0) {
            return OperationResult.error("没有匹配的任务：" + questId);
        }
        QuestNetwork.sendSnapshot(target, isAdmin(target), true);
        return OperationResult.ok("已重置 " + target.getGameProfile().getName() + " 的 " + reset + " 个任务进度");
    }

    private static boolean isAdmin(ServerPlayer player) {
        return player.createCommandSourceStack().hasPermission(QuestConstants.ADMIN_PERMISSION_LEVEL);
    }

    public record OperationResult(boolean success, String message) {
        public static OperationResult ok(String message) { return new OperationResult(true, message); }
        public static OperationResult error(String message) { return new OperationResult(false, message); }
    }
}
