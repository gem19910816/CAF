package com.vehiclelock.mod;

import com.atsuishio.superbwarfare.capability.energy.SyncedEntityEnergyStorage;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 汽油相关的常量与工具方法。
 */
public final class GasolineUtils {

    public static final String GASOLINE_BUCKET_ID = "createdieselgenerators:gasoline_bucket";
    public static final String GASOLINE_FLUID_ID = "createdieselgenerators:gasoline";

    /** 每桶汽油(1000 mB)对应的载具油量。 */
    public static final int FUEL_PER_BUCKET = 1_000_000;
    /** 每 1 mB 汽油对应的载具油量。 */
    public static final int FUEL_PER_MB = 1000;

    private GasolineUtils() {
    }

    public static boolean isGasolineBucket(ItemStack stack) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key != null && GASOLINE_BUCKET_ID.equals(key.toString());
    }

    public static Item getGasolineBucketItem() {
        return ForgeRegistries.ITEMS.getValue(new ResourceLocation("createdieselgenerators", "gasoline_bucket"));
    }

    public static Fluid getGasolineFluid() {
        return ForgeRegistries.FLUIDS.getValue(new ResourceLocation("createdieselgenerators", "gasoline"));
    }

    public static FluidStack getGasolineStack(int mb) {
        Fluid fluid = getGasolineFluid();
        return fluid == null ? FluidStack.EMPTY : new FluidStack(fluid, mb);
    }

    /** 直接写入载具的能量存储（同时更新内部缓存字段与同步字段），绕过被 Mixin 禁用的 receiveEnergy。 */
    public static void setVehicleFuel(VehicleEntity vehicle, int fuel) {
        vehicle.getCapability(ForgeCapabilities.ENERGY).ifPresent(cap -> {
            if (cap instanceof SyncedEntityEnergyStorage synced) {
                synced.setEnergy(fuel);
            }
        });
    }
}
