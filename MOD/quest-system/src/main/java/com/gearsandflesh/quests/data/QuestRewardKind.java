package com.gearsandflesh.quests.data;

public enum QuestRewardKind {
    MONEY("货币"),
    ITEM("物品"),
    COMMAND("命令"),
    XP("经验");

    private final String label;

    QuestRewardKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
