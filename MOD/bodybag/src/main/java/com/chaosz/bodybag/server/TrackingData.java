package com.chaosz.bodybag.server;

import com.chaosz.bodybag.BodyBagConfig;
import com.chaosz.bodybag.item.BodyBagItem;
import de.maxhenkel.corpse.entities.CorpseEntity;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端收取任务管理。
 * 玩家点击一次裹尸袋后，这里接管 10 秒计时，每秒发送倒计时，
 * 并检测距离/尸体是否存在；到点后把尸体（含物品）打包进袋子。
 */
public class TrackingData {

    private static final Map<UUID, Task> TASKS = new HashMap<>();

    public static void startCollection(ServerPlayer player, CorpseEntity corpse) {
        TASKS.put(player.getUUID(), new Task(corpse.getId(), BodyBagConfig.COLLECT_DURATION_TICKS.get()));
        player.displayClientMessage(
                Component.translatable("message.bodybag.start", corpseName(corpse)), true);
    }

    public static boolean isCollecting(ServerPlayer player) {
        return TASKS.containsKey(player.getUUID());
    }

    public static void cancel(ServerPlayer player) {
        TASKS.remove(player.getUUID());
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!(event.player instanceof ServerPlayer player)) {
            return;
        }
        Task task = TASKS.get(player.getUUID());
        if (task == null) {
            return;
        }

        // 重新解析目标尸体
        Entity entity = ((ServerLevel) player.level()).getEntity(task.entityId);
        if (!(entity instanceof CorpseEntity corpse) || !corpse.isAlive() || corpse.isRemoved()) {
            cancel(player, "message.bodybag.lost_target");
            return;
        }

        // 离开范围 → 停止
        double range = BodyBagConfig.COLLECT_RANGE.get();
        if (player.distanceToSqr(corpse.getX(), corpse.getY(), corpse.getZ()) > range * range) {
            cancel(player, "message.bodybag.cancelled");
            return;
        }

        task.remaining--;
        int seconds = (int) Math.ceil(task.remaining / 20.0);
        if (seconds != task.lastSecond) {
            task.lastSecond = seconds;
            if (seconds > 0) {
                player.displayClientMessage(
                        Component.translatable("message.bodybag.progress", seconds), true);
            }
        }

        if (task.remaining <= 0) {
            TASKS.remove(player.getUUID());
            BodyBagItem.completeCollection(player, corpse);
        }
    }

    private static void cancel(ServerPlayer player, String messageKey) {
        TASKS.remove(player.getUUID());
        player.displayClientMessage(Component.translatable(messageKey), true);
    }

    private static String corpseName(CorpseEntity corpse) {
        String name = corpse.getCorpseName();
        return name != null && !name.isEmpty() ? name : "?";
    }

    private static class Task {
        final int entityId;
        int remaining;
        int lastSecond = -1;

        Task(int entityId, int remaining) {
            this.entityId = entityId;
            this.remaining = remaining;
        }
    }
}