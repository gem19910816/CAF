package com.gearsandflesh.quests;

import com.gearsandflesh.quests.network.QuestNetwork;
import net.minecraftforge.fml.common.Mod;

@Mod(QuestConstants.MOD_ID)
public final class QuestMod {
    public QuestMod() {
        QuestNetwork.register();
    }
}
