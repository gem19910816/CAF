package com.caf.bt7274;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;
import javax.annotation.Nullable;

public final class TitanDeployment {
    private TitanDeployment(){}
    public static @Nullable TitanEntity findOwned(ServerPlayer p){
        for(ServerLevel l:p.server.getAllLevels())
            for(var e:l.getAllEntities())if(e instanceof TitanEntity t && t.isAlive() && p.getUUID().equals(t.getOwnerUUID()))return t;
        return null;
    }
    public static boolean isValidLanding(ServerLevel l,BlockPos pos){
        if(pos.getY()<l.getMinBuildHeight()+1 || pos.getY()>l.getMaxBuildHeight()-9 || !l.getWorldBorder().isWithinBounds(pos))return false;
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++){
            BlockPos ground=pos.offset(x,-1,z);
            if(!l.hasChunkAt(ground))return false;
            BlockState s=l.getBlockState(ground);
            if(!s.isCollisionShapeFullBlock(l,ground)||!s.getFluidState().isEmpty())return false;
            for(int y=0;y<7;y++)if(!l.getBlockState(pos.offset(x,y,z)).getCollisionShape(l,pos.offset(x,y,z)).isEmpty())return false;
            if(!l.canSeeSky(pos.offset(x,6,z)))return false;
        }
        return l.noCollision(new AABB(pos.getX()+.5-1.524,pos.getY(),pos.getZ()+.5-1.524,pos.getX()+.5+1.524,pos.getY()+5.969,pos.getZ()+.5+1.524));
    }
    public static @Nullable TitanEntity deploy(ServerPlayer p,BlockPos feet){
        TitanRegistry registry=TitanRegistry.get(p.server);
        if(findOwned(p)!=null||registry.active(p.getUUID())!=null){p.displayClientMessage(Component.translatable("message.bt7274.already_active"),true);return null;}
        long now=p.server.overworld().getGameTime();
        long until=registry.cooldown(p.getUUID());
        if(until>now){p.displayClientMessage(Component.translatable("message.bt7274.cooldown",(until-now+19)/20),true);return null;}
        if(feet.distSqr(p.blockPosition())>80*80 || !isValidLanding(p.serverLevel(),feet)){
            p.displayClientMessage(Component.translatable("message.bt7274.no_landing"),true);return null;
        }
        TitanEntity titan=BT7274Mod.TITAN.get().create(p.serverLevel());
        if(titan==null)return null;
        titan.setOwner(p.getUUID());titan.setManaged(true);titan.setYRot(p.getYRot());titan.yBodyRot=p.getYRot();titan.yHeadRot=p.getYRot();
        titan.beginDeployment(feet);
        if(!p.serverLevel().addFreshEntity(titan))return null;
        registry.bind(p.getUUID(),titan.getUUID(),now+1200);
        p.getCooldowns().addCooldown(BT7274Mod.BEACON.get(),100);
        titan.sound("deploy",4F,1F);
        p.displayClientMessage(Component.translatable("message.bt7274.incoming"),false);
        return titan;
    }
}
