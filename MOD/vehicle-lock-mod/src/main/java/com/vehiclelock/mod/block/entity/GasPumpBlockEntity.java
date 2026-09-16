package com.vehiclelock.mod.block.entity;

import com.vehiclelock.mod.GasolineUtils;
import com.vehiclelock.mod.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;

/**
 * 加油机方块实体：本身不存油，作为「媒介」从正下方方块的 FLUID_HANDLER 里抽汽油。
 * 同时记录本机的加油枪是否被拔出，保证同一时刻只有一把枪在外面。
 */
public class GasPumpBlockEntity extends BlockEntity {

    private static final String TAG_NOZZLE_OUT = "NozzleOut";

    /** true = 加油枪已被拔走。 */
    private boolean nozzleOut = false;

    public GasPumpBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.GAS_PUMP_BE.get(), pos, state);
    }

    public boolean hasNozzleOut() {
        return nozzleOut;
    }

    public void setNozzleOut(boolean out) {
        this.nozzleOut = out;
    }

    /**
     * 从正下方方块的 FLUID_HANDLER 里抽最多 {@code mb} mB 汽油。
     */
    public int pullGasoline(int mb) {
        if (this.level == null) return 0;

        BlockEntity below = this.level.getBlockEntity(this.worldPosition.below());
        if (below == null) return 0;

        FluidStack resource = GasolineUtils.getGasolineStack(mb);
        if (resource.isEmpty()) return 0;

        FluidStack drained = below.getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.UP)
                .map(h -> h.drain(resource, IFluidHandler.FluidAction.EXECUTE))
                .orElse(FluidStack.EMPTY);

        return drained.isEmpty() ? 0 : drained.getAmount();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putBoolean(TAG_NOZZLE_OUT, this.nozzleOut);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.nozzleOut = tag.getBoolean(TAG_NOZZLE_OUT);
    }
}
