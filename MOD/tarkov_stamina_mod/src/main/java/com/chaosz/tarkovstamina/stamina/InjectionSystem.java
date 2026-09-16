package com.chaosz.tarkovstamina.stamina;

import com.chaosz.tarkovstamina.StaminaConfig;
import com.chaosz.tarkovstamina.StaminaSystem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 基因强化系统
 * <p>
 * 右键使用特殊强化针剂，永久提升体力上限。
 * 每次注射 +INJECTION_BONUS_PERCENT% 上限，最多 MAX_INJECTIONS 次。
 * 物品：caf:special_strength_injection
 * </p>
 */
public final class InjectionSystem {
    private static final String K_INJECTION_COUNT = "injectionCount";
    private static final String ITEM_ID = "caf:special_strength_injection";

    private InjectionSystem() {
    }

    @SubscribeEvent
    public static void onUseItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getSide().isClient()) return;
        if (event.getItemStack().getItem().getDescriptionId().contains("special_strength_injection")
                || itemId(event).equals(ITEM_ID)) {
            ServerPlayer player = (ServerPlayer) event.getEntity();
            CompoundTag state = StaminaSystem.state(player, true);
            int count = state.getInt(K_INJECTION_COUNT);
            int max = StaminaConfig.MAX_INJECTIONS.get();

            if (count < max) {
                count++;
                state.putInt(K_INJECTION_COUNT, count);
                // 消耗物品
                event.getItemStack().shrink(1);

                float newMax = StaminaSystem.maximumFor(player, state);
                state.putFloat("stamina", newMax);

                int bonusPercent = (int) Math.round(count * StaminaConfig.INJECTION_BONUS_PERCENT.get());
                player.displayClientMessage(
                        Component.literal("§a✔ 基因强化成功！")
                                .append(Component.literal(
                                        " §f当前上限: +" + bonusPercent + "% | 代谢能力略微提升")),
                        false);
                player.playSound(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 2.0F, 1.0F);
                event.setCanceled(true);
            } else {
                player.displayClientMessage(
                        Component.literal("§c你的身体已达到进化极限，无法继续强化。"),
                        false);
            }
        }
    }

    private static String itemId(PlayerInteractEvent.RightClickItem event) {
        return event.getItemStack().getItem().builtInRegistryHolder()
                .key().location().toString();
    }
}