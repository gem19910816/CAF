package com.gearsandflesh.quests.client;

import com.gearsandflesh.quests.data.QuestDefinition;
import com.gearsandflesh.quests.data.QuestEntry;
import com.gearsandflesh.quests.network.QuestNetwork;
import com.gearsandflesh.quests.network.QuestQueryC2S;
import com.gearsandflesh.quests.network.QuestSnapshotS2C;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public final class ClientQuestState {
    private static final int GOLD = 0xFFF4CE59;
    private static final int TEXT = 0xFFE8F1F4;
    private static final int GREEN = 0xFF63D8A3;
    private static final int ACCENT = 0xFF238EB2;
    private static QuestSnapshotS2C snapshot;
    private static String trackedId;

    private ClientQuestState() {
    }

    public static void open() { open(false); }

    public static void open(boolean adminMode) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.player == null || minecraft.getConnection() == null) return;
            QuestScreen screen = new QuestScreen(adminMode);
            minecraft.setScreen(screen);
            request(adminMode);
        });
    }

    public static void request(boolean adminMode) {
        if (Minecraft.getInstance().getConnection() != null) {
            QuestNetwork.CHANNEL.sendToServer(new QuestQueryC2S(adminMode));
        }
    }

    public static void accept(QuestSnapshotS2C message) {
        snapshot = message;
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.screen instanceof QuestScreen screen) screen.apply(message);
            else if (minecraft.screen instanceof QuestPoolScreen pool) pool.apply(message);
        });
    }

    static QuestSnapshotS2C snapshot() { return snapshot; }

    /** The one quest the player pins to the HUD, or null. */
    public static String trackedId() {
        return trackedId;
    }

    public static void toggleTracked(String questId) {
        trackedId = questId.equals(trackedId) ? null : questId;
    }

    /** HUD chip for the tracked quest; drawn every frame by the registered overlay. */
    public static void renderTracker(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (trackedId == null || minecraft.player == null || minecraft.options.hideGui) {
            return;
        }
        if (snapshot == null) {
            return;
        }
        QuestEntry entry = null;
        for (QuestEntry candidate : snapshot.entries()) {
            if (candidate.definition().id().equals(trackedId)) {
                entry = candidate;
                break;
            }
        }
        if (entry == null || entry.claimed()) {
            return;
        }
        QuestDefinition definition = entry.definition();
        int x = 6;
        int y = 6;
        graphics.fill(x - 3, y - 3, x + 126, y + 31, 0x88000000);
        graphics.drawString(minecraft.font, "★ " + clip(definition.title(), 108), x, y, GOLD);
        int barWidth = 120;
        int barHeight = 3;
        graphics.fill(x, y + 14, x + barWidth, y + 14 + barHeight, 0xFF22303A);
        int filled = (int) (barWidth * Math.min(1f, entry.progress() / (float) definition.target()));
        if (filled > 0) {
            graphics.fill(x, y + 14, x + filled, y + 14 + barHeight, entry.complete() ? GREEN : ACCENT);
        }
        graphics.drawString(minecraft.font, entry.progress() + "/" + definition.target(), x, y + 20, TEXT);
    }

    private static String clip(String text, int maxWidth) {
        var font = Minecraft.getInstance().font;
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, maxWidth - 3) + "...";
    }
}
