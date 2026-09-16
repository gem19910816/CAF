package com.vehiclelock.mod.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LevelAccessor;

/**
 * 加油机的上半格虚块：仅提供碰撞和右键转发，不可获取。
 */
public class GasPumpTopBlock extends Block {

    private static final VoxelShape SHAPE = Block.box(2, 0, 3, 14, 16, 13);

    public GasPumpTopBlock(Properties properties) {
        super(properties);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        // 转发到底座
        BlockPos basePos = pos.below();
        BlockState base = level.getBlockState(basePos);
        if (base.getBlock() instanceof GasPumpBlock pumpBlock) {
            return pumpBlock.use(base, level, basePos, player, hand, hit.withPosition(basePos));
        }
        return InteractionResult.PASS;
    }

    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getBlockState(pos.below()).getBlock() instanceof GasPumpBlock;
    }

    @Override
    public BlockState updateShape(BlockState state, net.minecraft.core.Direction dir, BlockState neighborState,
                                   LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (dir == net.minecraft.core.Direction.DOWN && !(neighborState.getBlock() instanceof GasPumpBlock)) {
            return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, dir, neighborState, level, pos, neighborPos);
    }

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // 挖上半格时联动破坏底座（掉底座方块）
        BlockPos below = pos.below();
        BlockState belowState = level.getBlockState(below);
        if (belowState.getBlock() instanceof GasPumpBlock) {
            // 创造模式不触发掉落；生存模式让底座自己走 loot
            level.destroyBlock(below, !player.getAbilities().instabuild, player);
        }
        super.playerWillDestroy(level, pos, state, player);
    }
}
