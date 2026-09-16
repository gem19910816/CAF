package com.chaosz.tarkovstamina.survival;

import com.chaosz.tarkovstamina.StaminaSystem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Set;

/**
 * 拉屎系统（含窜稀）
 * <p>
 * 从 KJS 迁移：
 * - 食物历史跟踪：2分钟内吃下超过3种不同食物 → 窜稀
 * - 窜稀：憋不住提示(1秒) → 3秒后拉出3坨屎 + 伤害1点
 * - 正常拉屎：蹲下或坐马桶(refurbished_furniture:oak_toilet)，憋屎≥15分钟可拉
 * - 憋太久(30分钟)：腹胀提示 + 缓慢
 * - 吃屎功能：蹲下右键屎物品
 * </p>
 */
public final class PoopSystem {
    private static final String K_TIME_SINCE_POOP = "timeSinceLastPoop";
    private static final String K_HAS_DIARRHEA = "hasDiarrhea";
    private static final String K_DIARRHEA_COOLDOWN = "diarrheaCooldown";
    private static final String K_DIARRHEA_SPIT_TIMER = "diarrheaSpitTimer";
    private static final String K_POOP_TIMER = "poopTimer";
    private static final String K_FOOD_HISTORY = "foodHistory";
    private static final String K_LAST_FOOD_TIME = "lastFoodTime";

    private static final ResourceLocation TOILET_ID = ResourceLocation.tryBuild("refurbished_furniture", "oak_toilet");
    /** 我们的屎物品 */
    private static final ResourceLocation SHIT_ID = ResourceLocation.tryBuild("caf", "shit");
    /** 兼容旧版：末日装饰的屎 */
    private static final ResourceLocation SHIT_ID_LEGACY = ResourceLocation.tryBuild("doomsday_decoration", "shit");

    // 医疗物品白名单 - 注册为食物但不应触发窜稀
    private static final Set<String> MEDICAL_FOOD_EXCLUSIONS = Set.of(
            // survival_instinct
            "survival_instinct:bandage", "survival_instinct:homemade_bandage",
            "survival_instinct:medkit_bag", "survival_instinct:survival_medkit",
            "survival_instinct:alcohol_wipes",
            "survival_instinct:morphine_syringe", "survival_instinct:morphine_injector",
            "survival_instinct:adrenaline_syringe", "survival_instinct:adrenaline_injector",
            "survival_instinct:antibiotics", "survival_instinct:analgesic",
            "survival_instinct:blood_syringe",
            // caf 药品
            "caf:ganmaoyao", "caf:jieyanyao", "caf:jiejiuyao",
            // zombiekit
            "zombiekit:painkiller", "zombiekit:potion_jar",
            "zombiekit:bandage", "zombiekit:medical_kit",
            "zombiekit:suspicious_drug", "zombiekit:miracle",
            // spore
            "spore:syringe",
            // legendarysurvivaloverhaul
            "legendarysurvivaloverhaul:tonic",
            // tactical_aid
            "tactical_aid:relief_injector", "tactical_aid:adrenalineinjector",
            "tactical_aid:quickactioninjector", "tactical_aid:quickactioninjector_ii",
            "tactical_aid:aggressivenessinjector", "tactical_aid:painlessinjector",
            "tactical_aid:adrenalineinjector_ii", "tactical_aid:metabolizeinjector",
            "tactical_aid:glucoseinjector", "tactical_aid:adrenalineinjector_iii",
            "tactical_aid:nanometre_particlesinjector",
            // infectious
            "infectious:antibiotics", "infectious:bandage", "infectious:medikit"
    );

    private PoopSystem() {
    }

    // ═══════════════════════════════════════════════════════════════
    //  核心 Tick 逻辑
    // ═══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide()) {
            return;
        }
        ServerPlayer player = (ServerPlayer) event.player;
        CompoundTag state = StaminaSystem.state(player, true);
        Level level = player.level();

        // 食物历史管理：距上次进食超过 2400 ticks (2分钟) 重置
        long lastFoodTime = state.getLong(K_LAST_FOOD_TIME);
        if (lastFoodTime > 0 && level.getGameTime() - lastFoodTime >= 2400) {
            state.put(K_FOOD_HISTORY, new ListTag());
            state.putLong(K_LAST_FOOD_TIME, 0);
        }

        // 窜稀冷却递减
        int cooldown = state.getInt(K_DIARRHEA_COOLDOWN);
        if (cooldown > 0) {
            state.putInt(K_DIARRHEA_COOLDOWN, cooldown - 1);
        }

        // 存货计时
        long timeSincePoop = state.getLong(K_TIME_SINCE_POOP) + 1;
        state.putLong(K_TIME_SINCE_POOP, timeSincePoop);
        boolean hasStomachLoad = timeSincePoop >= 18000; // 15分钟

        // ── 窜稀流程 ──
        if (state.getBoolean(K_HAS_DIARRHEA)) {
            int spitTimer = state.getInt(K_DIARRHEA_SPIT_TIMER) + 1;
            state.putInt(K_DIARRHEA_SPIT_TIMER, spitTimer);

            if (spitTimer == 20) {
                player.displayClientMessage(Component.literal("§e你感觉快要憋不住了..."), false);
            }

            if (spitTimer >= 80) {
                for (int i = 0; i < 3; i++) {
                    spawnShit(player, 0.2 * i);
                }
                level.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.MUD_BREAK, SoundSource.AMBIENT, 1.0F, 0.5F);
                player.displayClientMessage(Component.literal("§c你窜稀了！"), false);
                player.hurt(player.damageSources().generic(), 1.0F);
                state.putBoolean(K_HAS_DIARRHEA, false);
                state.putInt(K_DIARRHEA_SPIT_TIMER, 0);
                state.putInt(K_DIARRHEA_COOLDOWN, 600);
            }
            return;
        }

        // ── 正常拉屎（蹲下或坐马桶）──
        boolean isOnToilet = false;
        if (player.getVehicle() != null && TOILET_ID.equals(
                BuiltInRegistries.ENTITY_TYPE.getKey(player.getVehicle().getType()))) {
            isOnToilet = true;
        }

        if (player.isCrouching() || isOnToilet) {
            if (hasStomachLoad) {
                int poopTimer = state.getInt(K_POOP_TIMER) + 1;
                state.putInt(K_POOP_TIMER, poopTimer);
                int threshold = isOnToilet ? 40 : 80;
                if (poopTimer >= threshold) {
                    spawnShit(player, 0.0);
                    level.playSound(null, player.getX(), player.getY(), player.getZ(),
                            SoundEvents.MUD_BREAK, SoundSource.AMBIENT, 1.0F, 0.5F);
                    state.putLong(K_TIME_SINCE_POOP, 0);
                    state.putInt(K_POOP_TIMER, 0);
                    player.displayClientMessage(Component.literal("§6高效施肥！"), false);
                    player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                }
            } else {
                state.putInt(K_POOP_TIMER, 0);
            }
        } else {
            state.putInt(K_POOP_TIMER, 0);
        }

        // ── 憋太久：腹胀 ──
        if (state.getLong(K_TIME_SINCE_POOP) >= 36000) { // 30分钟
            if (state.getLong(K_TIME_SINCE_POOP) % 400 == 0) {
                player.displayClientMessage(
                        Component.literal("§c你感觉腹胀难受，需要找个地方解决一下..."), false);
                player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
                        21 * 20, 0, false, false, true));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  食物跟踪：吃完食物时记录（排除医疗物品）
    // ═══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onFoodEaten(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack stack = event.getItem();
        if (stack.isEmpty() || !stack.isEdible()) return;

        String foodId = ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
        if (MEDICAL_FOOD_EXCLUSIONS.contains(foodId)) return;

        // lrtactical:consumable 特殊处理 - 医疗类(ibuprofen/blood_pack)排除
        if ("lrtactical:consumable".equals(foodId) && stack.getTag() != null) {
            String cid = stack.getTag().getString("ConsumableId");
            if ("lrtactical:ibuprofen".equals(cid) || "lrtactical:blood_pack".equals(cid)) {
                return;
            }
        }

        CompoundTag state = StaminaSystem.state(player, true);
        ListTag history = state.getList(K_FOOD_HISTORY, Tag.TAG_STRING);
        boolean exists = false;
        for (Tag tag : history) {
            if (tag.getAsString().equals(foodId)) {
                exists = true;
                break;
            }
        }

        if (!exists) {
            history.add(StringTag.valueOf(foodId));
            state.put(K_FOOD_HISTORY, history);
            state.putLong(K_LAST_FOOD_TIME, player.level().getGameTime());

            if (history.size() > 3
                    && !state.getBoolean(K_HAS_DIARRHEA)
                    && state.getInt(K_DIARRHEA_COOLDOWN) <= 0) {
                state.putBoolean(K_HAS_DIARRHEA, true);
                state.putLong(K_LAST_FOOD_TIME, player.level().getGameTime());
                player.displayClientMessage(
                        Component.literal("§e你感觉肚子有点不对劲..."), false);
                player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.FISH_SWIM, SoundSource.NEUTRAL, 0.6F, 0.5F);
            }

            while (history.size() > 3) {
                history.remove(0);
            }
            state.put(K_FOOD_HISTORY, history);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  吃屎功能（蹲下右键）
    // ═══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onEatShit(PlayerInteractEvent.RightClickItem event) {
        if (event.getSide().isClient()) return;

        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (!SHIT_ID.equals(id) && !SHIT_ID_LEGACY.equals(id) || !event.getEntity().isCrouching()) return;

        ServerPlayer player = (ServerPlayer) event.getEntity();
        player.displayClientMessage(Component.literal("§c你吃了屎。"), false);

        // 1. 强制扣除数量
        if (!player.isCreative()) {
            stack.shrink(1);
        }

        // 2. 增加饱食度
        player.getFoodData().eat(4, 0.2F);

        // 3. 给予负面状态
        player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 400, 1));
        player.addEffect(new MobEffectInstance(MobEffects.HUNGER, 200, 1));

        // 4. 播放声音
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.GENERIC_EAT, SoundSource.PLAYERS, 1.0F, 0.5F);

        // 5. 阻止默认行为
        event.setCanceled(true);
    }

    // ═══════════════════════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════════════════════

    private static void spawnShit(ServerPlayer player, double yOffset) {
        ItemStack shit = new ItemStack(ForgeRegistries.ITEMS.getValue(SHIT_ID), 1);
        ItemEntity entity = new ItemEntity(player.level(),
                player.getX(), player.getY() + yOffset, player.getZ(), shit);
        entity.setPickUpDelay(10);
        player.level().addFreshEntity(entity);
    }
}