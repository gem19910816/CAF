package com.gearsandflesh.quests.data;

/** One quest as shown to a player: the quest plus which pool instance (daily/weekly/special) it is,
 *  since a quest in several pools keeps an independent progress/claim record per pool. */
public record QuestEntry(QuestDefinition definition, String group, int progress, boolean claimed) {
    public QuestEntry {
        definition = definition == null ? new QuestDefinition("invalid", java.util.List.of(QuestType.SPECIAL),
                "无效任务", "", QuestGoalType.MANUAL, java.util.List.of(), 1, java.util.List.of(), false, java.util.List.of(), 0) : definition;
        group = group == null || group.isBlank() ? definition.primaryType().name() : group;
        progress = Math.max(0, Math.min(definition.target(), progress));
    }

    public boolean complete() {
        return progress >= definition.target();
    }

    /** One row per quest for management lists: prefers the fastest-resetting instance. */
    public static java.util.List<QuestEntry> uniqueByQuest(java.util.List<QuestEntry> entries) {
        java.util.LinkedHashMap<String, QuestEntry> unique = new java.util.LinkedHashMap<>();
        for (QuestEntry entry : entries) {
            unique.merge(entry.definition().id(), entry,
                    (a, b) -> groupRank(a) <= groupRank(b) ? a : b);
        }
        return java.util.List.copyOf(unique.values());
    }

    private static int groupRank(QuestEntry entry) {
        return switch (entry.group()) {
            case "DAILY" -> 0;
            case "WEEKLY" -> 1;
            case "SPECIAL" -> 2;
            default -> 3;
        };
    }
}
