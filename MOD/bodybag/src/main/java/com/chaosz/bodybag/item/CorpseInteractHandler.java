package com.chaosz.bodybag.item;

import com.chaosz.bodybag.BodyBagMod;
import de.maxhenkel.corpse.entities.CorpseEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 手持空裹尸袋右键尸体时，阻止打开尸体背包界面，
 * 改为触发裹尸袋的正常使用流程（收取尸体）。
 */
@Mod.EventBusSubscriber(modid = BodyBagMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class CorpseInteractHandler {

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof BodyBagItem) || BodyBagItem.isFilled(stack)) {
            return;
        }
        if (!(event.getTarget() instanceof CorpseEntity)) {
            return;
        }

        // 取消事件，防止尸体 GUI 打开
        event.setCanceled(true);

        Player player = event.getEntity();
        if (player.level().isClientSide) {
            // 延后一帧调用 useItem，避免在此事件处理中重入造成卡键
            Minecraft.getInstance().tell(() -> {
                if (Minecraft.getInstance().gameMode != null) {
                    Minecraft.getInstance().gameMode.useItem(player, event.getHand());
                }
            });
        }
        // 服务端仅仅取消事件（尸体 GUI 不开），
        // 客户端延后发出的 use-item 数据包会触发服务器执行 Item.use() 启动收取。
    }
}