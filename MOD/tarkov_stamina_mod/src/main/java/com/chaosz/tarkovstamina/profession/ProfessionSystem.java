package com.chaosz.tarkovstamina.profession;

import com.chaosz.tarkovstamina.StaminaSystem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Random;

/**
 * 职业系统：木工 / 石工 / 技工
 * <p>
 * - 木工：手持斧头破块 → woodcutCount++ → 手持斧头时给急迫(受惩罚)
 * - 石工：手持镐子破块 → stonecutCount++ → 手持镐子时给急迫(受惩罚)
 * - 技工：手持螺丝刀破块 → mechanicCount++ → 自定义掉落 + 手持螺丝刀时给急迫
 * </p>
 */
public final class ProfessionSystem {
    private static final String K_WOODCUT = "woodcutCount";
    private static final String K_STONECUT = "stonecutCount";
    private static final String K_MECHANIC = "mechanicCount";

    private static final ResourceLocation SCREWDRIVER_RL = ResourceLocation.tryBuild(
            "survival_instinct", "screwdriver_rapier");

    private static final Random RANDOM = new Random();

    private ProfessionSystem() {
    }

    // ═══════════════════════════════════════════════════════════════
    //  破块计数
    // ═══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;

        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) return;

        String heldId = ForgeRegistries.ITEMS.getKey(held.getItem()).toString();
        String blockId = ForgeRegistries.BLOCKS.getKey(event.getState().getBlock()).toString();

        // ── 木工：手持斧头 ──
        if (held.is(net.minecraft.tags.ItemTags.AXES)) {
            incrementCount(player, K_WOODCUT, "[木工]", ProfessionConfig.WOODCUTTER_STAGES);
            return;
        }

        // ── 石工：手持镐子 ──
        if (held.is(net.minecraft.tags.ItemTags.PICKAXES)) {
            incrementCount(player, K_STONECUT, "[石工]", ProfessionConfig.STONECUTTER_STAGES);
            return;
        }

        // ── 技工：手持螺丝刀 ──
        if (heldId.equals(ProfessionConfig.SCREWDRIVER_ID)) {
            String dropBlockId = blockId;
            List<String[]> drops = ProfessionConfig.getMechanicDrops().get(dropBlockId);
            boolean isVehicle = ProfessionConfig.VEHICLE_BLOCKS.contains(dropBlockId);
            if (drops == null && isVehicle) {
                drops = ProfessionConfig.VEHICLE_ITEMS;
            }

            // 潜行左键 + 不是末日装饰 → 精准采集
            if (player.isCrouching() && !dropBlockId.startsWith("doomsday_decoration:")) {
                event.setCanceled(true);
                player.level().destroyBlock(event.getPos(), false);
                player.level().addFreshEntity(new ItemEntity(player.level(),
                        event.getPos().getX() + 0.5, event.getPos().getY() + 0.5,
                        event.getPos().getZ() + 0.5,
                        new ItemStack(event.getState().getBlock().asItem())));
                return;
            }

            // 只有配置了掉落表的方块才处理（否则放行原版掉落）
            if (drops == null) return;

            // 取消原版掉落，改为自定义掉落
            event.setCanceled(true);
            player.level().destroyBlock(event.getPos(), false);

            incrementCount(player, K_MECHANIC, "[技工]", ProfessionConfig.MECHANIC_STAGES);

            // 技工自定义掉落
            int level = calcLevel(player.getPersistentData().getInt(K_MECHANIC),
                    ProfessionConfig.MECHANIC_STAGES);
            if (level > 0) {
                double multiplier = level / 3.0;
                for (String[] entry : drops) {
                    double chance = Double.parseDouble(entry[2]) * multiplier;
                    if (RANDOM.nextDouble() < chance) {
                        int count = (int) Math.ceil(Integer.parseInt(entry[1]) * multiplier);
                        count = Math.max(1, count);
                        Item item = ForgeRegistries.ITEMS.getValue(
                                ResourceLocation.tryParse(entry[0]));
                        if (item != null) {
                            player.level().addFreshEntity(new ItemEntity(player.level(),
                                    event.getPos().getX() + 0.5, event.getPos().getY() + 0.5,
                                    event.getPos().getZ() + 0.5, new ItemStack(item, count)));
                        }
                    }
                }
            }
        }
    }

    private static void incrementCount(ServerPlayer player, String key, String prefix,
                                        int[][] stages) {
        CompoundTag data = player.getPersistentData();
        int count = data.getInt(key) + 1;
        data.putInt(key, count);
        // 镜像到顶层键（KJS 技能终端兼容）
        data.putInt(key, count);

        for (int[] stage : stages) {
            if (count == stage[0]) {
                String[] texts = {
                        "[木工] 你的斧法开始有了章法。",
                        "[木工] 你熟悉了不同木材的纹理走向。",
                        "[木工] 你的劈砍如同庖丁解牛般精准。",
                        "[石工] 你对岩石的硬度有了新的认识。",
                        "[石工] 你的采掘节奏越发稳健。",
                        "[石工] 你仿佛能听到岩石内部的裂纹声。",
                        "[技工] 你开始熟悉螺丝和齿轮的拆解了。",
                        "[技工] 你的双手对精密结构有了感觉。",
                        "[技工] 没有什么机械是你拆不掉的了。"
                };
                int idx = prefix.equals("[木工]") ? stage[1]
                        : prefix.equals("[石工]") ? stage[1] + 3
                        : stage[1] + 6;
                if (idx >= 0 && idx < texts.length) {
                    player.displayClientMessage(
                            Component.literal("§7" + texts[idx]), false);
                    player.playSound(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 2.0F, 1.0F);
                }
                break;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  等级效果（每 20 tick 刷新急迫）
    // ═══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide()) return;
        if (event.player.tickCount % 20 != 0) return;

        ServerPlayer player = (ServerPlayer) event.player;
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) return;

        // 从 MOD 状态读取惩罚
        CompoundTag modState = StaminaSystem.state(player, true);
        int penalty = 0;
        if (modState.getBoolean("isSick")) penalty++;
        if (modState.getInt("depressionLevel") >= 50) penalty++;
        if (modState.getBoolean("isOverweight")) penalty++;

        // 木工：手持斧头
        if (held.is(net.minecraft.tags.ItemTags.AXES)) {
            int level = calcLevel(player.getPersistentData().getInt(K_WOODCUT),
                    ProfessionConfig.WOODCUTTER_STAGES);
            if (level >= 0) {
                int amp = level - penalty;
                if (amp >= 0) {
                    player.addEffect(new MobEffectInstance(
                            MobEffects.DIG_SPEED, 40, amp, false, false, false));
                }
            }
            return;
        }

        // 石工：手持镐子
        if (held.is(net.minecraft.tags.ItemTags.PICKAXES)) {
            int level = calcLevel(player.getPersistentData().getInt(K_STONECUT),
                    ProfessionConfig.STONECUTTER_STAGES);
            if (level >= 0) {
                int amp = level - penalty;
                if (amp >= 0) {
                    player.addEffect(new MobEffectInstance(
                            MobEffects.DIG_SPEED, 40, amp, false, false, false));
                }
            }
            return;
        }

        // 技工：手持螺丝刀 → 急迫（无惩罚）
        if (ForgeRegistries.ITEMS.getKey(held.getItem()).equals(SCREWDRIVER_RL)) {
            int level = calcLevel(player.getPersistentData().getInt(K_MECHANIC),
                    ProfessionConfig.MECHANIC_STAGES);
            if (level >= 0) {
                player.addEffect(new MobEffectInstance(
                        MobEffects.DIG_SPEED, 40, level, false, false, false));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════════════════════

    /** 死亡重置所有职业数据 */
    @SubscribeEvent
    public static void onRespawn(net.minecraftforge.event.entity.player.PlayerEvent.Clone event) {
        if (!event.isWasDeath()) return;
        CompoundTag pd = event.getEntity().getPersistentData();
        pd.putInt(K_WOODCUT, 0);
        pd.putInt(K_STONECUT, 0);
        pd.putInt(K_MECHANIC, 0);
        pd.putInt("fishLevel", 1);
        pd.putInt("fishExp", 0);
    }

    /** 计算当前阶级（0=无, 1=I, 2=II, 3=III） */
    private static int calcLevel(int count, int[][] stages) {
        for (int i = stages.length - 1; i >= 0; i--) {
            if (count >= stages[i][0]) return i + 1;
        }
        return 0;
    }

    /** 计算当前阶级的 amplifier 值 */
    private static int calcAmplifier(int count, int[][] stages) {
        for (int i = stages.length - 1; i >= 0; i--) {
            if (count >= stages[i][0]) return stages[i][1];
        }
        return -1;
    }
}