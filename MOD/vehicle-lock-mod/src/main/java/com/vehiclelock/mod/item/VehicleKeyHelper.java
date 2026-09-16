package com.vehiclelock.mod.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Helper for reading/writing our extra NBT fields on superbwarfare:vehicle_key items.
 * <p>
 * Key item NBT layout:
 *   DriverUUID: "player-uuid"          ← official superbwarfare field
 *   VLPlate: "M1A2-4821"               ← our field (license plate)
 *   VLUuid: [UUID]                      ← our field (vehicle instance UUID)
 * <p>
 * The official key item is superbwarfare:vehicle_key (registry name).
 * We don't register our own key item anymore.
 */
public class VehicleKeyHelper {

    private static final String TAG_PLATE = "VLPlate";
    private static final String TAG_UUID = "VLUuid";

    /** Check if an ItemStack is a vehicle key (official or our legacy). */
    public static boolean isKeyItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        String id = ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
        // Official superbwarfare key
        if ("superbwarfare:vehicle_key".equals(id) || "superbwarfare:creative_vehicle_key".equals(id)) return true;
        // Legacy our key (deprecated but keep for compatibility)
        if ("vehiclelock:vehicle_key".equals(id)) return true;
        return false;
    }

    /** Check if a stack matches a specific vehicle UUID (reads our VLUuid). */
    public static boolean matches(ItemStack stack, UUID vehicleUuid) {
        if (!isKeyItem(stack)) return false;
        UUID keyUuid = getVehicleUuid(stack);
        return keyUuid != null && keyUuid.equals(vehicleUuid);
    }

    /** Create a key item bound to a vehicle. Uses the official superbwarfare key. */
    public static ItemStack create(UUID vehicleUuid, String vehiclePlate) {
        // Use the official superbwarfare:vehicle_key
        Item key = ForgeRegistries.ITEMS.getValue(ResourceLocation.of("superbwarfare:vehicle_key", ':'));
        if (key == null) {
            // Fallback: if superbwarfare isn't loaded, this won't work anyway
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(key);
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_UUID, vehicleUuid);
        tag.putString(TAG_PLATE, vehiclePlate);
        // Note: DriverUUID is set by the official system when the player uses the key
        // We don't set it here — it gets set when the player right-clicks with the key
        stack.setTag(tag);
        return stack;
    }

    @Nullable
    public static UUID getVehicleUuid(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return null;
        if (tag.hasUUID(TAG_UUID)) return tag.getUUID(TAG_UUID);
        return null;
    }

    @Nullable
    public static String getVehiclePlate(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(TAG_PLATE)) return tag.getString(TAG_PLATE);
        return null;
    }

    /** Short readable type from a vehicle entity type string, e.g. "M1A2". */
    public static String shortType(String vehicleType) {
        if (vehicleType == null || !vehicleType.contains(":")) return vehicleType == null ? "?" : vehicleType;
        return vehicleType.split(":")[1];
    }
}