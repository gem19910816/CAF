package com.vehiclelock.mod.handler;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * World-saved registry: plate number → claimed vehicle info.
 * Lets admins look up who owns which vehicle.
 */
public class VehicleLockData extends SavedData {

    public static final String DATA_NAME = "vehiclelock_data";

    public record Entry(UUID vehicleUuid, UUID ownerUuid, String type, long claimTime) {}

    private final Map<String, Entry> plates = new LinkedHashMap<>();

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<String, Entry> e : plates.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putString("plate", e.getKey());
            t.putUUID("vehicle", e.getValue().vehicleUuid());
            t.putUUID("owner", e.getValue().ownerUuid());
            t.putString("type", e.getValue().type());
            t.putLong("time", e.getValue().claimTime());
            list.add(t);
        }
        tag.put("plates", list);
        return tag;
    }

    public static VehicleLockData load(CompoundTag tag) {
        VehicleLockData data = new VehicleLockData();
        ListTag list = tag.getList("plates", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            String plate = t.getString("plate");
            UUID vehicle = t.hasUUID("vehicle") ? t.getUUID("vehicle") : null;
            UUID owner = t.hasUUID("owner") ? t.getUUID("owner") : null;
            if (plate.isEmpty() || vehicle == null) continue;
            data.plates.put(plate, new Entry(vehicle, owner, t.getString("type"), t.getLong("time")));
        }
        data.setDirty();
        return data;
    }

    public static VehicleLockData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(VehicleLockData::load, VehicleLockData::new, DATA_NAME);
    }

    /** Register a newly claimed vehicle. */
    public void register(String plate, UUID vehicleUuid, UUID ownerUuid, String type) {
        plates.put(plate, new Entry(vehicleUuid, ownerUuid, type, System.currentTimeMillis()));
        setDirty();
    }

    @Nullable
    public Entry lookup(String plate) {
        return plates.get(plate);
    }

    public List<Map.Entry<String, Entry>> entries() {
        return new ArrayList<>(plates.entrySet());
    }

    public void remove(String plate) {
        plates.remove(plate);
        setDirty();
    }
}