package com.vehiclelock.mod.block;

import com.vehiclelock.mod.ModBlocks;
import com.vehiclelock.mod.ModItems;
import com.vehiclelock.mod.block.entity.GasPumpBlockEntity;
import com.vehiclelock.mod.item.FuelNozzleItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 加油机方块（两格高，底座）：
 * - 空手右键拔下本机的加油枪；手持加油枪右键则把枪挂回去。
 * - 每台加油机同一时刻只能有一把枪在外面。
 * - 上方自动放置一个 GasPumpTopBlock 虚块，破坏底座时联动清除。
 * - HAS_NOZZLE 属性控制模型上是否显示油枪和油管。
 */
public class GasPumpBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public static final BooleanProperty HAS_NOZZLE = BooleanProperty.create("has_nozzle");

    private static final VoxelShape SHAPE = Block.box(2, 0, 3, 14, 16, 13);

    public GasPumpBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(HAS_NOZZLE, true));
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HAS_NOZZLE);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GasPumpBlockEntity(pos, state);
    }

    // ========== 两格高联动 ==========

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockPos pos = ctx.getClickedPos();
        Level level = ctx.getLevel();
        // 上半格必须可替换
        if (!level.getBlockState(pos.above()).canBeReplaced(ctx)) {
            return null;
        }
        return this.defaultBlockState()
                .setValue(FACING, ctx.getHorizontalDirection().getOpposite())
                .setValue(HAS_NOZZLE, true);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide) {
            level.setBlock(pos.above(), ModBlocks.GAS_PUMP_TOP.get().defaultBlockState(), 3);
        }
    }

    @Override
    public BlockState updateShape(BlockState state, Direction dir, BlockState neighborState, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (dir == Direction.UP && !(neighborState.getBlock() instanceof GasPumpTopBlock)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, dir, neighborState, level, pos, neighborPos);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock())) {
            // 加油机被破坏时，如果枪在外面且没插在车上，就掉一把无绑定的枪让玩家捡
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof GasPumpBlockEntity pump) {
                if (pump.hasNozzleOut()) {
                    // 枪可能正插在车上，那种情况由 tick 循环里的 detach 兜底
                    // 这里只处理"枪在玩家手里闲置"的情况无法感知 → 直接给所有持有绑定此 pos 的枪的玩家清 NBT
                    for (net.minecraft.world.entity.player.Player p : level.players()) {
                        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                            ItemStack s = p.getInventory().getItem(i);
                            if (s.getItem() instanceof FuelNozzleItem) {
                                BlockPos bound = FuelNozzleItem.getLinkedPump(s);
                                if (bound != null && bound.equals(pos)) {
                                    // 清除绑定，让枪变成"无主之枪"（玩家可留作纪念，但不能加油）
                                    s.getOrCreateTag().remove("Pump");
                                    s.getOrCreateTag().remove("TargetVehicle");
                                    if (p instanceof net.minecraft.server.level.ServerPlayer sp) {
                                        sp.displayClientMessage(Component.literal("§c[加油机] 加油机被破坏，加油枪已失效"), true);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // 联动清除上半格
            if (level.getBlockState(pos.above()).getBlock() instanceof GasPumpTopBlock) {
                level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 3);
            }
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    // ========== 加油枪交互 ==========

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof GasPumpBlockEntity pump)) return InteractionResult.PASS;

        ItemStack stack = player.getItemInHand(hand);

        // 手持加油枪右键 → 挂回加油机
        if (stack.getItem() instanceof FuelNozzleItem) {
            // 只认绑定本机的枪
            BlockPos bound = FuelNozzleItem.getLinkedPump(stack);
            if (bound == null || !bound.equals(pos)) {
                player.displayClientMessage(Component.literal("§c[加油机] 这把枪不是这台加油机的"), true);
                return InteractionResult.FAIL;
            }
            // 枪正插在车上不让挂
            if (FuelNozzleItem.hasTarget(stack)) {
                player.displayClientMessage(Component.literal("§c[加油机] 枪还插在载具上，先拔回来"), true);
                return InteractionResult.FAIL;
            }
            // 如果加油机当前显示有枪（没被拔出），说明状态错乱，拒绝
            if (!pump.hasNozzleOut()) {
                player.displayClientMessage(Component.literal("§c[加油机] 加油枪明明就在加油机上，你手里这把是哪来的？"), true);
                return InteractionResult.FAIL;
            }
            stack.shrink(1);
            pump.setNozzleOut(false);
            pump.setChanged();
            // 恢复模型上的油枪
            level.setBlock(pos, state.setValue(HAS_NOZZLE, true), 3);
            level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 1.0f, 1.0f);
            player.displayClientMessage(Component.literal("§a[加油机] 加油枪已归位"), true);
            return InteractionResult.SUCCESS;
        }

        // 空手蹲下右键 → 强制重置（用于枪被吞后的紧急恢复）
        if (stack.isEmpty() && player.isCrouching()) {
            if (pump.hasNozzleOut()) {
                pump.setNozzleOut(false);
                pump.setChanged();
                level.setBlock(pos, state.setValue(HAS_NOZZLE, true), 3);
                player.displayClientMessage(Component.literal("§e[加油机] 已强制重置加油枪状态（用于枪被吞后的恢复）"), true);
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        }

        // 空手右键 → 拔枪
        if (stack.isEmpty()) {
            if (pump.hasNozzleOut()) {
                player.displayClientMessage(Component.literal("§c[加油机] 加油枪已经被拔走了，用完记得还回来"), true);
                return InteractionResult.FAIL;
            }
            ItemStack nozzle = new ItemStack(ModItems.FUEL_NOZZLE.get());
            FuelNozzleItem.setLinkedPump(nozzle, pos);
            if (!player.addItem(nozzle)) {
                player.drop(nozzle, false);
            }
            pump.setNozzleOut(true);
            pump.setChanged();
            // 模型上隐藏油枪
            level.setBlock(pos, state.setValue(HAS_NOZZLE, false), 3);
            level.playSound(null, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 1.0f, 1.0f);
            player.displayClientMessage(Component.literal("§a[加油机] 拔下加油枪，用完右键本机挂回"), true);
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.PASS;
    }
}
