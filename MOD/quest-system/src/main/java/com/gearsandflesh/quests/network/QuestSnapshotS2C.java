package com.gearsandflesh.quests.network;

import com.gearsandflesh.quests.QuestService;
import com.gearsandflesh.quests.data.QuestDefinition;
import com.gearsandflesh.quests.data.QuestEntry;
import com.gearsandflesh.quests.data.QuestType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record QuestSnapshotS2C(boolean adminMode, List<QuestEntry> entries) {
    public QuestSnapshotS2C {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static QuestSnapshotS2C from(net.minecraft.server.level.ServerPlayer player, boolean adminMode) {
        return new QuestSnapshotS2C(adminMode, QuestService.snapshot(player, adminMode));
    }

    public static void encode(QuestSnapshotS2C message, FriendlyByteBuf buffer) {
        buffer.writeBoolean(message.adminMode);
        buffer.writeVarInt(message.entries.size());
        for (QuestEntry entry : message.entries) {
            entry.definition().write(buffer);
            buffer.writeUtf(entry.group(), 16);
            buffer.writeVarInt(entry.progress());
            buffer.writeBoolean(entry.claimed());
        }
    }

    public static QuestSnapshotS2C decode(FriendlyByteBuf buffer) {
        boolean adminMode = buffer.readBoolean();
        int count = Math.max(0, Math.min(256, buffer.readVarInt()));
        List<QuestEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new QuestEntry(QuestDefinition.read(buffer), buffer.readUtf(16), buffer.readVarInt(), buffer.readBoolean()));
        }
        return new QuestSnapshotS2C(adminMode, entries);
    }

    public static void handle(QuestSnapshotS2C message, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.gearsandflesh.quests.client.ClientQuestState.accept(message)));
        context.setPacketHandled(true);
    }
}
