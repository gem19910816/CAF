package com.gearsandflesh.quests.network;

import com.gearsandflesh.quests.QuestConstants;
import com.gearsandflesh.quests.QuestService;
import com.gearsandflesh.quests.data.QuestDefinition;
import com.gearsandflesh.quests.data.QuestSavedData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class QuestNetwork {
    private static final String PROTOCOL = "9";
    private static final long SNAPSHOT_THROTTLE_MS = 1000;
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(QuestConstants.MOD_ID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static boolean registered;
    private static final Map<UUID, Long> LAST_SNAPSHOT = new HashMap<>();

    private QuestNetwork() {
    }

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        int id = 0;
        CHANNEL.registerMessage(id++, OpenQuestS2C.class, OpenQuestS2C::encode, OpenQuestS2C::decode, OpenQuestS2C::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, QuestQueryC2S.class, QuestQueryC2S::encode, QuestQueryC2S::decode, QuestQueryC2S::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, QuestSnapshotS2C.class, QuestSnapshotS2C::encode, QuestSnapshotS2C::decode, QuestSnapshotS2C::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, ClaimQuestC2S.class, ClaimQuestC2S::encode, ClaimQuestC2S::decode, ClaimQuestC2S::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, SaveQuestC2S.class, SaveQuestC2S::encode, SaveQuestC2S::decode, SaveQuestC2S::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, DeleteQuestC2S.class, DeleteQuestC2S::encode, DeleteQuestC2S::decode, DeleteQuestC2S::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, PublishQuestsC2S.class, PublishQuestsC2S::encode, PublishQuestsC2S::decode, PublishQuestsC2S::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, ClaimAllC2S.class, ClaimAllC2S::encode, ClaimAllC2S::decode, ClaimAllC2S::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    public static void sendSnapshot(ServerPlayer player, boolean adminMode) {
        sendSnapshot(player, adminMode, true);
    }

    /** force=false lets routine progress updates get throttled to one snapshot per second. */
    public static void sendSnapshot(ServerPlayer player, boolean adminMode, boolean force) {
        long now = System.currentTimeMillis();
        if (!force) {
            Long last = LAST_SNAPSHOT.get(player.getUUID());
            if (last != null && now - last < SNAPSHOT_THROTTLE_MS) {
                return;
            }
        }
        LAST_SNAPSHOT.put(player.getUUID(), now);
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                QuestSnapshotS2C.from(player, adminMode));
    }

    public static void clearPlayer(UUID playerId) {
        LAST_SNAPSHOT.remove(playerId);
    }

    public static void sendNotice(ServerPlayer player, QuestService.OperationResult result) {
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[任务] " + result.message()));
        sendSnapshot(player, player.createCommandSourceStack().hasPermission(QuestConstants.ADMIN_PERMISSION_LEVEL));
    }

    static void handleClaim(ServerPlayer player, String id, String group) {
        sendNotice(player, QuestService.claim(player, id, group));
    }

    static void handleClaimAll(ServerPlayer player) {
        sendNotice(player, QuestService.claimAll(player));
    }

    static void handleSave(ServerPlayer player, QuestDefinition definition) {
        sendNotice(player, QuestService.saveDefinition(player, definition));
    }

    static void handleDelete(ServerPlayer player, String id) {
        sendNotice(player, QuestService.deleteDefinition(player, id));
    }

    static void handlePublish(ServerPlayer player) {
        if (!player.createCommandSourceStack().hasPermission(com.gearsandflesh.quests.QuestConstants.ADMIN_PERMISSION_LEVEL)) {
            sendNotice(player, QuestService.OperationResult.error("你没有任务管理权限"));
            return;
        }
        int count = QuestService.broadcastUpdate(player.server);
        sendNotice(player, QuestService.OperationResult.ok("已向 " + count + " 名在线玩家推送最新任务"));
    }
}
