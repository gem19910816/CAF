package com.vehiclelock.mod.item;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 加油枪：从加油机上拔下来，蹲下右键载具接上油管开始自动加油，
 * 加满后蹲下右键载具把枪拔回来，再右键加油机放回原位。
 */
public class FuelNozzleItem extends Item {

    public static final int MB_PER_TICK = 20;
    public static final int MAX_HOSE_DISTANCE = 16;

    private static final String TAG_PUMP = "Pump";
    private static final String TAG_TARGET = "TargetVehicle";

    public FuelNozzleItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    // ================= NBT 绑定信息 =================

    public static void setLinkedPump(ItemStack stack, BlockPos pos) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.put(TAG_PUMP, NbtUtils.writeBlockPos(pos));
    }

    @Nullable
    public static BlockPos getLinkedPump(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_PUMP)) return null;
        return NbtUtils.readBlockPos(tag.getCompound(TAG_PUMP));
    }

    public static void setTargetVehicle(ItemStack stack, UUID vehicleUuid) {
        stack.getOrCreateTag().putUUID(TAG_TARGET, vehicleUuid);
    }

    @Nullable
    public static UUID getTargetVehicle(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.hasUUID(TAG_TARGET)) return null;
        return tag.getUUID(TAG_TARGET);
    }

    public static void clearTarget(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            tag.remove(TAG_TARGET);
        }
    }

    public static void clearPump(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            tag.remove(TAG_PUMP);
        }
    }

    public static boolean hasTarget(ItemStack stack) {
        return getTargetVehicle(stack) != null;
    }
}
