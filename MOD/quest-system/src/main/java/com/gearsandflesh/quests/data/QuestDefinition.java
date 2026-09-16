package com.gearsandflesh.quests.data;

import com.gearsandflesh.quests.QuestConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public record QuestDefinition(
        String id,
        List<QuestType> types,
        String title,
        String description,
        QuestGoalType goalType,
        List<String> goalIds,
        int target,
        List<QuestReward> rewards,
        boolean enabled,
        List<String> prerequisites,
        int revision
) {
    public QuestDefinition {
        id = sanitize(id, QuestConstants.MAX_ID_LENGTH);
        types = normalizeTypes(types);
        title = sanitize(title, QuestConstants.MAX_TEXT_LENGTH);
        description = sanitize(description, QuestConstants.MAX_DESCRIPTION_LENGTH);
        goalType = goalType == null ? QuestGoalType.MANUAL : goalType;
        goalIds = normalizeGoals(goalIds, goalType);
        target = Math.max(1, Math.min(1_000_000, target));
        rewards = rewards == null ? List.of() : List.copyOf(rewards.stream().limit(QuestConstants.MAX_REWARDS).toList());
        prerequisites = normalizePrereqs(prerequisites, id);
        revision = Math.max(0, revision);
    }

    /** A quest may live in several pools at once; at least one group is always kept. */
    private static List<QuestType> normalizeTypes(List<QuestType> list) {
        LinkedHashSet<QuestType> unique = new LinkedHashSet<>();
        if (list != null) {
            list.stream().filter(java.util.Objects::nonNull).forEach(unique::add);
        }
        if (unique.isEmpty()) {
            unique.add(QuestType.DAILY);
        }
        return List.copyOf(unique);
    }

    /** Whether the quest shows up in that pool. */
    public boolean inGroup(QuestType group) {
        return types.contains(group);
    }

    /** First group — used as display fallback and for the progress cadence. */
    public QuestType primaryType() {
        return types.get(0);
    }

    /** Same quest with a different revision, used by the server when gameplay content changes. */
    public QuestDefinition withRevision(int newRevision) {
        return new QuestDefinition(id, types, title, description, goalType, goalIds, target, rewards, enabled, prerequisites, newRevision);
    }

    /** Same quest without one prerequisite id, used when that quest is deleted. */
    public QuestDefinition withoutPrereq(String prereqId) {
        List<String> remaining = prerequisites.stream().filter(id -> !id.equals(prereqId)).toList();
        return new QuestDefinition(id, types, title, description, goalType, goalIds, target, rewards, enabled, remaining, revision);
    }

    /** Whether the fields that player progress depends on differ between the two versions. */
    public boolean gameplayChangedFrom(QuestDefinition other) {
        return other == null
                || !types.equals(other.types)
                || target != other.target
                || goalType != other.goalType
                || !goalIds.equals(other.goalIds)
                || !rewards.equals(other.rewards);
    }

    /** Quests that must be claimed before this one becomes visible; dangling ids count as met. */
    private static List<String> normalizePrereqs(List<String> ids, String selfId) {
        if (ids == null) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String id : ids) {
            String clean = sanitize(id, QuestConstants.MAX_ID_LENGTH);
            if (!clean.isEmpty() && !clean.equals(selfId)) {
                unique.add(clean);
            }
            if (unique.size() >= QuestConstants.MAX_PREREQS) {
                break;
            }
        }
        return List.copyOf(unique);
    }

    /** Killing/collecting any one of the listed ids counts toward the quest. */
    private static List<String> normalizeGoals(List<String> ids, QuestGoalType type) {
        if (ids == null || type == QuestGoalType.MANUAL) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String id : ids) {
            String clean = sanitize(id, QuestConstants.MAX_TEXT_LENGTH);
            if (!clean.isEmpty()) {
                unique.add(clean);
            }
            if (unique.size() >= QuestConstants.MAX_GOALS) {
                break;
            }
        }
        return List.copyOf(unique);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", id);
        tag.putString("Type", primaryType().name());
        ListTag typeTags = new ListTag();
        for (QuestType questType : types) {
            typeTags.add(net.minecraft.nbt.StringTag.valueOf(questType.name()));
        }
        tag.put("Types", typeTags);
        tag.putString("Title", title);
        tag.putString("Description", description);
        tag.putString("GoalType", goalType.name());
        ListTag goals = new ListTag();
        for (String goalId : goalIds) {
            CompoundTag goalTag = new CompoundTag();
            goalTag.putString("Id", goalId);
            goals.add(goalTag);
        }
        tag.put("GoalIds", goals);
        if (!goalIds.isEmpty()) {
            tag.putString("GoalId", goalIds.get(0));
        }
        tag.putInt("Target", target);
        ListTag rewardTags = new ListTag();
        for (QuestReward reward : rewards) {
            rewardTags.add(reward.save());
        }
        tag.put("Rewards", rewardTags);
        int legacyMoney = rewards.stream()
                .filter(reward -> reward.kind() == QuestRewardKind.MONEY)
                .mapToInt(QuestReward::amount).sum();
        tag.putInt("Reward", legacyMoney);
        tag.putBoolean("Enabled", enabled);
        ListTag prereqTags = new ListTag();
        for (String prereqId : prerequisites) {
            prereqTags.add(net.minecraft.nbt.StringTag.valueOf(prereqId));
        }
        tag.put("Prereqs", prereqTags);
        tag.putInt("Revision", revision);
        return tag;
    }

    public static QuestDefinition load(CompoundTag tag) {
        List<QuestType> types = new ArrayList<>();
        ListTag typeTags = tag.getList("Types", Tag.TAG_STRING);
        for (int i = 0; i < typeTags.size(); i++) {
            try {
                types.add(QuestType.valueOf(typeTags.getString(i)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (types.isEmpty()) {
            try {
                types.add(QuestType.valueOf(tag.getString("Type")));
            } catch (IllegalArgumentException ignored) {
            }
        }
        QuestGoalType goalType;
        try {
            goalType = QuestGoalType.valueOf(tag.getString("GoalType"));
        } catch (IllegalArgumentException ignored) {
            goalType = QuestGoalType.MANUAL;
        }
        List<String> goalIds = new ArrayList<>();
        ListTag goals = tag.getList("GoalIds", Tag.TAG_COMPOUND);
        for (int i = 0; i < goals.size(); i++) {
            goalIds.add(goals.getCompound(i).getString("Id"));
        }
        if (goalIds.isEmpty()) {
            String legacy = tag.getString("GoalId");
            if (!legacy.isBlank()) {
                goalIds.add(legacy);
            }
        }
        List<QuestReward> rewards = new ArrayList<>();
        ListTag rewardTags = tag.getList("Rewards", Tag.TAG_COMPOUND);
        for (int i = 0; i < rewardTags.size(); i++) {
            rewards.add(QuestReward.load(rewardTags.getCompound(i)));
        }
        if (rewards.isEmpty()) {
            int legacyReward = tag.getInt("Reward");
            if (legacyReward > 0) {
                rewards.add(new QuestReward(QuestRewardKind.MONEY, "", legacyReward));
            }
        }
        List<String> prerequisites = new ArrayList<>();
        ListTag prereqTags = tag.getList("Prereqs", Tag.TAG_STRING);
        for (int i = 0; i < prereqTags.size(); i++) {
            prerequisites.add(prereqTags.getString(i));
        }
        return new QuestDefinition(
                tag.getString("Id"), types, tag.getString("Title"),
                tag.getString("Description"), goalType, goalIds,
                tag.getInt("Target"), rewards, tag.getBoolean("Enabled"), prerequisites, tag.getInt("Revision")
        );
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeUtf(id, QuestConstants.MAX_ID_LENGTH);
        buffer.writeVarInt(types.size());
        for (QuestType questType : types) {
            buffer.writeEnum(questType);
        }
        buffer.writeUtf(title, QuestConstants.MAX_TEXT_LENGTH);
        buffer.writeUtf(description, QuestConstants.MAX_DESCRIPTION_LENGTH);
        buffer.writeEnum(goalType);
        buffer.writeVarInt(goalIds.size());
        for (String goalId : goalIds) {
            buffer.writeUtf(goalId, QuestConstants.MAX_TEXT_LENGTH);
        }
        buffer.writeVarInt(target);
        buffer.writeVarInt(rewards.size());
        for (QuestReward reward : rewards) {
            reward.write(buffer);
        }
        buffer.writeBoolean(enabled);
        buffer.writeVarInt(prerequisites.size());
        for (String prereqId : prerequisites) {
            buffer.writeUtf(prereqId, QuestConstants.MAX_ID_LENGTH);
        }
        buffer.writeVarInt(revision);
    }

    public static QuestDefinition read(FriendlyByteBuf buffer) {
        String id = buffer.readUtf(QuestConstants.MAX_ID_LENGTH);
        int typeCount = Math.max(1, Math.min(QuestType.values().length, buffer.readVarInt()));
        List<QuestType> types = new ArrayList<>();
        for (int i = 0; i < typeCount; i++) {
            types.add(buffer.readEnum(QuestType.class));
        }
        String title = buffer.readUtf(QuestConstants.MAX_TEXT_LENGTH);
        String description = buffer.readUtf(QuestConstants.MAX_DESCRIPTION_LENGTH);
        QuestGoalType goalType = buffer.readEnum(QuestGoalType.class);
        int goalCount = Math.max(0, buffer.readVarInt());
        List<String> goalIds = new ArrayList<>();
        for (int i = 0; i < goalCount; i++) {
            goalIds.add(buffer.readUtf(QuestConstants.MAX_TEXT_LENGTH));
        }
        int target = buffer.readVarInt();
        int rewardCount = Math.max(0, buffer.readVarInt());
        List<QuestReward> rewards = new ArrayList<>();
        for (int i = 0; i < rewardCount; i++) {
            rewards.add(QuestReward.read(buffer));
        }
        boolean enabled = buffer.readBoolean();
        int prereqCount = Math.max(0, buffer.readVarInt());
        List<String> prerequisites = new ArrayList<>();
        for (int i = 0; i < prereqCount; i++) {
            prerequisites.add(buffer.readUtf(QuestConstants.MAX_ID_LENGTH));
        }
        int revision = buffer.readVarInt();
        return new QuestDefinition(id, types, title, description, goalType, goalIds, target, rewards, enabled, prerequisites, revision);
    }

    private static String sanitize(String value, int max) {
        if (value == null) {
            return "";
        }
        String clean = value.strip().replaceAll("[\\p{Cntrl}]", "");
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
}
