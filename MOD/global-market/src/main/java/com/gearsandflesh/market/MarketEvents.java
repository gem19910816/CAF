package com.gearsandflesh.market;

import com.gearsandflesh.market.data.MarketSavedData;
import com.gearsandflesh.market.network.MarketNetwork;
import com.gearsandflesh.market.service.MarketService;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MarketConstants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MarketEvents {
    private static int cleanupTicks;
    private static int deliveryTicks;

    private MarketEvents() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("market")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    MarketNetwork.open(player);
                    return 1;
                })
                .then(Commands.literal("edit")
                        .requires(source -> source.hasPermission(
                                MarketConstants.ADMIN_PERMISSION_LEVEL))
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            MarketNetwork.openAdmin(player);
                            return 1;
                        })));
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        cleanupTicks = 0;
        deliveryTicks = 0;
        MarketSavedData.get(event.getServer());
        if (MarketService.isMoneyAvailable()) {
            GlobalMarketMod.LOGGER.info("Global Market currency is {}", MarketConstants.MONEY_ID);
        } else {
            GlobalMarketMod.LOGGER.error(
                    "Global Market currency {} is missing; all trades will be rejected",
                    MarketConstants.MONEY_ID
            );
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++deliveryTicks >= 20) {
            deliveryTicks = 0;
            MarketService.processDeliveries(event.getServer());
        }
        if (++cleanupTicks >= 20 * 60) {
            cleanupTicks = 0;
            int expired = MarketService.expireListings(event.getServer());
            if (expired > 0) {
                GlobalMarketMod.LOGGER.info("Expired {} Global Market listings", expired);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MarketNetwork.forgetPlayer(player.getUUID());
        MarketSavedData data = MarketSavedData.get(player.server);
        data.releaseDueDeliveries(System.currentTimeMillis());
        long money = data.pendingMoney(player.getUUID());
        int items = data.pendingItemCount(player.getUUID());
        long transitMoney = data.inTransitMoney(player.getUUID());
        int transitItems = data.inTransitItemCount(player.getUUID());
        if (money > 0L || items > 0 || transitMoney > 0L || transitItems > 0) {
            MarketNetwork.sendNotice(
                    player,
                    true,
                    "全球市场：可领取 " + items + " 件物品、" + money
                            + " 枚货币；运输中 " + transitItems + " 件物品、"
                            + transitMoney + " 枚货币"
            );
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        MarketNetwork.forgetPlayer(event.getEntity().getUUID());
    }
}
