package com.fungalmoon;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 简单记录月球上已发现的 Proto Hivemind 位置（用于袭击频率计算）。
 * 每 60 秒把月球维度里的 hivemind 扫描一次，结果存进内存即可——
 * 不需要持久化到存档，重开服后重新扫描就行。
 */
public final class HivemindTracker {

    private static final List<BlockPos> HIVEMINDS = new ArrayList<>();

    private HivemindTracker() {}

    public static void set(List<BlockPos> positions) {
        synchronized (HIVEMINDS) {
            HIVEMINDS.clear();
            HIVEMINDS.addAll(positions);
        }
    }

    public static int count() {
        synchronized (HIVEMINDS) {
            return HIVEMINDS.size();
        }
    }

    public static List<BlockPos> snapshot() {
        synchronized (HIVEMINDS) {
            return List.copyOf(HIVEMINDS);
        }
    }

    // 存档持久化（可选，防止重启后立刻丢失威胁度）
    public static CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (BlockPos p : snapshot()) {
            CompoundTag e = new CompoundTag();
            e.putInt("x", p.getX());
            e.putInt("y", p.getY());
            e.putInt("z", p.getZ());
            list.add(e);
        }
        tag.put("hiveminds", list);
        return tag;
    }

    public static void load(CompoundTag tag) {
        List<BlockPos> out = new ArrayList<>();
        ListTag list = tag.getList("hiveminds", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            out.add(new BlockPos(e.getInt("x"), e.getInt("y"), e.getInt("z")));
        }
        set(out);
    }
}
