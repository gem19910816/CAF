package com.vehiclelock.mod.mixin;

import com.atsuishio.superbwarfare.capability.energy.VehicleEnergyStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让载具不再接受电力补能，汽油成为唯一燃料。
 * <p>
 * {@code canReceive} 是 Forge 的 {@code EnergyStorage} 定义的方法，不属于 Minecraft
 * 混淆映射，所以这里用 {@code remap = false}。
 * <p>
 * 影响：充电站 {@code ChargingStationBlockEntity.chargeEntity} 会先检查
 * {@code cap.canReceive()}，返回 false 即跳过；载具背包里的电池包补能走
 * {@code setEnergy() -> receiveEnergy()}，同样被挡下。加油/抽油则直接写存储，不受影响。
 */
@Mixin(value = VehicleEnergyStorage.class, remap = false)
public abstract class VehicleEnergyStorageMixin {

    @Inject(method = "canReceive", at = @At("HEAD"), cancellable = true, remap = false)
    private void vehiclelock$blockElectricCharge(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
