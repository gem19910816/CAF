package com.caf.bt7274;

import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.projectile.ProjectileUtil;

public final class TitanRocket extends Entity {
    private static final net.minecraft.resources.ResourceKey<net.minecraft.world.damagesource.DamageType> ORDNANCE_DAMAGE=net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE,BT7274Mod.id("titan_ordnance"));
    private UUID titanId;
    private UUID targetId;
    private UUID vortexHolder;
    private int flightTicks;
    public UUID getTitanOwnerUUID(){return titanId;}
    public void holdForVortex(TitanEntity titan){vortexHolder=titan.getUUID();setDeltaMovement(Vec3.ZERO);}
    public void reflectFromVortex(TitanEntity titan,Vec3 velocity){vortexHolder=null;targetId=null;titanId=titan.getUUID();flightTicks=0;setDeltaMovement(velocity);hasImpulse=true;}
    public TitanRocket(EntityType<? extends TitanRocket> type,Level level){super(type,level);setNoGravity(true);}
    // Vanilla scales render distance by the 35cm collision box, hiding missiles
    // after only 22m even though locks reach 64m and their exhaust is much larger.
    @Override public boolean shouldRenderAtSqrDistance(double distance){return distance<128*128;}
    public void launch(TitanEntity titan,Vec3 position,Vec3 velocity){titanId=titan.getUUID();setPos(position);setDeltaMovement(velocity);}
    public void launchTarget(TitanEntity titan,Vec3 position,Vec3 velocity,LivingEntity target){launch(titan,position,velocity);targetId=target.getUUID();}
    @Override protected void defineSynchedData(SynchedEntityData.Builder b){}
    @Override protected void readAdditionalSaveData(CompoundTag tag){if(tag.hasUUID("Titan"))titanId=tag.getUUID("Titan");if(tag.hasUUID("Target"))targetId=tag.getUUID("Target");flightTicks=Math.max(0,tag.getInt("FlightTicks"));}
    @Override protected void addAdditionalSaveData(CompoundTag tag){if(titanId!=null)tag.putUUID("Titan",titanId);if(targetId!=null)tag.putUUID("Target",targetId);tag.putInt("FlightTicks",flightTicks);}
    @Override public void tick(){
        super.tick();
        if(!level().isClientSide&&vortexHolder!=null){
            Entity holder=((ServerLevel)level()).getEntity(vortexHolder);
            if(holder instanceof TitanEntity titan&&titan.isAlive()&&titan.isVortexActive()){setDeltaMovement(Vec3.ZERO);return;}
            vortexHolder=null;targetId=null;setDeltaMovement(0,0,-1.5);
        }
        if(!level().isClientSide)flightTicks++;
        if(!level().isClientSide&&targetId!=null){Entity target=((ServerLevel)level()).getEntity(targetId);if(target!=null&&target.isAlive()){Vec3 desired=target.getEyePosition().subtract(position()).normalize().scale(1.5);setDeltaMovement(getDeltaMovement().lerp(desired,.22));}}
        Vec3 from=position(),to=from.add(getDeltaMovement());
        if(level().isClientSide){
            // Fill the 20Hz network trajectory with a continuous motor trail rather than isolated dots.
            Vec3 delta=position().subtract(xo,yo,zo);
            for(int i=0;i<4;i++){
                Vec3 p=position().subtract(delta.scale(i/4.0));
                level().addParticle(ParticleTypes.SMOKE,p.x,p.y,p.z,0,.015,0);
                if(i==0)level().addParticle(ParticleTypes.FLAME,p.x,p.y,p.z,0,0,0);
            }
            return;
        }
        ServerLevel sl=(ServerLevel)level();Entity source=titanId==null?null:sl.getEntity(titanId);TitanEntity titan=source instanceof TitanEntity t?t:null;
        var hit=level().clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,this));
        if(hit.getType()!=HitResult.Type.MISS)to=hit.getLocation();
        var entityHit=ProjectileUtil.getEntityHitResult(level(),this,from,to,getBoundingBox().expandTowards(getDeltaMovement()).inflate(1),e->
            e instanceof LivingEntity && e!=source && (titan==null||(!e.getUUID().equals(titan.getOwnerUUID())&&!titan.hasPassenger(e)&&!titan.isAlliedTo(e))));
        if(entityHit!=null)to=entityHit.getLocation();
        setPos(to);
        sl.sendParticles(ParticleTypes.SMOKE,getX(),getY(),getZ(),4,.08,.08,.08,0);
        sl.sendParticles(ParticleTypes.FLAME,getX(),getY(),getZ(),2,.03,.03,.03,0);
        if(hit.getType()!=HitResult.Type.MISS||entityHit!=null||flightTicks>65){
            Entity owner=titan!=null&&titan.getControllingPassenger()!=null?titan.getControllingPassenger():titan;
            var damageSource=new net.minecraft.world.damagesource.DamageSource(level().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE).getHolderOrThrow(ORDNANCE_DAMAGE),this,owner);
            if(entityHit!=null&&entityHit.getEntity() instanceof LivingEntity direct){float directDamage=direct instanceof TitanEntity?150:280;if(direct.hurt(damageSource,directDamage)&&titan!=null)titan.addCore(4);}
            sl.sendParticles(ParticleTypes.EXPLOSION_EMITTER,getX(),getY(),getZ(),1,0,0,0,0);
            sl.playSound(null,getX(),getY(),getZ(),net.minecraft.sounds.SoundEvent.createVariableRangeEvent(BT7274Mod.id("impact")),net.minecraft.sounds.SoundSource.NEUTRAL,4,1);
            for(LivingEntity v:sl.getEntitiesOfClass(LivingEntity.class,getBoundingBox().inflate(5.08),e->e!=source && (titan==null||(!e.getUUID().equals(titan.getOwnerUUID())&&!titan.hasPassenger(e)&&!titan.isAlliedTo(e))))){
                float distance=(float)v.getBoundingBox().getCenter().distanceTo(position());
                float damage=(float)(100*Math.max(0,1-distance/5.08));
                if(damage>0&&v.hurt(damageSource,damage)&&titan!=null)titan.addCore(4);
            }discard();
        }
    }
}
