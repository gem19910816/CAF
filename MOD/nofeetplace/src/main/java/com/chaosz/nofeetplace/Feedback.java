package com.chaosz.nofeetplace;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 拦截提示 + 每玩家冷却，避免长按右键刷屏。 */
public final class Feedback {

    private static final Map<UUID, Long> LAST_SHOWN = new HashMap<>();

    private Feedback() {
    }

    public static void notifyBlocked(Player player) {
        if (!NoFeetPlaceConfig.showMessage()) {
            return;
        }

        long now = player.level().getGameTime();
        Long last = LAST_SHOWN.get(player.getUUID());
        if (last != null && now - last < NoFeetPlaceConfig.messageCooldownTicks()) {
            return;
        }
        LAST_SHOWN.put(player.getUUID(), now);

        player.displayClientMessage(Component.translatable("message.nofeetplace.blocked"), true);
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SHOWN.remove(event.getEntity().getUUID());
    }
}
