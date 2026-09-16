package com.gearsandflesh.quests.data;

public enum QuestGoalType {
    KILL("击杀生物"),
    COLLECT("收集物品"),
    MANUAL("手动推进"),
    BIOME("探索群系"),
    STRUCTURE("探索结构");

    private final String label;

    QuestGoalType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
