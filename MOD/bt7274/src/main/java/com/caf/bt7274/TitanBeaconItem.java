package com.caf.bt7274;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public final class TitanBeaconItem extends Item {
    public TitanBeaconItem(Properties p){super(p);}
    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand){
        ItemStack stack=player.getItemInHand(hand);
        if(level.isClientSide)return InteractionResultHolder.success(stack);
        ServerPlayer sp=(ServerPlayer)player;
        TitanEntity existing=TitanDeployment.findOwned(sp);
        TitanRegistry registry=TitanRegistry.get(sp.server);
        if(player.isShiftKeyDown() && (existing!=null||registry.active(player.getUUID())!=null)){
            if(existing!=null&&(existing.isVehicle() || existing.isDeploying())){
                player.displayClientMessage(Component.translatable("message.bt7274.busy"),true);
            }else{ registry.recall(player.getUUID());if(existing!=null){existing.voice("voice_disembark");existing.discard();}player.displayClientMessage(Component.translatable("message.bt7274.recalled"),true); }
            return InteractionResultHolder.consume(stack);
        }
        if(existing!=null){
            if(existing.level()!=level){player.displayClientMessage(Component.translatable("message.bt7274.other_dimension"),true);return InteractionResultHolder.consume(stack);}
            existing.setMode(existing.getMode()==0?1:0);
            player.displayClientMessage(Component.translatable(existing.getMode()==0?"message.bt7274.guard":"message.bt7274.follow"),true);
            return InteractionResultHolder.consume(stack);
        }
        if(registry.active(player.getUUID())!=null){player.displayClientMessage(Component.translatable("message.bt7274.unloaded"),true);return InteractionResultHolder.consume(stack);}
        var from=player.getEyePosition();var to=from.add(player.getLookAngle().scale(64));
        BlockHitResult hit=level.clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player));
        if(hit.getType()!=HitResult.Type.BLOCK){player.displayClientMessage(Component.translatable("message.bt7274.no_target"),true);return InteractionResultHolder.fail(stack);}
        BlockPos feet=level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,hit.getBlockPos());
        TitanDeployment.deploy(sp,feet);
        return InteractionResultHolder.consume(stack);
    }
    @Override public void appendHoverText(ItemStack stack,Item.TooltipContext ctx,List<Component> lines,TooltipFlag flag){
        lines.add(Component.translatable("tooltip.bt7274.beacon"));
        lines.add(Component.translatable("tooltip.bt7274.beacon_modes"));
    }
}
