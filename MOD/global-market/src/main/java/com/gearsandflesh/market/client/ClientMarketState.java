package com.gearsandflesh.market.client;

import com.gearsandflesh.market.data.MarketQuery;
import com.gearsandflesh.market.network.MarketNetwork;
import com.gearsandflesh.market.network.MarketNoticeS2C;
import com.gearsandflesh.market.network.MarketQueryC2S;
import com.gearsandflesh.market.network.MarketSnapshotS2C;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Client-owned cache and entry point used by both network packets and integrations. */
public final class ClientMarketState {
    private static MarketSnapshotS2C snapshot;
    private static MarketNoticeS2C lastNotice;

    private ClientMarketState() {
    }

    public static void accept(MarketSnapshotS2C message) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            snapshot = message;
            if (minecraft.screen instanceof MarketScreen screen) {
                screen.applySnapshot(message);
            }
        });
    }

    public static void onNotice(MarketNoticeS2C message) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            lastNotice = message;
            if (minecraft.screen instanceof MarketScreen screen) {
                screen.showNotice(message);
            }
            if (minecraft.player != null && !message.message().isBlank()) {
                minecraft.player.displayClientMessage(
                        Component.literal("[全球市场] " + message.message())
                                .withStyle(message.success() ? ChatFormatting.GREEN : ChatFormatting.RED),
                        false
                );
            }
        });
    }

    public static void open() {
        open(false);
    }

    public static void open(boolean adminMode) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.player == null || minecraft.getConnection() == null) {
                return;
            }
            MarketScreen screen = new MarketScreen(adminMode);
            minecraft.setScreen(screen);
            screen.requestInitialSnapshot();
        });
    }

    static MarketSnapshotS2C snapshot() {
        return snapshot;
    }

    static MarketNoticeS2C lastNotice() {
        return lastNotice;
    }

    static void query(MarketQuery query) {
        if (Minecraft.getInstance().getConnection() != null) {
            MarketNetwork.CHANNEL.sendToServer(new MarketQueryC2S(query));
        }
    }
}
