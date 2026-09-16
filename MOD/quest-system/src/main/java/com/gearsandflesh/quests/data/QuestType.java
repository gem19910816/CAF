package com.gearsandflesh.quests.data;

public enum QuestType {
    DAILY("日常任务"),
    WEEKLY("周常任务"),
    SPECIAL("特殊任务"),
    IDLE("闲置任务");

    private final String label;

    QuestType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
