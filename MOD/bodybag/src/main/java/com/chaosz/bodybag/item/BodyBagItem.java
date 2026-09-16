package com.chaosz.bodybag.item;

import com.chaosz.bodybag.BodyBagConfig;
import com.chaosz.bodybag.server.TrackingData;
import de.maxhenkel.corpse.corelib.death.Death;
import de.maxhenkel.corpse.entities.CorpseEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

public class BodyBagItem extends Item {

    private static final String TAG_FILLED = "filled";
    private static final String TAG_CORPSE_NAME = "corpseName";
    private static final String TAG_DEATH_NBT = "death";

    public BodyBagItem(Properties properties) {
        super(properties);
    }

    public static boolean isFilled(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.getBoolean(TAG_FILLED);
    }

    // ------------------------------------------------------------------
    // 右键使用
    // ------------------------------------------------------------------

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (isFilled(stack)) {
            // 已装满 → 服务端立即释放尸体
            if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
                releaseCorpse(level, serverPlayer, stack);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        // 空袋 → 服务端启动 10 秒收取任务
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            if (TrackingData.isCollecting(serverPlayer)) {
                serverPlayer.displayClientMessage(
                        Component.translatable("message.bodybag.already_collecting"), true);
                return InteractionResultHolder.fail(stack);
            }
            CorpseEntity corpse = findNearestCorpse(level, serverPlayer);
            if (corpse == null) {
                serverPlayer.displayClientMessage(
                        Component.translatable("message.bodybag.no_corpse"), true);
                return InteractionResultHolder.fail(stack);
            }
            TrackingData.startCollection(serverPlayer, corpse);
            return InteractionResultHolder.sidedSuccess(stack, false);
        }

        // 客户端：直接返回供服务端处理
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    // 去掉长按机制，不需要 getUseDuration / finishUsingItem / releaseUsing / onUseTick
    // 进度倒计时由 TrackingData 每秒推送 actionbar 消息

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.NONE;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        if (isFilled(stack)) {
            CompoundTag tag = stack.getTag();
            String name = tag != null ? tag.getString(TAG_CORPSE_NAME) : "?";
            tooltip.add(Component.translatable("item.bodybag.body_bag.tooltip.filled", name));
        } else {
            tooltip.add(Component.translatable("item.bodybag.body_bag.tooltip.empty"));
        }
    }

    // ------------------------------------------------------------------
    // 收取完成（由 TrackingData 回调）
    // ------------------------------------------------------------------

    public static void completeCollection(ServerPlayer player, CorpseEntity corpse) {
        // 找到玩家手中的裹尸袋
        ItemStack bag = findBag(player);
        if (bag == null) {
            player.displayClientMessage(Component.translatable("message.bodybag.cancelled"), true);
            return;
        }

        Death death = corpse.getDeath();
        if (death == null) {
            player.displayClientMessage(Component.translatable("message.bodybag.cancelled"), true);
            return;
        }

        // 保存完整的 Death NBT 到裹尸袋（包含所有物品、经验、位置等）
        CompoundTag deathNbt = death.toNBT();
        CompoundTag tag = bag.getOrCreateTag();
        tag.putBoolean(TAG_FILLED, true);
        tag.putString(TAG_CORPSE_NAME, death.getPlayerName());
        tag.put(TAG_DEATH_NBT, deathNbt);

        // 清除尸体死亡数据，避免 remove() 时掉落物品
        Death emptyDeath = new Death.Builder(death.getId(), death.getPlayerUUID())
                .playerName(death.getPlayerName())
                .mainInventory(NonNullList.withSize(death.getMainInventory().size(), ItemStack.EMPTY))
                .armorInventory(NonNullList.withSize(death.getArmorInventory().size(), ItemStack.EMPTY))
                .offHandInventory(NonNullList.withSize(death.getOffHandInventory().size(), ItemStack.EMPTY))
                .additionalItems(NonNullList.create())
                .equipment(NonNullList.create())
                .experience(0)
                .timestamp(death.getTimestamp())
                .posX(death.getPosX()).posY(death.getPosY()).posZ(death.getPosZ())
                .dimension(death.getDimension())
                .model(death.getModel())
                .build();
        corpse.setDeath(emptyDeath);

        // 特效 + 移除尸体
        corpse.spawnDeathParticles();
        corpse.remove(Entity.RemovalReason.DISCARDED);

        player.displayClientMessage(
                Component.translatable("message.bodybag.collected", death.getPlayerName()), true);
    }

    // ------------------------------------------------------------------
    // 释放尸体
    // ------------------------------------------------------------------

    private void releaseCorpse(Level level, ServerPlayer player, ItemStack bag) {
        CompoundTag tag = bag.getTag();
        if (tag == null || !tag.contains(TAG_DEATH_NBT)) {
            player.displayClientMessage(Component.translatable("message.bodybag.no_death_data"), true);
            clearBag(bag);
            return;
        }

        CompoundTag deathNbt = tag.getCompound(TAG_DEATH_NBT);
        Death death = Death.fromNBT(deathNbt);
        if (death == null) {
            player.displayClientMessage(Component.translatable("message.bodybag.no_death_data"), true);
            clearBag(bag);
            return;
        }

        // 创建尸体实体（使用原始死亡位置和朝向）
        CorpseEntity corpse = CorpseEntity.createFromDeath(player, death);
        if (corpse == null) {
            player.displayClientMessage(Component.translatable("message.bodybag.no_death_data"), true);
            clearBag(bag);
            return;
        }

        // 水平方向偏移 2 格（忽略俯仰，只取水平方向）
        Vec3 lookVec = player.getLookAngle();
        Vec3 horizontalLook = new Vec3(lookVec.x, 0, lookVec.z).normalize();
        double spawnX = player.getX() + horizontalLook.x * 2.0;
        double spawnZ = player.getZ() + horizontalLook.z * 2.0;

        // 找地面（找到该位置最上面的非空气方块，放在它上面）
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(
                (int) Math.floor(spawnX),
                (int) Math.floor(player.getY()),
                (int) Math.floor(spawnZ));
        // 从玩家脚底往下找地面
        while (pos.getY() > level.getMinBuildHeight() && level.getBlockState(pos).isAir()) {
            pos.move(0, -1, 0);
        }
        // 如果找到了非空气方块，放在它上面；否则放在玩家脚底
        double spawnY;
        if (!level.getBlockState(pos).isAir()) {
            spawnY = pos.getY() + 1.0;
        } else {
            spawnY = player.getY();
        }

        corpse.setPos(spawnX, spawnY, spawnZ);
        corpse.setYRot(player.getYRot());

        // 生成到世界
        level.addFreshEntity(corpse);

        // 清空袋子的数据
        clearBag(bag);

        player.displayClientMessage(
                Component.translatable("message.bodybag.released", death.getPlayerName()), true);
    }

    private static void clearBag(ItemStack bag) {
        bag.removeTagKey(TAG_FILLED);
        bag.removeTagKey(TAG_CORPSE_NAME);
        bag.removeTagKey(TAG_DEATH_NBT);
    }

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------

    /**
     * 返回玩家主手或副手第一个未装满的裹尸袋。
     */
    @Nullable
    private static ItemStack findBag(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof BodyBagItem && !isFilled(stack)) {
                return stack;
            }
        }
        return null;
    }

    public static CorpseEntity findNearestCorpse(Level level, Player player) {
        double range = BodyBagConfig.COLLECT_RANGE.get();
        AABB aabb = player.getBoundingBox().inflate(range);
        List<CorpseEntity> corpses = level.getEntitiesOfClass(CorpseEntity.class, aabb,
                c -> c.isAlive() && !c.isRemoved());
        CorpseEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (CorpseEntity corpse : corpses) {
            double dist = player.distanceToSqr(corpse.getX(), corpse.getY(), corpse.getZ());
            if (dist <= range * range && dist < best) {
                best = dist;
                nearest = corpse;
            }
        }
        return nearest;
    }
}