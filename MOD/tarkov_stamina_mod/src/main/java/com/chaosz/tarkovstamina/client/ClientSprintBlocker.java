package com.chaosz.tarkovstamina.client;

import com.chaosz.tarkovstamina.TarkovStamina;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端侧疾跑封锁器
 * 体力耗尽时释放疾跑键 + 关闭疾跑标记，阻止客户端进入疾跑状态。
 * START 释放键 + 关闭标记（aiStep 前），END 再压一次（兜底双击 W 等路径）。
 */
@Mod.EventBusSubscriber(modid = TarkovStamina.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientSprintBlocker {
    private ClientSprintBlocker() {
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (!(event.player instanceof LocalPlayer lp)) return;
        if (!ClientStaminaState.received() || ClientStaminaState.hidden()
                || ClientStaminaState.stamina() > 0.0F) return;

        if (event.phase == TickEvent.Phase.START) {
            // 释放疾跑键 + 关闭疾跑标记（aiStep 前，阻止 aiStep 重新启动疾跑）
            Minecraft.getInstance().options.keySprint.setDown(false);
            lp.setSprinting(false);
        } else if (event.phase == TickEvent.Phase.END) {
            // 再压一次，兜底双击 W 等不经过 Ctrl 键的路径
            lp.setSprinting(false);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ClientStaminaState.received()
                || ClientStaminaState.hidden() || ClientStaminaState.stamina() > 0.0F) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            // 在输入处理完成后再压一次，避免按住 Ctrl 时下一帧重新进入疾跑。
            minecraft.options.keySprint.setDown(false);
            minecraft.player.setSprinting(false);
        }
    }
}
