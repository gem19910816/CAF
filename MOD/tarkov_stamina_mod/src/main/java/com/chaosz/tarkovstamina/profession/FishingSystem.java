package com.chaosz.tarkovstamina.profession;

import com.chaosz.tarkovstamina.StaminaSystem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 钓鱼升级系统
 * <p>
 * 复刻 KJS 钓鱼升级.js：
 * - 右键鱼竿抛竿 → fishExp++（带5秒冷却）
 * - 升级所需：200次升2级，500次升3级
 * - 手持鱼竿时给幸运效果
 * - 2级咬钩等待5秒，3级咬钩等待1秒
 * </p>
 */
public final class FishingSystem {
    private static final String K_FISH_LEVEL = "fishLevel";
    private static final String K_FISH_EXP = "fishExp";
    private static final String K_LAST_FISH_TIME = "lastFishTime";

    private FishingSystem() {
    }

    // ═══════════════════════════════════════════════════════════════
    //  抛竿检测与经验
    // ═══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onUseRod(PlayerInteractEvent.RightClickItem event) {
        if (event.getSide().isClient()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        var stack = event.getItemStack();
        if (stack.isEmpty() || !stack.getItem().getDescriptionId().contains("fishing_rod")) return;

        CompoundTag data = player.getPersistentData();
        int level = data.getInt(K_FISH_LEVEL);
        int exp = data.getInt(K_FISH_EXP);
        if (data.contains(K_FISH_LEVEL) && level >= ProfessionConfig.FISH_MAX) return;
        if (level == 0) data.putInt(K_FISH_LEVEL, 1); // 初始化

        long now = player.level().getGameTime();
        if (data.contains(K_LAST_FISH_TIME)
                && (now - data.getLong(K_LAST_FISH_TIME)) < ProfessionConfig.FISH_CD) return;

        data.putLong(K_LAST_FISH_TIME, now);
        data.putInt(K_FISH_EXP, exp + 1);
        exp = data.getInt(K_FISH_EXP);

        int req = ProfessionConfig.FISH_EXP_CONFIG[data.getInt(K_FISH_LEVEL) - 1];
        if (exp >= req) {
            int newLevel = data.getInt(K_FISH_LEVEL) + 1;
            data.putInt(K_FISH_LEVEL, newLevel);
            data.putInt(K_FISH_EXP, 0);
            player.displayClientMessage(
                    Component.literal("§a技能提升: ").append(
                            Component.literal("§f钓鱼 LV." + newLevel)), false);
            player.displayClientMessage(
                    Component.literal("§a▲ 技能等级提升: 钓鱼 (" + newLevel + "/" + ProfessionConfig.FISH_MAX + ")"), false);
            player.playSound(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 2.0F, 1.0F);
        } else {
            player.displayClientMessage(Component.literal(
                    "§7钓鱼熟练度增加... (" + exp + "/" + req + ")"), false);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  动态 BUFF：手持鱼竿时给幸运
    // ═══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide()) return;
        if (event.player.tickCount % 20 != 0) return;

        ServerPlayer player = (ServerPlayer) event.player;
        int level = player.getPersistentData().getInt(K_FISH_LEVEL);
        if (level < 2) return;

        if (!player.getMainHandItem().isEmpty()
                && player.getMainHandItem().getItem().getDescriptionId().contains("fishing_rod")) {
            int amp = level - 2;
            player.addEffect(new MobEffectInstance(
                    MobEffects.LUCK, 40, amp, false, false, true));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  鱼钩秒咬钩逻辑
    // ═══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onBobberSpawn(EntityEvent.EntityConstructing event) {
        Entity entity = event.getEntity();
        if (entity.level().isClientSide()) return;
        if (!entity.getType().getDescriptionId().contains("fishing_bobber")) return;

        // 通过实体 NBT 调整 WaitTime（等价于 KJS data merge entity）
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        if (entity.save(tag)) {
            // 找到鱼竿所有者
            // 简化：通过最近持有鱼竿的玩家判断
            var owner = entity.level().getNearestPlayer(entity, 10.0);
            if (owner instanceof ServerPlayer sp) {
                int fLevel = sp.getPersistentData().getInt(K_FISH_LEVEL);
                if (fLevel == 2) {
                    tag.putInt("WaitTime", ProfessionConfig.WAIT_LV2);
                } else if (fLevel >= 3) {
                    tag.putInt("WaitTime", ProfessionConfig.WAIT_LV3);
                }
                entity.load(tag);
            }
        }
    }
}