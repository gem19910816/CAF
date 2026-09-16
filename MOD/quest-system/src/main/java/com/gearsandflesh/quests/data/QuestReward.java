package com.gearsandflesh.quests.data;

import com.gearsandflesh.quests.QuestConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/** One line of a quest's reward: money, an item stack, a console command, or experience points. */
public record QuestReward(QuestRewardKind kind, String value, int amount) {
    public QuestReward {
        kind = kind == null ? QuestRewardKind.MONEY : kind;
        value = sanitize(value, QuestConstants.MAX_COMMAND_LENGTH);
        if (kind == QuestRewardKind.COMMAND) {
            if (value.startsWith("/")) {
                value = value.substring(1);
            }
            amount = 0;
        } else {
            amount = Math.max(1, Math.min(1_000_000, amount));
            if (kind != QuestRewardKind.ITEM) {
                value = "";
            }
        }
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Kind", kind.name());
        tag.putString("Value", value);
        tag.putInt("Amount", amount);
        return tag;
    }

    public static QuestReward load(CompoundTag tag) {
        QuestRewardKind kind;
        try {
            kind = QuestRewardKind.valueOf(tag.getString("Kind"));
        } catch (IllegalArgumentException ignored) {
            kind = QuestRewardKind.MONEY;
        }
        return new QuestReward(kind, tag.getString("Value"), tag.getInt("Amount"));
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeEnum(kind);
        buffer.writeUtf(value, QuestConstants.MAX_COMMAND_LENGTH);
        buffer.writeVarInt(amount);
    }

    public static QuestReward read(FriendlyByteBuf buffer) {
        return new QuestReward(
                buffer.readEnum(QuestRewardKind.class),
                buffer.readUtf(QuestConstants.MAX_COMMAND_LENGTH),
                buffer.readVarInt()
        );
    }

    public String describe() {
        return switch (kind) {
            case MONEY -> "货币 ×" + amount;
            case ITEM -> value + " ×" + amount;
            case COMMAND -> "命令: " + value;
            case XP -> "经验 +" + amount;
        };
    }

    private static String sanitize(String raw, int max) {
        if (raw == null) {
            return "";
        }
        String clean = raw.strip().replaceAll("[\\p{Cntrl}]", "");
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
}
