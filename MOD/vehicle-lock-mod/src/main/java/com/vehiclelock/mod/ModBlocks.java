package com.vehiclelock.mod;

import com.vehiclelock.mod.block.GasPumpBlock;
import com.vehiclelock.mod.block.GasPumpTopBlock;
import com.vehiclelock.mod.block.entity.GasPumpBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, VehicleLockMod.MOD_ID);

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, VehicleLockMod.MOD_ID);

    public static final RegistryObject<Block> GAS_PUMP =
            BLOCKS.register("gas_pump", () -> new GasPumpBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .strength(3.0f, 6.0f)
                            .noOcclusion()));

    /** 加油机上半格虚块：无掉落、无模型、仅碰撞。 */
    public static final RegistryObject<Block> GAS_PUMP_TOP =
            BLOCKS.register("gas_pump_top", () -> new GasPumpTopBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .strength(3.0f, 6.0f)
                            .noOcclusion()
                            .noLootTable()));

    public static final RegistryObject<BlockEntityType<GasPumpBlockEntity>> GAS_PUMP_BE =
            BLOCK_ENTITIES.register("gas_pump",
                    () -> BlockEntityType.Builder.of(GasPumpBlockEntity::new, GAS_PUMP.get()).build(null));
}
