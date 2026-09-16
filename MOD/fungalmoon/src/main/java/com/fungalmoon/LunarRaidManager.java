package com.fungalmoon;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/**
 * 核心逻辑：
 *  1. 月球上直接生成最终形态的红夜（5岁土丘/RedNight）。
 *  2. 红夜定期在地球玩家附近刷“感染点”（一小块真菌区域）。
 *  3. 玩家必须处理感染点（挖掉），否则感染扩散并刷出真菌怪。
 *  4. 玩家杀死红夜后，所有维度真菌生成永久停止。
 *  5. 怪物强度阶梯式上升：每成功处理 N 次袭击，怪物等级提升。
 */
@Mod.EventBusSubscriber(modid = FungalMoon.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class LunarRaidManager {

    private static final ResourceKey<Level> MOON =
            ResourceKey.create(Registries.DIMENSION, new ResourceLocation("ad_astra", "moon"));
    private static final ResourceLocation MOUND_ID = new ResourceLocation("spore", "mound");

    private static final Random RANDOM = new Random();

    private static long nextRaidCheck = -1L;
    private static long nextRedNightCheck = -1L;
    private static int raidsToday = 0;
    private static long lastDay = -1L;

    private static boolean redNightSpawned = false;
    private static boolean fungusDisabled = false;

    // 阶梯式强度：当前怪物等级（1~4）
    private static int currentTier = 1;
    private static int successfulCleans = 0;

    // 感染点追踪
    private static final List<InfectionPoint> infectionPoints = new ArrayList<>();
    private static final int INFECTION_SPREAD_TIME = 2400;
    private static final int INFECTION_SPAWN_TIME = 3600;
    private static final int INFECTION_RADIUS = 3;

    private LunarRaidManager() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        MinecraftServer server = event.getServer();
        ServerLevel overworld = server.overworld();
        ServerLevel moon = server.getLevel(MOON);
        if (moon == null) return;

        long gameTime = overworld.getGameTime();
        long day = overworld.getDayTime() / 24000L;
        if (day != lastDay) {
            lastDay = day;
            raidsToday = 0;
        }

        if (!redNightSpawned && gameTime >= nextRedNightCheck) {
            nextRedNightCheck = gameTime + 200L;
            trySpawnRedNight(moon);
        }

        if (!fungusDisabled) {
            processInfectionPoints(server, gameTime);
        }

        // 每 7 个游戏日触发一次袭击（在游戏日变化时检测）
        if (day != lastDay) {
            lastDay = day;
            raidsToday = 0;
            // 检查是否是第 7 天（day 从 0 开始，0~6 为第一个周期）
            if (day % 7 == 0 && !overworld.players().isEmpty()) {
                trySpawnInfectionPoint(server, overworld);
            }
        }
    }

    // ==================== 红夜生成 ====================

    private static void trySpawnRedNight(ServerLevel moon) {
        EntityType<?> moundType = ForgeRegistries.ENTITY_TYPES.getValue(MOUND_ID);
        if (moundType == null) return;

        BlockPos spawnPos = null;
        for (int i = 0; i < 32; i++) {
            int x = RANDOM.nextInt(2000) - 1000;
            int z = RANDOM.nextInt(2000) - 1000;
            BlockPos pos = moon.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    new BlockPos(x, moon.getSeaLevel(), z));
            if (moon.getBlockState(pos.below()).isSolidRender(moon, pos.below())) {
                spawnPos = pos;
                break;
            }
        }
        if (spawnPos == null) return;

        Entity entity = moundType.create(moon);
        if (!(entity instanceof Mob mob)) {
            if (entity != null) entity.discard();
            return;
        }

        mob.moveTo(spawnPos.getX() + 0.5D, spawnPos.getY() + 1.0D, spawnPos.getZ() + 0.5D, 0.0F, 0.0F);
        mob.finalizeSpawn(moon, moon.getCurrentDifficultyAt(spawnPos), MobSpawnType.EVENT, null, null);
        mob.setPersistenceRequired();

        CompoundTag nbt = mob.saveWithoutId(new CompoundTag());
        nbt.putInt("age", 5);
        nbt.putInt("max_age", 5);
        mob.load(nbt);

        mob.setCustomName(Component.literal("§4RedNight"));
        mob.setCustomNameVisible(true);

        moon.addFreshEntity(mob);
        redNightSpawned = true;

        Component msg = Component.literal("§5[真菌入侵] §d月球的真菌主巢“红夜”已经苏醒！它会定期向地球播撒感染……消灭它才能终结一切！");
        moon.getServer().getPlayerList().broadcastSystemMessage(msg, false);
    }

    // ==================== 感染点机制 ====================

    private static void trySpawnInfectionPoint(MinecraftServer server, ServerLevel overworld) {
        if (fungusDisabled) return;
        List<ServerPlayer> players = overworld.players();
        if (players.isEmpty()) return;

        ServerPlayer target = players.get(RANDOM.nextInt(players.size()));
        BlockPos center = target.blockPosition();

        int rMin = FMConfig.SPAWN_RADIUS_MIN.get();
        int rMax = Math.max(rMin, FMConfig.SPAWN_RADIUS_MAX.get());
        BlockPos spawnPos = findSpawnPos(overworld, center, rMin, rMax);
        if (spawnPos == null) return;

        InfectionPoint point = new InfectionPoint(spawnPos, overworld.dimension(), overworld.getGameTime());
        infectionPoints.add(point);
        raidsToday++;

        spawnInfectionBlocks(overworld, spawnPos, INFECTION_RADIUS);

        if (FMConfig.ANNOUNCE_RAIDS.get()) {
            Component msg = Component.literal("§5[真菌入侵] §d月球的“红夜”向地球播撒了感染孢子！坐标："
                    + spawnPos.getX() + ", " + spawnPos.getY() + ", " + spawnPos.getZ()
                    + "。尽快处理，否则真菌将蔓延！");
            target.server.getPlayerList().broadcastSystemMessage(msg, false);
        }
    }

    private static void spawnInfectionBlocks(ServerLevel level, BlockPos center, int radius) {
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                for (int y = -2; y <= 2; y++) {
                    BlockPos pos = center.offset(x, y, z);
                    if (level.getBlockState(pos).isAir()) continue;
                    if (!level.getBlockState(pos.below()).isSolidRender(level, pos.below())) continue;
                    if (RANDOM.nextFloat() < 0.7f) {
                        level.setBlock(pos, Blocks.MYCELIUM.defaultBlockState(), 3);
                    } else {
                        level.setBlock(pos, Blocks.PODZOL.defaultBlockState(), 3);
                    }
                    if (level.getBlockState(pos.above()).isAir() && RANDOM.nextFloat() < 0.3f) {
                        level.setBlock(pos.above(), Blocks.RED_MUSHROOM.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    private static void processInfectionPoints(MinecraftServer server, long gameTime) {
        Iterator<InfectionPoint> it = infectionPoints.iterator();
        while (it.hasNext()) {
            InfectionPoint point = it.next();
            ServerLevel level = server.getLevel(point.dimension);
            if (level == null) {
                it.remove();
                continue;
            }

            boolean stillInfected = false;
            for (int x = -INFECTION_RADIUS; x <= INFECTION_RADIUS && !stillInfected; x++) {
                for (int z = -INFECTION_RADIUS; z <= INFECTION_RADIUS && !stillInfected; z++) {
                    for (int y = -2; y <= 2 && !stillInfected; y++) {
                        BlockPos pos = point.pos.offset(x, y, z);
                        BlockState state = level.getBlockState(pos);
                        if (state.is(Blocks.MYCELIUM) || state.is(Blocks.PODZOL)) {
                            stillInfected = true;
                        }
                    }
                }
            }

            if (!stillInfected) {
                it.remove();
                successfulCleans++;
                checkEscalation(level);
                Component msg = Component.literal("§a[真菌入侵] §f一处真菌感染点已被清理！");
                level.getServer().getPlayerList().broadcastSystemMessage(msg, false);
                continue;
            }

            long age = gameTime - point.spawnTime;

            if (age >= INFECTION_SPREAD_TIME && !point.hasSpread) {
                point.hasSpread = true;
                spawnInfectionBlocks(level, point.pos, INFECTION_RADIUS + 2);
                Component msg = Component.literal("§c[真菌入侵] §f一处真菌感染点正在扩散！坐标："
                        + point.pos.getX() + ", " + point.pos.getY() + ", " + point.pos.getZ());
                level.getServer().getPlayerList().broadcastSystemMessage(msg, false);
            }

            if (age >= INFECTION_SPAWN_TIME && !point.hasSpawned) {
                point.hasSpawned = true;
                spawnInfectionMobs(level, point.pos);
            }
        }
    }

    private static void checkEscalation(ServerLevel level) {
        int threshold = FMConfig.ESCALATION_THRESHOLD.get();
        if (successfulCleans >= threshold && currentTier < 4) {
            currentTier++;
            successfulCleans = 0;
            Component msg = Component.literal("§4[真菌入侵] §f红夜感受到了威胁……真菌的攻势将变得更加猛烈！（当前强度等级：" + currentTier + "）");
            level.getServer().getPlayerList().broadcastSystemMessage(msg, false);
        }
    }

    private static void spawnInfectionMobs(ServerLevel level, BlockPos center) {
        List<? extends String> mobPool = getCurrentTierMobs();
        if (mobPool.isEmpty()) return;

        int size = 3 + RANDOM.nextInt(4);
        for (int i = 0; i < size; i++) {
            String id = mobPool.get(RANDOM.nextInt(mobPool.size()));
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(id));
            if (type == null) continue;

            BlockPos pos = center.offset(RANDOM.nextInt(10) - 5, 0, RANDOM.nextInt(10) - 5);
            pos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos);

            Entity entity = type.create(level);
            if (!(entity instanceof Mob mob)) {
                if (entity != null) entity.discard();
                continue;
            }
            mob.moveTo(pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D,
                    RANDOM.nextFloat() * 360.0F, 0.0F);
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null, null);
            mob.setPersistenceRequired();
            level.addFreshEntity(mob);
        }

        Component msg = Component.literal("§4[真菌入侵] §f真菌感染点孵化出了怪物！坐标："
                + center.getX() + ", " + center.getY() + ", " + center.getZ());
        level.getServer().getPlayerList().broadcastSystemMessage(msg, false);
    }

    private static List<? extends String> getCurrentTierMobs() {
        return switch (currentTier) {
            case 1 -> FMConfig.TIER1_MOBS.get();
            case 2 -> FMConfig.TIER2_MOBS.get();
            case 3 -> FMConfig.TIER3_MOBS.get();
            case 4 -> FMConfig.TIER4_MOBS.get();
            default -> FMConfig.TIER1_MOBS.get();
        };
    }

    private static BlockPos findSpawnPos(ServerLevel level, BlockPos center, int rMin, int rMax) {
        for (int attempt = 0; attempt < 16; attempt++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2.0D;
            int dist = rMin + RANDOM.nextInt(rMax - rMin + 1);
            int x = center.getX() + (int) Math.round(Math.cos(angle) * dist);
            int z = center.getZ() + (int) Math.round(Math.sin(angle) * dist);
            BlockPos surface = level.getHeightmapPos(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    new BlockPos(x, level.getSeaLevel(), z));
            if (!level.getWorldBorder().isWithinBounds(surface)) continue;
            if (!level.getBlockState(surface.below()).isSolidRender(level, surface.below())) continue;
            if (!level.getBlockState(surface).isAir()) continue;
            if (!level.getBlockState(surface.above()).isAir()) continue;
            return surface;
        }
        return null;
    }

    private static long randomInterval() {
        // 已弃用，改为按游戏日触发
        return 1200L;
    }

    // ==================== 红夜死亡 & 真菌关闭 ====================

    @SubscribeEvent
    public static void onEntityDeath(LivingDeathEvent event) {
        if (fungusDisabled) return;
        Entity entity = event.getEntity();
        if (!(entity instanceof Mob mob)) return;
        if (mob.level().dimension() != MOON) return;
        if (!mob.getType().toString().equals("spore:mound")) return;
        CompoundTag nbt = mob.saveWithoutId(new CompoundTag());
        if (nbt.getInt("age") != 5) return;

        fungusDisabled = true;
        infectionPoints.clear();
        Component msg = Component.literal("§a[真菌入侵] §f真菌主巢“红夜”已被消灭！所有维度的真菌将逐渐消亡……");
        mob.getServer().getPlayerList().broadcastSystemMessage(msg, false);
    }

    // ==================== 真菌怪属性接管 ====================

    @SubscribeEvent
    public static void onEntityJoinWorld(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof Mob mob)) return;
        if (!FMConfig.OVERRIDE_MOB_STATS.get()) return;
        if (!mob.getType().toString().startsWith("spore:")) return;

        // 红夜只允许在月球存在，出现在其他维度立刻移除
        if (mob.getType().toString().equals("spore:mound")) {
            CompoundTag nbt = mob.saveWithoutId(new CompoundTag());
            if (nbt.getInt("age") == 5 && mob.level().dimension() != MOON) {
                event.setCanceled(true);
                return;
            }
        }

        // 从配置读取属性
        for (String entry : FMConfig.MOB_STATS.get()) {
            String[] parts = entry.split("\\|");
            if (parts.length != 4) continue;
            if (!parts[0].equals(mob.getType().toString())) continue;

            try {
                double health = Double.parseDouble(parts[1]);
                double damage = Double.parseDouble(parts[2]);
                double armor = Double.parseDouble(parts[3]);

                if (mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH) != null) {
                    mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(health);
                    mob.setHealth((float) health);
                }
                if (mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) != null) {
                    mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE).setBaseValue(damage);
                }
                if (mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR) != null) {
                    mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR).setBaseValue(armor);
                }
            } catch (NumberFormatException ignored) {}
            break;
        }
    }

    // ==================== 真菌怪月球生存（无空气免疫） ====================

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        if (!mob.getType().toString().startsWith("spore:")) return;
        // 在月球维度（ad_astra:moon）的真菌怪不会窒息
        if (mob.level().dimension() == MOON) {
            mob.setAirSupply(mob.getMaxAirSupply());
        }
    }

    // ==================== 内部类 ====================

    private static class InfectionPoint {
        final BlockPos pos;
        final ResourceKey<Level> dimension;
        final long spawnTime;
        boolean hasSpread = false;
        boolean hasSpawned = false;

        InfectionPoint(BlockPos pos, ResourceKey<Level> dimension, long spawnTime) {
            this.pos = pos;
            this.dimension = dimension;
            this.spawnTime = spawnTime;
        }
    }
}
