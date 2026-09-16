package com.chaosz.tarkovstamina.condition;

import com.chaosz.tarkovstamina.StaminaConfig;
import com.chaosz.tarkovstamina.StaminaSystem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 恐慌 + 成瘾 + 麻木系统
 * <p>
 * - 恐慌：周围 12 格内怪物 ≥ 10 只 → 挖掘疲劳，需要烟酒冷静
 * - 麻木：累计击杀怪物 ≥ 100 只 → 免疫恐慌
 * - 烟瘾/酒瘾：摄入 3 次后上瘾，断供 30 分钟犯瘾，72 分钟戒断
 * - 冷静：烟酒摄入后提供 5 分钟冷静效果，移除挖掘疲劳
 * </p>
 */
public final class PanicSystem {
    private static final String K_MONSTER_KILLS = "monsterKills";
    private static final String K_CALM_UNTIL = "calmUntil";
    private static final String K_IS_SMOKE_ADDICTED = "isSmokeAddicted";
    private static final String K_SMOKE_COUNT = "smokeCount";
    private static final String K_LAST_SMOKE_TIME = "lastSmokeTime";
    private static final String K_IS_ALCOHOL_ADDICTED = "isAlcoholAddicted";
    private static final String K_ALCOHOL_COUNT = "alcoholCount";
    private static final String K_LAST_ALCOHOL_TIME = "lastAlcoholTime";

    private PanicSystem() {
    }

    // ═══════════════════════════════════════════════════════════════
    //  击杀计数
    // ═══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onEntityDeath(LivingDeathEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) return;
        LivingEntity killed = event.getEntity();
        if (!killed.isInvertedHealAndHarm()) return; // 不是怪物

        CompoundTag state = StaminaSystem.state(player, true);
        int kills = Math.min(state.getInt(K_MONSTER_KILLS) + 1, 99999);
        state.putInt(K_MONSTER_KILLS, kills);

        if (kills == StaminaConfig.NUMB_KILL_THRESHOLD.get()) {
            player.displayClientMessage(Component.literal(
                    "§7[心境变化] 杀戮已成平常，眼前的怪物再也无法让你动摇。"), false);
        }
    }

    // 死亡重置
    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        CompoundTag state = StaminaSystem.state(player, true);
        state.putInt(K_MONSTER_KILLS, 0);
        state.putLong(K_CALM_UNTIL, 0);
    }

    // ═══════════════════════════════════════════════════════════════
    //  摄入监听（烟/酒）— 吃东西完成时触发
    // ═══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onFoodEaten(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var stack = event.getItem();
        if (stack.isEmpty() || !stack.isEdible()) return;

        String id = ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
        boolean isCigarette = id.contains("harmfulsmoke:") && id.contains("cigarette");
        boolean isAlcohol = id.contains("beer") || id.contains("wine")
                || id.contains("whiskey") || id.contains("vodka");

        if (isCigarette) {
            onConsumed(player, "smoke");
        }
        if (isAlcohol) {
            onConsumed(player, "alcohol");
        }
    }

    private static void onConsumed(ServerPlayer player, String type) {
        CompoundTag state = StaminaSystem.state(player, true);
        long now = player.level().getGameTime();

        String countKey = type.equals("smoke") ? K_SMOKE_COUNT : K_ALCOHOL_COUNT;
        String timeKey = type.equals("smoke") ? K_LAST_SMOKE_TIME : K_LAST_ALCOHOL_TIME;
        String addictedKey = type.equals("smoke") ? K_IS_SMOKE_ADDICTED : K_IS_ALCOHOL_ADDICTED;
        String name = type.equals("smoke") ? "烟" : "酒";

        int count = state.getInt(countKey) + 1;
        state.putInt(countKey, count);
        state.putLong(timeKey, now);

        if (!state.getBoolean(addictedKey)) {
            if (count == 1) player.displayClientMessage(
                    Component.literal("§8[" + name + "] 这味道...似乎能让你暂时忘记烦恼。"), false);
            if (count == 2) player.displayClientMessage(
                    Component.literal("§8[" + name + "] 你开始有点迷恋这种感觉了。"), false);
            if (count >= StaminaConfig.ADDICTION_TRIGGER_COUNT.get()) {
                state.putBoolean(addictedKey, true);
                player.displayClientMessage(
                        Component.literal("§c[警告] 你已经产生" + name + "瘾了！"), false);
            }
        } else {
            player.displayClientMessage(
                    Component.literal("§a[" + name + "] 呼... 焦虑得到了缓解。"), false);
        }

        // 提供冷静效果
        state.putLong(K_CALM_UNTIL, now + StaminaConfig.CALM_DURATION_TICKS.get());
        player.removeEffect(MobEffects.DIG_SLOWDOWN);
    }

    // ═══════════════════════════════════════════════════════════════
    //  核心 Tick 逻辑
    // ═══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide()) {
            return;
        }
        if (event.player.tickCount % 100 != 0) return;

        ServerPlayer player = (ServerPlayer) event.player;
        Level level = player.level();
        CompoundTag state = StaminaSystem.state(player, true);
        long now = level.getGameTime();

        // ── 烟瘾判定 ──
        if (state.getBoolean(K_IS_SMOKE_ADDICTED)) {
            long diff = now - state.getLong(K_LAST_SMOKE_TIME);
            if (diff >= StaminaConfig.ADDICTION_QUIT_TICKS.get()) {
                state.putBoolean(K_IS_SMOKE_ADDICTED, false);
                state.putInt(K_SMOKE_COUNT, 0);
                player.displayClientMessage(Component.literal("§b经过长期的坚持，你成功戒烟了！"), false);
            } else if (diff >= StaminaConfig.ADDICTION_CRAVING_TICKS.get()) {
                if (now % 1000 == 0) {
                    player.displayClientMessage(Component.literal("§6烟瘾犯了..."), false);
                }
            }
        }

        // ── 酒瘾判定 ──
        if (state.getBoolean(K_IS_ALCOHOL_ADDICTED)) {
            long diff = now - state.getLong(K_LAST_ALCOHOL_TIME);
            if (diff >= StaminaConfig.ADDICTION_QUIT_TICKS.get()) {
                state.putBoolean(K_IS_ALCOHOL_ADDICTED, false);
                state.putInt(K_ALCOHOL_COUNT, 0);
                player.displayClientMessage(Component.literal("§b好样的，你成功戒酒了！"), false);
            } else if (diff >= StaminaConfig.ADDICTION_CRAVING_TICKS.get()) {
                if (now % 1000 == 0) {
                    player.displayClientMessage(Component.literal("§6酒瘾犯了，你感到浑身无力..."), false);
                }
                player.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 200, 0, false, false, false));
            }
        }

        // ── 恐慌判定 ──
        int killCount = state.getInt(K_MONSTER_KILLS);
        boolean isNumb = killCount >= StaminaConfig.NUMB_KILL_THRESHOLD.get();
        boolean isCalm = now < state.getLong(K_CALM_UNTIL);

        if (!isCalm && !isNumb) {
            int monsterCount = 0;
            double radius = StaminaConfig.PANIC_RADIUS.get();
            AABB aabb = player.getBoundingBox().inflate(radius);
            for (var e : level.getEntitiesOfClass(Mob.class, aabb,
                    e -> e.isAlive() && e.distanceTo(player) < radius)) {
                if (e.isInvertedHealAndHarm()) monsterCount++;
            }
            if (monsterCount >= StaminaConfig.PANIC_MONSTER_COUNT.get()) {
                player.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 200, 0, false, false, false));
                player.displayClientMessage(Component.literal("§5怪物太多了！你的手在发抖...你需要烟或酒来冷静！"), false);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  公开方法（供 KJS 或其他系统调用）
    // ═══════════════════════════════════════════════════════════════

    /** 记录摄入烟/酒，返回是否已上瘾 */
    public static boolean recordConsumption(ServerPlayer player, String type) {
        CompoundTag state = StaminaSystem.state(player, true);
        long now = player.level().getGameTime();
        String countKey = type.equals("smoke") ? K_SMOKE_COUNT : K_ALCOHOL_COUNT;
        String timeKey = type.equals("smoke") ? K_LAST_SMOKE_TIME : K_LAST_ALCOHOL_TIME;
        String addictedKey = type.equals("smoke") ? K_IS_SMOKE_ADDICTED : K_IS_ALCOHOL_ADDICTED;

        int count = state.getInt(countKey) + 1;
        state.putInt(countKey, count);
        state.putLong(timeKey, now);

        if (!state.getBoolean(addictedKey)) {
            if (count >= StaminaConfig.ADDICTION_TRIGGER_COUNT.get()) {
                state.putBoolean(addictedKey, true);
                player.displayClientMessage(Component.literal(
                        "§c[警告] 你已经产生" + (type.equals("smoke") ? "烟" : "酒") + "瘾了！"), false);
                return true;
            }
        }

        // 提供冷静效果
        state.putLong(K_CALM_UNTIL, now + StaminaConfig.CALM_DURATION_TICKS.get());
        player.removeEffect(MobEffects.DIG_SLOWDOWN);
        return state.getBoolean(addictedKey);
    }
}