package com.chaosz.nofeetplace;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 禁止腾空时放置方块。
 *
 * <p>判定只看玩家状态，不看落点 —— 塔搭的第一步就是"跳起来点脚下"，
 * 把整个交互掐掉就够了，不需要算方块会落到哪一格的哪个面。
 *
 * <p>事件双端都会触发，而且取消之后客户端直接 return、连包都不发
 * （见 {@code MultiPlayerGameMode.useItemOn}），所以这一次交互等于完全没发生过：
 * 没音效、不挥手、不消耗物品。服务端也会独立跑同一份判定，改客户端绕不过去。
 */
public final class PlacementGuard {

    private PlacementGuard() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();

        // 创造模式完全不受限，跟原版一样随便放
        if (player.isCreative()) {
            return;
        }

        if (isSupported(player)) {
            return;
        }

        // 只拦方块放置。少了这一条，腾空时连箱子都打不开、按钮也按不了。
        if (!(player.getItemInHand(event.getHand()).getItem() instanceof BlockItem)) {
            return;
        }

        event.setCanceled(true);
        Feedback.notifyBlocked(player);
    }

    /** 站在地上，或者在水里 / 梯子上 / 载具里 / 岩浆里 —— 都不算腾空。 */
    private static boolean isSupported(Player player) {
        return player.onGround()
                || player.isInWater()
                || player.onClimbable()
                || player.isPassenger()
                || player.isInLava();
    }
}
