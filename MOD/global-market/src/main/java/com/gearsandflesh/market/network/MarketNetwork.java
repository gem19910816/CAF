package com.gearsandflesh.market.network;

import com.gearsandflesh.market.MarketConstants;
import com.gearsandflesh.market.data.MarketQuery;
import com.gearsandflesh.market.service.MarketService;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MarketNetwork {
    private static final String PROTOCOL = "4";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MarketConstants.MOD_ID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private static final Map<UUID, MarketQuery> CURRENT_QUERIES = new ConcurrentHashMap<>();
    private static boolean registered;

    private MarketNetwork() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        int id = 0;
        CHANNEL.registerMessage(id++, MarketQueryC2S.class,
                MarketQueryC2S::encode, MarketQueryC2S::decode, MarketQueryC2S::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, CreateListingC2S.class,
                CreateListingC2S::encode, CreateListingC2S::decode, CreateListingC2S::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, BuyListingC2S.class,
                BuyListingC2S::encode, BuyListingC2S::decode, BuyListingC2S::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, CancelListingC2S.class,
                CancelListingC2S::encode, CancelListingC2S::decode, CancelListingC2S::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, ClaimMailboxC2S.class,
                ClaimMailboxC2S::encode, ClaimMailboxC2S::decode, ClaimMailboxC2S::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, AdminListingActionC2S.class,
                AdminListingActionC2S::encode, AdminListingActionC2S::decode,
                AdminListingActionC2S::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, MarketSnapshotS2C.class,
                MarketSnapshotS2C::encode, MarketSnapshotS2C::decode, MarketSnapshotS2C::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, MarketNoticeS2C.class,
                MarketNoticeS2C::encode, MarketNoticeS2C::decode, MarketNoticeS2C::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id, OpenMarketS2C.class,
                OpenMarketS2C::encode, OpenMarketS2C::decode, OpenMarketS2C::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static MarketQuery currentQuery(ServerPlayer player) {
        return CURRENT_QUERIES.getOrDefault(player.getUUID(), MarketQuery.defaults());
    }

    public static void rememberQuery(ServerPlayer player, MarketQuery query) {
        CURRENT_QUERIES.put(player.getUUID(), query);
    }

    public static void forgetPlayer(UUID playerId) {
        CURRENT_QUERIES.remove(playerId);
    }

    public static void sendSnapshot(ServerPlayer player, MarketQuery query) {
        MarketSnapshotS2C snapshot = MarketService.snapshot(player, query);
        CURRENT_QUERIES.put(player.getUUID(), snapshot.query());
        CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                snapshot
        );
    }

    public static void refresh(ServerPlayer player) {
        sendSnapshot(player, currentQuery(player));
    }

    public static void sendNotice(ServerPlayer player, boolean success, String message) {
        CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new MarketNoticeS2C(success, message)
        );
    }

    public static void open(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenMarketS2C(false));
    }

    public static void openAdmin(ServerPlayer player) {
        if (!player.createCommandSourceStack().hasPermission(
                MarketConstants.ADMIN_PERMISSION_LEVEL)) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenMarketS2C(true));
    }

    static void finishOperation(ServerPlayer player, MarketService.OperationResult result) {
        sendNotice(player, result.success(), result.message());
        refresh(player);
    }
}
