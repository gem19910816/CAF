package com.gearsandflesh.quests;

import net.minecraft.resources.ResourceLocation;

public final class QuestConstants {
    public static final String MOD_ID = "gearsandflesh_quests";
    public static final int ADMIN_PERMISSION_LEVEL = 2;
    public static final int MAX_QUESTS = 128;
    public static final int MAX_ID_LENGTH = 32;
    public static final int MAX_TEXT_LENGTH = 120;
    public static final int MAX_DESCRIPTION_LENGTH = 240;
    public static final int MAX_COMMAND_LENGTH = 200;
    public static final int MAX_GOALS = 128;
    public static final int MAX_REWARDS = 6;
    public static final int MAX_PREREQS = 8;
    public static final ResourceLocation MONEY_ID = new ResourceLocation("caf", "money");

    private QuestConstants() {
    }
}
