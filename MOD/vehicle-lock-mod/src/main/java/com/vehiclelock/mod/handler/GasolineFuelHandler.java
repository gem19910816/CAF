package com.vehiclelock.mod.handler;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.vehiclelock.mod.GasolineUtils;
import com.vehiclelock.mod.VehicleLockMod;
import com.vehiclelock.mod.block.entity.GasPumpBlockEntity;
import com.vehiclelock.mod.item.FuelNozzleItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 汽油系统事件处理：
 * <ul>
 *   <li>汽油桶蹲下右键载具 → 直接加一桶油；</li>
 *   <li>空桶蹲下右键载具 → 抽一桶油；</li>
 *   <li>手持加油枪蹲下右键载具 → 把枪插到载具上，开始自动加油；</li>
 *   <li>加油中蹲下右键载具 → 把枪拔回来；</li>
 *   <li>加满 / 没油 / 车开太远 / 枪被销毁 / 玩家下线时油管自动脱落。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = VehicleLockMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class GasolineFuelHandler {

    /** 进行中的加油会话：vehicleUuid -> 会话数据。 */
    private static final Map<UUID, FuelingSession> ACTIVE = new HashMap<>();

    private static final class FuelingSession {
        final ItemStack nozzle;
        final UUID playerUuid;
        final BlockPos pumpPos;
        final UUID vehicleUuid;
        int tickCounter = 0;

        FuelingSession(ItemStack nozzle, UUID playerUuid, BlockPos pumpPos, UUID vehicleUuid) {
            this.nozzle = nozzle;
            this.playerUuid = playerUuid;
            this.pumpPos = pumpPos;
            this.vehicleUuid = vehicleUuid;
        }
    }

    // ================= 交互入口 =================

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        Entity target = event.getTarget();
        if (!(target instanceof VehicleEntity ve)) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!player.isCrouching()) return;

        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) return;

        // ========== 加油枪 ==========
        if (stack.getItem() instanceof FuelNozzleItem) {
            event.setCanceled(true);
            if (player.level().isClientSide()) return;

            // 枪已插着 → 拔回来
            if (FuelNozzleItem.hasTarget(stack)) {
                UUID vid = FuelNozzleItem.getTargetVehicle(stack);
                FuelNozzleItem.clearTarget(stack);
                if (vid != null) ACTIVE.remove(vid);
                player.displayClientMessage(Component.literal("§a[加油] 已拔下加油枪，请放回加油机"), true);
                return;
            }

            // 这辆车已经被别的枪插着
            if (ACTIVE.containsKey(ve.getUUID())) {
                player.displayClientMessage(Component.literal("§c[加油] 这辆车已经在加油了"), true);
                return;
            }

            // 插上这辆车
            BlockPos pumpPos = FuelNozzleItem.getLinkedPump(stack);
            if (pumpPos == null) {
                player.displayClientMessage(Component.literal("§c[加油] 这把加油枪没有绑定加油机"), true);
                return;
            }
            if (!(player.level().getBlockEntity(pumpPos) instanceof GasPumpBlockEntity)) {
                player.displayClientMessage(Component.literal("§c[加油] 加油机不在原位了"), true);
                return;
            }
            if (ve.blockPosition().distSqr(pumpPos) > (long) FuelNozzleItem.MAX_HOSE_DISTANCE * FuelNozzleItem.MAX_HOSE_DISTANCE) {
                player.displayClientMessage(Component.literal("§c[加油] 油管不够长，靠近加油机"), true);
                return;
            }
            if (!ve.hasEnergyStorage() || ve.getMaxEnergy() <= 0) {
                player.displayClientMessage(Component.literal("§c[加油] 这辆车不需要燃料"), true);
                return;
            }
            if (ve.getEnergy() >= ve.getMaxEnergy()) {
                player.displayClientMessage(Component.literal("§e[加油] 油箱已满"), true);
                return;
            }

            // 一个玩家同时只能加一辆
            for (FuelingSession fs : ACTIVE.values()) {
                if (fs.playerUuid.equals(player.getUUID())) {
                    player.displayClientMessage(Component.literal("§c[加油] 你已经在给另一辆车加油了"), true);
                    return;
                }
            }

            FuelNozzleItem.setTargetVehicle(stack, ve.getUUID());
            ACTIVE.put(ve.getUUID(), new FuelingSession(stack, player.getUUID(), pumpPos, ve.getUUID()));
            player.displayClientMessage(Component.literal("§a[加油] 加油枪已接上，§e请手持加油枪不要切换§a，加满或蹲下右键车拔回"), true);
            return;
        }

        if (player.level().isClientSide()) return;

        // ========== 汽油桶 / 空桶 ==========
        if (GasolineUtils.isGasolineBucket(stack)) {
            refuel(player, ve, stack, event);
        } else if (stack.is(Items.BUCKET)) {
            drain(player, ve, stack, event);
        }
    }

    // ================= 服务端 tick 推进 =================

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (ACTIVE.isEmpty()) return;

        Iterator<Map.Entry<UUID, FuelingSession>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            FuelingSession s = it.next().getValue();

            // 枪已被销毁/丢弃到不存在的物品堆 → 终止
            if (s.nozzle.isEmpty() || !(s.nozzle.getItem() instanceof FuelNozzleItem)) {
                it.remove(); continue;
            }

            // 持枪玩家不在线 → 终止
            ServerPlayer holder = event.getServer().getPlayerList().getPlayer(s.playerUuid);
            if (holder == null) {
                FuelNozzleItem.clearTarget(s.nozzle);
                it.remove(); continue;
            }
            // 玩家不再拿着这把枪（丢了 / 切换物品）→ 终止
            if (holder.getMainHandItem() != s.nozzle && !holder.getInventory().contains(s.nozzle)) {
                FuelNozzleItem.clearTarget(s.nozzle);
                it.remove(); continue;
            }

            ServerLevel level = null;
            for (ServerLevel l : event.getServer().getAllLevels()) {
                if (l.getEntity(s.vehicleUuid) instanceof VehicleEntity) { level = l; break; }
            }
            if (level == null) {
                // 找不到载具所在维度，无法定位加油机 → 保守处理：仅清标记
                detach(s, holder, "§c[加油] 找不到载具，油管脱落");
                it.remove(); continue;
            }

            VehicleEntity vehicle = (VehicleEntity) level.getEntity(s.vehicleUuid);
            if (!(level.getBlockEntity(s.pumpPos) instanceof GasPumpBlockEntity pump)) {
                detach(s, holder, "§c[加油] 加油机不在原位了，油管脱落");
                it.remove(); continue;
            }
            if (vehicle.blockPosition().distSqr(s.pumpPos) > (long) FuelNozzleItem.MAX_HOSE_DISTANCE * FuelNozzleItem.MAX_HOSE_DISTANCE) {
                it.remove();
                returnNozzleToPump(s, level, holder, "§c[加油] 车开太远，油管脱落，加油枪已归还加油机");
                continue;
            }

            int current = vehicle.getEnergy();
            int max = vehicle.getMaxEnergy();
            if (current >= max) {
                // 加满了：自动把枪传回加油机
                it.remove();
                returnNozzleToPump(s, level, holder, "§a[加油] 已加满！加油枪已自动归还");
                continue;
            }

            int drained = pump.pullGasoline(FuelNozzleItem.MB_PER_TICK);
            if (drained <= 0) {
                it.remove();
                returnNozzleToPump(s, level, holder, "§c[加油] 加油机没油了，加油枪已自动归还");
                continue;
            }

            GasolineUtils.setVehicleFuel(vehicle, Math.min(current + drained * GasolineUtils.FUEL_PER_MB, max));
            if (++s.tickCounter % 40 == 0) {
                holder.displayClientMessage(Component.literal("§e[加油] 加油中... §f" + vehicle.getEnergy() + " / " + max), true);
            }
        }
    }

    // ================= 玩家死亡时归还加油枪 =================

    @SubscribeEvent
    public static void onPlayerDeath(net.minecraftforge.event.entity.living.LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer holder)) return;
        if (holder.level().isClientSide()) return;

        // 检查玩家身上所有加油枪
        for (int i = 0; i < holder.getInventory().getContainerSize(); i++) {
            ItemStack stack = holder.getInventory().getItem(i);
            if (!(stack.getItem() instanceof FuelNozzleItem)) continue;
            BlockPos pumpPos = FuelNozzleItem.getLinkedPump(stack);
            if (pumpPos == null) continue;

            // 找到对应加油机并归还
            ServerLevel level = holder.serverLevel();
            if (level.getBlockEntity(pumpPos) instanceof GasPumpBlockEntity pump) {
                if (pump.hasNozzleOut()) {
                    pump.setNozzleOut(false);
                    pump.setChanged();
                    net.minecraft.world.level.block.state.BlockState pumpState = level.getBlockState(pumpPos);
                    if (pumpState.getBlock() instanceof com.vehiclelock.mod.block.GasPumpBlock) {
                        level.setBlock(pumpPos, pumpState.setValue(com.vehiclelock.mod.block.GasPumpBlock.HAS_NOZZLE, true), 3);
                    }
                    // 从背包移除
                    holder.getInventory().setItem(i, ItemStack.EMPTY);
                }
            }
        }
    }

    // ================= 玩家下线清理 =================

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer holder)) return;
        UUID pid = holder.getUUID();
        ServerLevel level = holder.serverLevel();

        Iterator<Map.Entry<UUID, FuelingSession>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            FuelingSession s = it.next().getValue();
            if (!s.playerUuid.equals(pid)) continue;
            it.remove();

            // 枪留在下线的玩家背包里，清掉 NBT 标记，避免变成"死枪"
            FuelNozzleItem.clearTarget(s.nozzle);
            FuelNozzleItem.clearPump(s.nozzle);

            // 加油机状态复位（枪回到模型上）
            if (level.getBlockEntity(s.pumpPos) instanceof GasPumpBlockEntity pump) {
                pump.setNozzleOut(false);
                pump.setChanged();
                net.minecraft.world.level.block.state.BlockState pumpState = level.getBlockState(s.pumpPos);
                if (pumpState.getBlock() instanceof com.vehiclelock.mod.block.GasPumpBlock) {
                    level.setBlock(s.pumpPos, pumpState.setValue(com.vehiclelock.mod.block.GasPumpBlock.HAS_NOZZLE, true), 3);
                }
            }
        }
    }

    // ================= 内部工具 =================

    private static void detach(FuelingSession s, ServerPlayer holder, String msg) {
        FuelNozzleItem.clearTarget(s.nozzle);
        if (holder != null) holder.displayClientMessage(Component.literal(msg), true);
    }

    /** 把枪收回加油机：清 NBT、从玩家手里扣掉、加油机 nozzleOut 复位、模型恢复显示。 */
    private static void returnNozzleToPump(FuelingSession s, ServerLevel level, ServerPlayer holder, String msg) {
        if (!(level.getBlockEntity(s.pumpPos) instanceof GasPumpBlockEntity pump)) {
            detach(s, holder, "§c[加油] 加油机不在原位了，无法归还");
            return;
        }

        // 清掉枪的标记
        FuelNozzleItem.clearTarget(s.nozzle);

        // 从玩家手里扣掉这把枪（主手或背包）
        boolean removed = false;
        if (holder.getMainHandItem() == s.nozzle) {
            holder.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            removed = true;
        } else if (holder.getOffhandItem() == s.nozzle) {
            holder.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            removed = true;
        } else {
            for (int i = 0; i < holder.getInventory().getContainerSize(); i++) {
                if (holder.getInventory().getItem(i) == s.nozzle) {
                    holder.getInventory().setItem(i, ItemStack.EMPTY);
                    removed = true;
                    break;
                }
            }
        }
        if (!removed) {
            // 找不到就直接 shrink，保底
            s.nozzle.shrink(1);
        }

        // 加油机复位
        pump.setNozzleOut(false);
        pump.setChanged();
        net.minecraft.world.level.block.state.BlockState pumpState = level.getBlockState(s.pumpPos);
        if (pumpState.getBlock() instanceof com.vehiclelock.mod.block.GasPumpBlock) {
            level.setBlock(s.pumpPos, pumpState.setValue(com.vehiclelock.mod.block.GasPumpBlock.HAS_NOZZLE, true), 3);
        }
        level.playSound(null, s.pumpPos, net.minecraft.sounds.SoundEvents.ITEM_FRAME_ADD_ITEM, net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.0f);

        holder.displayClientMessage(Component.literal(msg), true);
        holder.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.5f);
    }

    // ================= 汽油桶直接加油 =================

    private static void refuel(Player player, VehicleEntity ve, ItemStack stack,
                               PlayerInteractEvent.EntityInteract event) {
        if (!ve.hasEnergyStorage() || ve.getMaxEnergy() <= 0) {
            player.displayClientMessage(Component.literal("§c[加油] 这辆车不需要燃料"), true);
            event.setCanceled(true);
            return;
        }
        int current = ve.getEnergy();
        int max = ve.getMaxEnergy();
        if (current >= max) {
            player.displayClientMessage(Component.literal("§e[加油] 油箱已满 (" + max + ")"), true);
            event.setCanceled(true);
            return;
        }
        int next = Math.min(current + GasolineUtils.FUEL_PER_BUCKET, max);
        GasolineUtils.setVehicleFuel(ve, next);

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
            ItemStack emptyBucket = new ItemStack(Items.BUCKET);
            if (stack.isEmpty()) {
                player.setItemInHand(InteractionHand.MAIN_HAND, emptyBucket);
            } else if (!player.getInventory().add(emptyBucket)) {
                player.drop(emptyBucket, false);
            }
        }
        player.displayClientMessage(Component.literal("§a[加油] 加油成功！当前油量 §f" + next + " / " + max), true);
        event.setCanceled(true);
    }

    // ================= 空桶抽油 =================

    private static void drain(Player player, VehicleEntity ve, ItemStack stack,
                              PlayerInteractEvent.EntityInteract event) {
        if (!ve.hasEnergyStorage() || ve.getMaxEnergy() <= 0) {
            player.displayClientMessage(Component.literal("§c[抽油] 这辆车没有油箱"), true);
            event.setCanceled(true);
            return;
        }
        int current = ve.getEnergy();
        if (current < GasolineUtils.FUEL_PER_BUCKET) {
            player.displayClientMessage(Component.literal("§c[抽油] 油量不足一桶（需要 " + GasolineUtils.FUEL_PER_BUCKET + "，当前 " + current + "）"), true);
            event.setCanceled(true);
            return;
        }
        int next = current - GasolineUtils.FUEL_PER_BUCKET;
        GasolineUtils.setVehicleFuel(ve, next);

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
            Item gasolineBucket = GasolineUtils.getGasolineBucketItem();
            ItemStack bucket = new ItemStack(gasolineBucket != null ? gasolineBucket : Items.BUCKET);
            if (stack.isEmpty()) {
                player.setItemInHand(InteractionHand.MAIN_HAND, bucket);
            } else if (!player.getInventory().add(bucket)) {
                player.drop(bucket, false);
            }
        }
        player.displayClientMessage(Component.literal("§a[抽油] 抽出 1 桶汽油！剩余油量 §f" + next), true);
        event.setCanceled(true);
    }
}
