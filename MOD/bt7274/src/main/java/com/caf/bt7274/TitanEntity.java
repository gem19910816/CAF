package com.caf.bt7274;

import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.projectile.ProjectileUtil;

/** Server owns movement, damage, cooldowns and every transition; client only sends pilot intent. */
public class TitanEntity extends PathfinderMob {
    public static final float MAX_SHIELD=1000F, MAX_ENERGY=100F;
    public static final int LANDING_TICKS=BtAnimationTimings.LAND_TICKS, EMBARK_TICKS=BtAnimationTimings.EMBARK_TICKS;
    public static final int DEPLOYMENT_TICKS=BtAnimationTimings.SKYFALL_TICKS+LANDING_TICKS;
    private static final ResourceKey<DamageType> CANNON_DAMAGE=ResourceKey.create(Registries.DAMAGE_TYPE,BT7274Mod.id("titan_cannon"));
    private static final EntityDataAccessor<Optional<UUID>> OWNER=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Float> SHIELD=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> ENERGY=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> CORE=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DEPLOY=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> MODE=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> SHIELD_ACTIVE=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> CORE_ACTIVE=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> LOCOMOTION=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> GROUND_SPEED=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> GROUND_CONTACT=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.BOOLEAN);
    private int lastGroundContactTick=-100,lastMovingTick=-100,lastMovingMode;

    private BlockPos landing=BlockPos.ZERO;
    private double dropHeight=70;
    private int inputButtons,inputTick=-100,lastButtons;
    private float inputStrafe,inputForward;
    private int fireCooldown,rocketCooldown,dashCooldown,meleeCooldown,voiceCooldown,hurtCooldown;
    private static final EntityDataAccessor<Integer> MAGAZINE=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> RELOAD=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> AIMING=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> ACTION=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> ACTION_START=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> CORE_CHARGE=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> SHOT_COUNT=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> VORTEX_ENERGY=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> ROCKET_LOCKS=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> SMOKE_TICKS=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<BlockPos> SMOKE_ORIGIN=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<Boolean> CROUCHING=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DOOMED=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> EJECT_TICKS=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> EJECT_CONFIRM=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> WEAPON_ACTION=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> WEAPON_START=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> MOVE_FORWARD=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> MOVE_STRAFE=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> EXIT_TICKS=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ROCKET_COOLDOWN=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> SMOKE_COOLDOWN=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> LOCK_TARGETS=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> VORTEX_CAPTURED=SynchedEntityData.defineId(TitanEntity.class,EntityDataSerializers.INT);
    private boolean intentionalDismount;
    private double nextShotTick;
    private int dashTicks,dashRegenDelay,vortexDelay,smokeCooldown,coreShots,rocketBurst,rocketBurstDelay;
    private int smokeTicks,vortexCaptured,rocketAcquireTicks;
    private float vortexCapturedDamage;
    private float currentSpeed;
    private boolean followingOwner,followRunning;
    private final TitanMovementFeedback movementFeedback=new TitanMovementFeedback();
    private Vec3 dashDirection=Vec3.ZERO,smokeOrigin=Vec3.ZERO;
    private final java.util.List<Entity> vortexProjectiles=new java.util.ArrayList<>();
    private final java.util.List<LivingEntity> rocketTargets=new java.util.ArrayList<>();
    private boolean vortexExhausted;
    public int getMagazine(){return entityData.get(MAGAZINE);}
    public int getReloadTicks(){return entityData.get(RELOAD);}
    public boolean isAiming(){return entityData.get(AIMING);}
    public boolean isVortexActive(){return isShieldActive();}
    public int getCoreChargeTicks(){return entityData.get(CORE_CHARGE);}
    public int getCoreActiveTicks(){return entityData.get(CORE_ACTIVE);}
    public String getAction(){return entityData.get(ACTION);}
    public int getActionStartTick(){return entityData.get(ACTION_START);}
    public int getShotCount(){return entityData.get(SHOT_COUNT);}
    public float getVortexEnergy(){return entityData.get(VORTEX_ENERGY);}
    public int getRocketLocks(){return entityData.get(ROCKET_LOCKS);}
    public int getSmokeTicks(){return entityData.get(SMOKE_TICKS);}
    public Vec3 getSmokeOrigin(){return Vec3.atCenterOf(entityData.get(SMOKE_ORIGIN));}
    public boolean isTitanCrouching(){return entityData.get(CROUCHING);}
    public boolean isDoomed(){return entityData.get(DOOMED);}
    // Vanilla MAX_HEALTH clamps attributes above 1024. Store hull at 1:10 and
    // convert incoming hull damage after the independent 1000-point shield.
    public float getTitanHull(){return getHealth()*10F;}
    public float getTitanMaxHull(){return getMaxHealth()*10F;}
    public int getHealthSegments(){return isDoomed()?0:(int)Math.ceil(getTitanHull()/1800);}
    public int getEjectTicks(){return entityData.get(EJECT_TICKS);}
    public int getEjectConfirmTicks(){return entityData.get(EJECT_CONFIRM);}
    public int getVortexCapturedCount(){return entityData.get(VORTEX_CAPTURED);}
    public String getWeaponAction(){return entityData.get(WEAPON_ACTION);}
    public int getWeaponActionStartTick(){return entityData.get(WEAPON_START);}
    public float getMoveForward(){return entityData.get(MOVE_FORWARD);}
    public float getMoveStrafe(){return entityData.get(MOVE_STRAFE);}
    public int getDisembarkTicks(){return entityData.get(EXIT_TICKS);}
    public int getRocketCooldownTicks(){return level().isClientSide?entityData.get(ROCKET_COOLDOWN):rocketCooldown;}
    public int getSmokeCooldownTicks(){return level().isClientSide?entityData.get(SMOKE_COOLDOWN):smokeCooldown;}
    public java.util.Map<Integer,Integer> getRocketTargetLocks(){
        java.util.Map<Integer,Integer> out=new java.util.LinkedHashMap<>();
        for(String entry:entityData.get(LOCK_TARGETS).split(",")){String[] pair=entry.split(":");if(pair.length==2)try{out.put(Integer.parseInt(pair[0]),Integer.parseInt(pair[1]));}catch(NumberFormatException ignored){}}
        return java.util.Collections.unmodifiableMap(out);
    }
    private void syncRocketLocks(){
        java.util.Map<Integer,Integer> counts=new java.util.LinkedHashMap<>();
        for(LivingEntity target:rocketTargets)if(target.isAlive())counts.merge(target.getId(),1,Integer::sum);
        entityData.set(LOCK_TARGETS,counts.entrySet().stream().map(e->e.getKey()+":"+e.getValue()).collect(java.util.stream.Collectors.joining(",")));
    }
    public boolean isIntentionalDismount(){return intentionalDismount||getEjectTicks()>0||!isAlive();}
    public boolean isAirborneDeployment(){return getDeploymentTicks()>LANDING_TICKS-BtAnimationTimings.LAND_IMPACT_TICKS;}
    public void triggerAnim(String controller,String action){
        if("weapon".equals(controller)){entityData.set(WEAPON_ACTION,action);entityData.set(WEAPON_START,(int)level().getGameTime());return;}
        entityData.set(ACTION,action);entityData.set(ACTION_START,(int)level().getGameTime());
    }
    private boolean bodyLocksControls(){
        if(isDeploying()||embarkTicks>0||getDisembarkTicks()>0||getEjectTicks()>0||!isAlive())return true;
        return switch(getAction()){case "melee","crouch_down","crouch_up","doomed_enter"->bodyActionElapsed()<BtAnimationTimings.bodyTicks(getAction());default->false;};
    }
    private int bodyActionElapsed(){return Math.max(0,(int)(level().getGameTime()-getActionStartTick()));}
    private void finishBodyAction(){int length=BtAnimationTimings.bodyTicks(getAction());if(length>0&&bodyActionElapsed()>=length&&!isDeploying()&&getEjectTicks()==0&&getDisembarkTicks()==0)triggerAnim("action","idle");}
    public boolean requestDisembark(){
        if(level().isClientSide||getControllingPassenger()==null||getDisembarkTicks()>0||getEjectTicks()>0||isDeploying())return false;
        releaseVortex();entityData.set(RELOAD,0);entityData.set(AIMING,false);entityData.set(CORE_CHARGE,0);entityData.set(CORE_ACTIVE,0);coreShots=0;
        meleeWindup=0;dashTicks=0;embarkTicks=0;rocketBurst=0;rocketTargets.clear();entityData.set(ROCKET_LOCKS,0);syncRocketLocks();
        getNavigation().stop();currentSpeed=0;clearInput();triggerAnim("weapon","idle");
        entityData.set(EXIT_TICKS,BtAnimationTimings.DISEMBARK_TICKS);triggerAnim("action","disembark");voice("voice_disembark");return true;
    }
    private void forceDismount(Entity passenger){intentionalDismount=true;try{passenger.stopRiding();}finally{intentionalDismount=false;}}
    private void forceEjectPassengers(){intentionalDismount=true;try{ejectPassengers();}finally{intentionalDismount=false;}}

    private int walkSoundTick,followTimer,orphanTicks,embarkTicks;
    private boolean warnedHealth;
    private boolean managed;
    private String pendingVoice;
    private int lastDamageTick=-1;
    private int meleeWindup;

    public TitanEntity(EntityType<? extends TitanEntity> type,Level level){
        super(type,level);setPersistenceRequired();xpReward=0;
    }
    public static AttributeSupplier.Builder createAttributes(){
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH,900).add(Attributes.MOVEMENT_SPEED,.29)
            .add(Attributes.KNOCKBACK_RESISTANCE,1).add(Attributes.FOLLOW_RANGE,40).add(Attributes.ARMOR,0)
            .add(Attributes.STEP_HEIGHT,2.032);
    }
    @Override protected void defineSynchedData(SynchedEntityData.Builder b){
        super.defineSynchedData(b);b.define(OWNER,Optional.empty());b.define(SHIELD,MAX_SHIELD);b.define(ENERGY,MAX_ENERGY);
        b.define(CORE,0F);b.define(DEPLOY,0);b.define(MODE,0);b.define(SHIELD_ACTIVE,0);b.define(CORE_ACTIVE,0);
        b.define(LOCOMOTION,0);b.define(GROUND_SPEED,0F);b.define(GROUND_CONTACT,false);
        b.define(VORTEX_CAPTURED,0);b.define(WEAPON_ACTION,"idle");b.define(WEAPON_START,0);b.define(MOVE_FORWARD,0F);b.define(MOVE_STRAFE,0F);b.define(EXIT_TICKS,0);b.define(ROCKET_COOLDOWN,0);b.define(SMOKE_COOLDOWN,0);b.define(LOCK_TARGETS,"");
        b.define(MAGAZINE,30);b.define(RELOAD,0);b.define(AIMING,false);b.define(ACTION,"idle");b.define(ACTION_START,0);b.define(CORE_CHARGE,0);b.define(SHOT_COUNT,0);b.define(VORTEX_ENERGY,1F);b.define(ROCKET_LOCKS,0);b.define(SMOKE_TICKS,0);b.define(SMOKE_ORIGIN,BlockPos.ZERO);b.define(CROUCHING,false);b.define(DOOMED,false);b.define(EJECT_TICKS,0);b.define(EJECT_CONFIRM,0);
    }
    @Override protected void registerGoals(){}
    public void setOwner(UUID id){entityData.set(OWNER,Optional.of(id));}
    public void setManaged(boolean value){managed=value;}
    public @Nullable UUID getOwnerUUID(){return entityData.get(OWNER).orElse(null);}
    public float getShield(){return entityData.get(SHIELD);}
    public float getEnergy(){return entityData.get(ENERGY);}
    public float getCoreCharge(){return entityData.get(CORE);}
    public int getMode(){return entityData.get(MODE);}
    public int getDeploymentTicks(){return entityData.get(DEPLOY);}
    public boolean isDeploying(){return getDeploymentTicks()>0;}
    public boolean isShieldActive(){return entityData.get(SHIELD_ACTIVE)>0;}
    public boolean isCoreActive(){return entityData.get(CORE_ACTIVE)>0;}
    public boolean tookDamageThisTick(){return lastDamageTick==tickCount;}
    public int getLocomotion(){return entityData.get(LOCOMOTION);}
    public float getGroundSpeed(){return entityData.get(GROUND_SPEED);}
    /** Server support plus a two-tick visual grace; never grants step permission. */
    public boolean hasGroundContact(){return entityData.get(GROUND_CONTACT);}
    public void setMode(int mode){entityData.set(MODE,Mth.clamp(mode,0,1));followingOwner=false;followRunning=false;getNavigation().stop();voice(mode==0?"voice_guard":"voice_follow");}
    public void beginDeployment(BlockPos feet){
        landing=feet.immutable();dropHeight=Math.min(70,level().getMaxBuildHeight()-8-feet.getY());
        entityData.set(DEPLOY,DEPLOYMENT_TICKS);meleeWindup=0;setPos(feet.getX()+.5,feet.getY()+dropHeight,feet.getZ()+.5);
        setNoGravity(true);noPhysics=true;triggerAnim("action","skyfall");
    }
    public void acceptInput(ServerPlayer p,float strafe,float forward,int buttons){
        if(!p.equals(getControllingPassenger()) || !p.getUUID().equals(getOwnerUUID()) || !Float.isFinite(strafe)||!Float.isFinite(forward))return;
        // Shift is an explicit server intent. Do not let vanilla's client-side
        // dismount tear down the cockpit before the approved 67-tick exit ends.
        if((buttons&4096)!=0){requestDisembark();inputTick=tickCount;return;}
        if(getDisembarkTicks()>0||getEjectTicks()>0)return;
        inputStrafe=Mth.clamp(strafe,-1,1);inputForward=Mth.clamp(forward,-1,1);inputButtons=buttons&8191;inputTick=tickCount;
    }
    public void clearInput(){inputStrafe=0;inputForward=0;inputButtons=0;lastButtons=0;meleeWindup=0;if(!level().isClientSide)releaseVortex();}
    @Override public boolean isControlledByLocalInstance(){return !level().isClientSide;}
    @Override public @Nullable LivingEntity getControllingPassenger(){
        Entity e=getFirstPassenger();return e instanceof Player p && p.getUUID().equals(getOwnerUUID())?p:null;
    }
    @Override protected boolean canAddPassenger(Entity e){return e instanceof Player p && p.getUUID().equals(getOwnerUUID()) && getPassengers().isEmpty()&&!isDeploying()&&getDisembarkTicks()==0&&getEjectTicks()==0&&isAlive();}
    @Override protected void tickRidden(Player rider,Vec3 travel){
        super.tickRidden(rider,travel);setYRot(isCoreActive()||getCoreChargeTicks()>0?Mth.approachDegrees(getYRot(),rider.getYRot(),3F):rider.getYRot());setXRot(Mth.clamp(rider.getXRot()*.35F,-20,25));
        yRotO=getYRot();yBodyRot=getYRot();yHeadRot=getYRot();
    }
    @Override protected Vec3 getRiddenInput(Player rider,Vec3 travel){
        if(bodyLocksControls())return Vec3.ZERO;
        Vec3 direction=new Vec3(inputStrafe,0,inputForward);return direction.lengthSqr()>1?direction.normalize():direction;
    }
    @Override protected float getRiddenSpeed(Player rider){
        boolean crouch=isTitanCrouching(),sprint=canPilotSprint();
        float target=(crouch?150:sprint?420:280)*.0254F/20F;
        if(inputForward==0&&inputStrafe==0)target=0;
        if(isAiming())target*=.4F;if(isCoreActive()||getCoreChargeTicks()>0)target*=.4F;
        float acceleration=(crouch?200:sprint?120:900)*.0254F/400F,deceleration=(crouch?500:sprint?400:1500)*.0254F/400F;
        currentSpeed=Mth.approach(currentSpeed,target,currentSpeed<target?acceleration:deceleration);
        return currentSpeed;
    }
    private boolean canPilotSprint(){
        // Vortex's original offhand blocks sprint. Firing/core arbitration is
        // an MC input adaptation: never shoot from an unanimated lowered pose.
        return !bodyLocksControls()&&!isTitanCrouching()&&(inputButtons&128)!=0&&inputForward>0&&(inputButtons&1)==0
            &&!isAiming()&&!isVortexActive()&&getReloadTicks()==0&&!isCoreActive()&&getCoreChargeTicks()==0;
    }
    @Override public void travel(Vec3 travel){
        if(bodyLocksControls()){setDeltaMovement(0,getDeltaMovement().y,0);if(isDeploying())return;super.travel(Vec3.ZERO);return;}
        super.travel(travel);
    }
    @Override public void move(MoverType type,Vec3 requested){
        Vec3 start=position(),momentum=getDeltaMovement();AABB startBox=getBoundingBox();
        boolean terrainMove=!level().isClientSide&&type==MoverType.SELF&&!noPhysics&&!bodyLocksControls()&&!isInWater()&&!isInLava();
        // Capture support before vanilla moves off a ledge or clears onGround.
        // A stale flag alone must never authorize another step in mid-air.
        boolean supportedBefore=terrainMove&&requested.y<=0&&momentum.y<=.1&&supported(startBox,.125);
        if(supportedBefore&&!onGround())setOnGround(true);
        super.move(type,requested);
        if(!terrainMove||requested.y>0||requested.horizontalDistanceSqr()<1.0E-5)return;
        boolean originalHorizontalCollision=horizontalCollision;
        // Descend one Titan-sized step with the complete hull instead of falling
        // for several ticks and meeting the next stair while the ground flag is off.
        // Only a body genuinely supported at this move's start may snap down;
        // cliffs, jumping, deployment and airborne wall contacts are excluded.
        Vec3 moved=position().subtract(start);
        if(supportedBefore&&moved.y<=.00001&&moved.horizontalDistanceSqr()>1.0E-5&&!supported(getBoundingBox(),.0001)){
            // Never exceed either the configured up-step or the original 2.032m
            // capability. The visual ground grace does not authorize this path.
            double descent=Math.min(maxUpStep(),2.032),probe=descent+.00001;
            Vec3 down=sweep(getBoundingBox(),new Vec3(0,-probe,0));
            AABB finish=getBoundingBox().move(down);
            if(down.y>=-descent&&down.y<-.00001&&supported(finish,.0001)
                &&level().noCollision(this,finish.deflate(1.0E-7))){
                super.move(type,down);setOnGround(true);verticalCollision=true;verticalCollisionBelow=true;
                fallDistance=0;horizontalCollision=originalHorizontalCollision;
            }
        }
        if(!horizontalCollision){
            if(supported(getBoundingBox(),.0001)){setOnGround(true);Vec3 after=getDeltaMovement();setDeltaMovement(after.x,Math.max(0,after.y),after.z);}
            return;
        }
        AABB box=getBoundingBox();
        if(momentum.y>.1||!supported(box,.125))return;
        moved=position().subtract(start);Vec3 remaining=new Vec3(requested.x-moved.x,0,requested.z-moved.z);
        if(remaining.horizontalDistanceSqr()<1.0E-5)return;
        double limit=maxUpStep()-Math.max(0,moved.y);
        if(limit<.01)return;
        AABB search=box.expandTowards(remaining.x,limit+.0001,remaining.z);
        java.util.SortedSet<Double> heights=new java.util.TreeSet<>();
        java.util.List<net.minecraft.world.phys.shapes.VoxelShape> shapes=new java.util.ArrayList<>();
        level().getBlockCollisions(this,search).forEach(shapes::add);
        for(var shape:shapes)for(AABB obstacle:shape.toAabbs()){
            double rise=obstacle.maxY-box.minY;
            if(rise>.00001&&rise<=limit+.00001)heights.add(Math.min(limit,rise+.00001));
        }
        Vec3 best=Vec3.ZERO;double bestLift=0,bestDown=0,bestProgress=0;
        for(double rise:heights){
            Vec3 up=sweep(box,new Vec3(0,rise,0));if(up.y<rise-.000001)continue;
            Vec3 across=sweep(box.move(up),remaining);
            double progress=across.dot(remaining);
            if(progress<=bestProgress+1.0E-7)continue;
            Vec3 down=sweep(box.move(up).move(across),new Vec3(0,-rise-.125,0));
            if(down.y<=-rise-.12499)continue; // Must finish on actual support.
            AABB finish=box.move(up).move(across).move(down);
            if(!level().noCollision(this,finish.deflate(1.0E-7))||!supported(finish,.0001))continue;
            best=across;bestLift=rise;bestDown=down.y;bestProgress=progress;
        }
        if(best.horizontalDistanceSqr()<.0001)return;
        // Every segment was collision-swept with the complete Titan box. Keep
        // Entity.move's block callbacks rather than teleporting through terrain.
        super.move(type,new Vec3(0,bestLift,0));super.move(type,best);super.move(type,new Vec3(0,bestDown,0));
        Vec3 total=position().subtract(start),after=getDeltaMovement();
        boolean blockedX=Math.abs(total.x-requested.x)>.0001,blockedZ=Math.abs(total.z-requested.z)>.0001;
        horizontalCollision=blockedX||blockedZ;
        boolean grounded=supported(getBoundingBox(),.0001);
        if(grounded){setOnGround(true);verticalCollision=true;verticalCollisionBelow=true;fallDistance=0;}
        setDeltaMovement(blockedX?after.x:momentum.x,grounded?Math.max(0,after.y):after.y,blockedZ?after.z:momentum.z);
    }
    private Vec3 sweep(AABB box,Vec3 movement){
        return Entity.collideBoundingBox(this,movement,box,level(),level().getEntityCollisions(this,box.expandTowards(movement)));
    }
    private boolean supported(AABB box,double distance){return sweep(box,new Vec3(0,-distance,0)).y>-distance+1.0E-7;}
    @Override public Vec3 getPassengerRidingPosition(Entity passenger){
        // Collision height changes immediately, while the pilot follows BB's
        // cockpit transition on the same ACTION_START clock as the renderer.
        double elapsed=Math.max(0,level().getGameTime()-getActionStartTick());
        if(BtBoardingMotion.active(this))return BtBoardingMotion.world(this,BtBoardingMotion.passenger(getAction(),elapsed)).add(passenger.getVehicleAttachmentPoint(this));
        return position().add(0,BtPassengerPose.height(getAction(),elapsed,isTitanCrouching()),0).add(passenger.getVehicleAttachmentPoint(this));
    }
    @Override protected net.minecraft.world.entity.EntityDimensions getDefaultDimensions(net.minecraft.world.entity.Pose pose){return net.minecraft.world.entity.EntityDimensions.scalable(3.048F,isTitanCrouching()?3.556F:5.969F).withEyeHeight(isTitanCrouching()?3.175F:4.699F);}
    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key){super.onSyncedDataUpdated(key);if(CROUCHING.equals(key))refreshDimensions();}
    @Override public Vec3 getDismountLocationForPassenger(LivingEntity passenger){
        if(getAction().equals("disembark")){
            Vec3 original=BtBoardingMotion.world(this,BtBoardingMotion.sample("disembark",BtAnimationTimings.DISEMBARK_RELEASE_TICKS).actor());
            if(level().noCollision(passenger,passenger.getBoundingBox().move(original.subtract(passenger.position()))))return original;
        }
        Vec3 side=Vec3.directionFromRotation(0,getYRot()+90).scale(3.1);
        for(int s:new int[]{1,-1})for(int y=0;y<4;y++){
            Vec3 p=position().add(side.scale(s)).add(0,y,0);
            if(level().noCollision(passenger,passenger.getBoundingBox().move(p.subtract(passenger.position()))))return p;
        }
        return position().add(0,6.5,0);
    }
    @Override protected void removePassenger(Entity e){
        super.removePassenger(e);clearInput();
        if(e instanceof LivingEntity living){living.resetFallDistance();if(!level().isClientSide&&getDisembarkTicks()==0)living.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.SLOW_FALLING,60,0,false,false));}
        if(!level().isClientSide && isAlive()&&!isIntentionalDismount()&&getDisembarkTicks()==0){triggerAnim("action","disembark");voice("voice_disembark");}
    }
    @Override protected InteractionResult mobInteract(Player player,InteractionHand hand){
        if(!player.getUUID().equals(getOwnerUUID())){
            if(!level().isClientSide)player.displayClientMessage(Component.translatable("message.bt7274.not_owner"),true);
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        if(isDeploying()||getEjectTicks()>0||getDisembarkTicks()>0)return InteractionResult.FAIL;
        if(player.getItemInHand(hand).is(BT7274Mod.BATTERY.get())){
            if(!level().isClientSide){
                if(isDoomed()){entityData.set(DOOMED,false);getAttribute(Attributes.MAX_HEALTH).setBaseValue(900);setHealth(180);}else heal(180);
                entityData.set(SHIELD,MAX_SHIELD);entityData.set(ENERGY,MAX_ENERGY);warnedHealth=false;voice("voice_battery_used");
                if(!player.hasInfiniteMaterials())player.getItemInHand(hand).shrink(1);sound("shield",2,1);
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        if(player.isShiftKeyDown()){
            if(!level().isClientSide){setMode(1-getMode());player.displayClientMessage(Component.translatable(getMode()==0?"message.bt7274.guard":"message.bt7274.follow"),true);}
        }else if(!level().isClientSide && player.startRiding(this)){
            getNavigation().stop();embarkTicks=EMBARK_TICKS;clearInput();triggerAnim("action","embark");voice("voice_embark");
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }
    @Override public void tick(){
        super.tick();
        if(level().isClientSide)return;
        boolean support=!noPhysics&&!isInWater()&&!isInLava()&&getDeltaMovement().y<=.1&&supported(getBoundingBox(),.125);
        if(support)lastGroundContactTick=tickCount;
        entityData.set(GROUND_CONTACT,!isAirborneDeployment()&&(support||tickCount-lastGroundContactTick<=2&&getDeltaMovement().y<=.1));
        if(!isAlive())return;
        finishBodyAction();
        if(getDisembarkTicks()>0){
            // Release at BB20's open hatch, then keep the complete authored
            // empty-cockpit recovery. Both events share ACTION_START after load.
            int exitElapsed=bodyActionElapsed();
            entityData.set(EXIT_TICKS,Math.max(0,BtAnimationTimings.DISEMBARK_TICKS-exitElapsed));setDeltaMovement(0,getDeltaMovement().y,0);
            if(exitElapsed>=BtAnimationTimings.DISEMBARK_RELEASE_TICKS){Entity pilot=getFirstPassenger();if(pilot!=null){
                forceDismount(pilot);
                Vec3 boost=Vec3.directionFromRotation(0,getYRot()).scale(250*.0254/20).add(0,200*.0254/20,0);
                pilot.setDeltaMovement(boost);pilot.hurtMarked=true;
            }}
            if(getDisembarkTicks()==0)triggerAnim("action","idle");
            return;
        }
        if(getEjectConfirmTicks()>0)entityData.set(EJECT_CONFIRM,getEjectConfirmTicks()-1);
        if(getEjectTicks()>0){ejectSequenceTick();return;}
        if(isDoomed()&&tickCount%4==0){ServerLevel sl=(ServerLevel)level();sl.sendParticles(ParticleTypes.LARGE_SMOKE,getX(),getY()+3,getZ(),4,1.1,1.6,1.1,.02);sl.sendParticles(ParticleTypes.ELECTRIC_SPARK,getX(),getY()+3,getZ(),3,1,1,1,.04);}
        if(managed && getOwnerUUID()!=null){
            TitanRegistry registry=TitanRegistry.get(level().getServer());
            if(registry.revoked(getUUID())||!getUUID().equals(registry.active(getOwnerUUID()))){registry.acknowledgeRevocation(getUUID());forceEjectPassengers();discard();return;}
        }
        if(getFirstPassenger() instanceof LivingEntity pilot&&!pilot.isAlive())forceEjectPassengers();
        if(fireCooldown>0)fireCooldown--;if(rocketCooldown>0)rocketCooldown--;if(dashCooldown>0)dashCooldown--;
        if(meleeCooldown>0)meleeCooldown--;if(voiceCooldown>0)voiceCooldown--;if(hurtCooldown>0)hurtCooldown--;if(embarkTicks>0)embarkTicks--;
        if(voiceCooldown==0&&pendingVoice!=null){String next=pendingVoice;pendingVoice=null;voice(next);}
        if(isDeploying()){entityData.set(LOCOMOTION,0);entityData.set(GROUND_SPEED,0F);deploymentTick();return;}
        if(meleeWindup>0&&--meleeWindup==0)applyMeleeImpact();
        if(getReloadTicks()>0){entityData.set(RELOAD,getReloadTicks()-1);if(getReloadTicks()==BtAnimationTimings.RELOAD_COMMIT_REMAINING)entityData.set(MAGAZINE,30);if(getReloadTicks()==0)triggerAnim("weapon","idle");}
        if(getCoreChargeTicks()>0){entityData.set(CORE_CHARGE,getCoreChargeTicks()-1);if(getCoreChargeTicks()==0){entityData.set(CORE_ACTIVE,BtAnimationTimings.CORE_ACTIVE_TICKS);coreShots=200;nextShotTick=tickCount;voiceCooldown=0;voice("voice_core_active");}}
        if(isVortexActive())vortexTick();
        else if(vortexDelay>0)vortexDelay--;else entityData.set(VORTEX_ENERGY,Math.min(1,getVortexEnergy()+1/60F));
        if(dashRegenDelay>0)dashRegenDelay--;else entityData.set(ENERGY,Math.min(MAX_ENERGY,getEnergy()+.6F));
        if(dashTicks>0&&!bodyLocksControls()){dashTicks--;setDeltaMovement(dashDirection.x,getDeltaMovement().y,dashDirection.z);hasImpulse=true;}
        if(smokeCooldown>0)smokeCooldown--;if(smokeTicks>0)smokeTick();
        if(rocketBurst>0&&--rocketBurstDelay<=0){for(int i=0;i<(tickCount%2==0?2:1)&&rocketBurst>0;i++)fireLockedRocket();rocketBurstDelay=1;}
        entityData.set(AIMING,(inputButtons&2)!=0&&!isCoreActive()&&getCoreChargeTicks()==0);
        boolean crouch=(inputButtons&512)!=0;if(crouch!=isTitanCrouching()){
            if(!bodyLocksControls()&&(crouch||level().noCollision(this,new AABB(getX()-1.524,getY(),getZ()-1.524,getX()+1.524,getY()+5.969,getZ()+1.524)))){entityData.set(CROUCHING,crouch);triggerAnim("action",crouch?"crouch_down":"crouch_up");}
        }
        if(entityData.get(CORE_ACTIVE)>0){entityData.set(CORE_ACTIVE,entityData.get(CORE_ACTIVE)-1);if(!isCoreActive()){voiceCooldown=0;voice("voice_core_offline");}}

        if(!isDoomed()&&hurtCooldown==0 && tickCount%5==0)entityData.set(SHIELD,Math.min(MAX_SHIELD,getShield()+25F));
        if(getControllingPassenger() instanceof ServerPlayer){
            orphanTicks=0;followingOwner=false;followRunning=false;
            if(tickCount-inputTick>6)clearInput();
            if(!bodyLocksControls())pilotTick();
        }else{clearInput();if(!bodyLocksControls())autoPilotTick();else{followingOwner=false;followRunning=false;getNavigation().stop();}}
        if(getHealth()<getMaxHealth()*.25F&&!warnedHealth){voice("voice_low_health");warnedHealth=true;}
        double dx=getX()-xo,dz=getZ()-zo;float speed=(float)Math.sqrt(dx*dx+dz*dz);
        entityData.set(GROUND_SPEED,Math.round(Math.min(speed,2F)*1000F)/1000F);
        int movingMode=(canPilotSprint()||followRunning||dashTicks>0)?2:1;
        if(speed>=.025F&&hasGroundContact()){lastMovingTick=tickCount;lastMovingMode=movingMode;}
        boolean moving=speed>=.025F||speed>.008F&&tickCount-lastMovingTick<=2;
        entityData.set(LOCOMOTION,isTitanCrouching()?3:!hasGroundContact()||!moving?0:speed>=.025F?movingMode:lastMovingMode);
        Vec3 local=new Vec3(dx,0,dz).yRot(getYRot()*Mth.DEG_TO_RAD);
        entityData.set(MOVE_FORWARD,getControllingPassenger()!=null?inputForward:(float)Mth.clamp(local.z*10,-1,1));
        entityData.set(MOVE_STRAFE,getControllingPassenger()!=null?inputStrafe:(float)Mth.clamp(local.x*10,-1,1));
        entityData.set(ROCKET_COOLDOWN,rocketCooldown);entityData.set(SMOKE_COOLDOWN,smokeCooldown);
    }
    private void deploymentTick(){
        int n=getDeploymentTicks();ServerLevel sl=(ServerLevel)level();
        double cx=landing.getX()+.5,cz=landing.getZ()+.5,approach=Math.min(8,dropHeight*.25);
        if(n>LANDING_TICKS){
            double t=Mth.clamp((DEPLOYMENT_TICKS-n-12)/48.0,0,1);
            setPos(cx,landing.getY()+approach+(dropHeight-approach)*(1-t*t),cz);setDeltaMovement(Vec3.ZERO);
            if(n==DEPLOYMENT_TICKS-12)sound("fall",5,1);
            if(tickCount%2==0){sl.sendParticles(ParticleTypes.ELECTRIC_SPARK,cx,landing.getY()+.15,cz,12,1.4,.05,1.4,.01);sl.sendParticles(ParticleTypes.FLAME,getX(),getY()+3,getZ(),5,.7,1,.7,.01);}
        }else{
            int elapsed=LANDING_TICKS-n;
            if(elapsed==0)triggerAnim("action","land");
            if(elapsed<BtAnimationTimings.LAND_IMPACT_TICKS){double u=elapsed/(double)BtAnimationTimings.LAND_IMPACT_TICKS;setPos(cx,landing.getY()+approach*(1-u*u),cz);setDeltaMovement(Vec3.ZERO);}
            if(elapsed==BtAnimationTimings.LAND_IMPACT_TICKS){
                noPhysics=false;setNoGravity(false);setPos(cx,landing.getY(),cz);setOnGround(true);sound("land",6,1);
                sl.sendParticles(ParticleTypes.EXPLOSION_EMITTER,cx,landing.getY()+.4,cz,1,0,0,0,0);sl.sendParticles(ParticleTypes.POOF,cx,landing.getY()+.3,cz,120,3,.2,3,.18);
                for(LivingEntity victim:sl.getEntitiesOfClass(LivingEntity.class,getBoundingBox().inflate(4,1,4),this::canHit)){victim.hurt(damageSources().mobAttack(this),22);victim.knockback(1.4,getX()-victim.getX(),getZ()-victim.getZ());}
            }
        }
        entityData.set(DEPLOY,n-1);
        if(n==1){noPhysics=false;setNoGravity(false);triggerAnim("action","idle");voice("voice_ready");}
    }
    private void pilotTick(){
        boolean firing=((inputButtons&1)!=0||isCoreActive())&&!isVortexActive()&&getCoreChargeTicks()==0;
        if(firing)mainFire(); // NativeAudioClient owns both firing loops and their tails.
        if((inputButtons&256)!=0&&(lastButtons&256)==0)reload();
        if((inputButtons&4)!=0&&(lastButtons&4)==0)dash();
        if((inputButtons&8)!=0&&rocketCooldown==0&&rocketBurst==0){if((lastButtons&8)==0){rocketAcquireTicks=0;rocketTargets.clear();entityData.set(ROCKET_LOCKS,0);syncRocketLocks();}acquireRocketLock();}
        if((inputButtons&8)==0&&(lastButtons&8)!=0)launchRocket();
        if((inputButtons&16)!=0&&(lastButtons&16)==0)meleeStrike();
        if((inputButtons&32)!=0&&!isVortexActive()&&!vortexExhausted)activateShield();
        if((inputButtons&32)==0){if(isVortexActive())releaseVortex();vortexExhausted=false;}
        if((inputButtons&64)!=0&&(lastButtons&64)==0)activateCore();
        if((inputButtons&1024)!=0&&(lastButtons&1024)==0)deploySmoke();
        if((inputButtons&2048)!=0&&(lastButtons&2048)==0)requestEject();
        lastButtons=inputButtons;
    }
    public boolean reload(){
        if(level().isClientSide||bodyLocksControls()||getMagazine()==30||getReloadTicks()>0||isCoreActive()||getCoreChargeTicks()>0)return false;
        // The original reload needs the same left arm as the Vortex. Release
        // the offhand before taking ownership; activateShield rejects RELOAD.
        releaseVortex();entityData.set(RELOAD,BtAnimationTimings.RELOAD_TICKS);triggerAnim("weapon",getMagazine()==0?"reload_empty":"reload");return true;
    }
    private void autoPilotTick(){
        ServerLevel sl=(ServerLevel)level();Player owner=getOwnerUUID()==null?null:sl.getPlayerByUUID(getOwnerUUID());
        if(owner==null){followingOwner=false;followRunning=false;getNavigation().stop();if(++orphanTicks>1200)discard();return;}orphanTicks=0;
        // Target searches are throttled; weapon cadence remains its own server
        // clock instead of accidentally becoming one shot per ten AI ticks.
        LivingEntity current=getTarget();if(current!=null&&canHit(current)&&hasLineOfSight(current)){
            Vec3 d=current.getEyePosition().subtract(getEyePosition());setYRot((float)(Mth.atan2(d.z,d.x)*Mth.RAD_TO_DEG)-90);yBodyRot=getYRot();yHeadRot=getYRot();
            setXRot((float)(-Mth.atan2(d.y,d.horizontalDistance())*Mth.RAD_TO_DEG));mainFire();
        }
        ++followTimer;
        if(followTimer%10==0){
            LivingEntity target=sl.getEntitiesOfClass(LivingEntity.class,getBoundingBox().inflate(26),v -> v instanceof Enemy && canHit(v)&&hasLineOfSight(v))
                .stream().min(java.util.Comparator.comparingDouble(this::distanceToSqr)).orElse(null);
            if(target!=null){
                followingOwner=false;followRunning=false;
                setTarget(target);
                getNavigation().stop();getLookControl().setLookAt(target,25,25);
                Vec3 d=target.getEyePosition().subtract(getEyePosition());setYRot((float)(Mth.atan2(d.z,d.x)*Mth.RAD_TO_DEG)-90);yBodyRot=getYRot();
                setXRot((float)(-Mth.atan2(d.y,d.horizontalDistance())*Mth.RAD_TO_DEG));mainFire();
            }else setTarget(null);
        }
        if(getTarget()!=null)return;
        double distanceSquared=distanceToSqr(owner);
        followingOwner=TitanMovementFeedback.shouldFollow(getMode()==1,followingOwner,distanceSquared);
        followRunning=TitanMovementFeedback.shouldRun(followingOwner,followRunning,distanceSquared,owner.isSprinting());
        if(!followingOwner){getNavigation().stop();return;}
        double speed=followRunning?TitanMovementFeedback.FOLLOW_RUN_SPEED:TitanMovementFeedback.FOLLOW_WALK_SPEED;
        getNavigation().setSpeedModifier(speed);
        if(followTimer%10==0||getNavigation().isDone())getNavigation().moveTo(owner,speed);
    }
    private boolean canHit(Entity e){
        return e instanceof LivingEntity l && l.isAlive() && e!=this && !hasPassenger(e) && !e.getUUID().equals(getOwnerUUID()) && !isAlliedTo(e)
            && (!(e instanceof Player p)||(!p.isCreative()&&!p.isSpectator()));
    }
    private Vec3 aim(){
        LivingEntity rider=getControllingPassenger();return rider!=null?rider.getLookAngle():getLookAngle();
    }
    private Vec3 aimPoint(){
        LivingEntity pilot=getControllingPassenger();
        if(pilot==null){
            LivingEntity target=getTarget();
            return target!=null&&target.isAlive()?target.getEyePosition():muzzlePosition().add(aim().scale(100));
        }
        Vec3 eye=pilot.getEyePosition(),end=eye.add(pilot.getLookAngle().scale(100));
        var obstruction=level().clip(new ClipContext(eye,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,this));
        if(obstruction.getType()!=HitResult.Type.MISS)end=obstruction.getLocation();
        var target=ProjectileUtil.getEntityHitResult(level(),this,eye,end,new AABB(eye,end).inflate(1),this::canHit);
        return target!=null?target.getLocation():end;
    }
    public Vec3 muzzlePosition(){
        float pitch=getControllingPassenger()!=null?getControllingPassenger().getXRot():getXRot();
        pitch=Mth.clamp(pitch,-65,65);
        Vec3 pivot=new Vec3(1.5625,4.9375,0);
        Vec3 local=new Vec3(1.75,2.69,-3.125).subtract(pivot).xRot(pitch*Mth.DEG_TO_RAD).add(pivot);
        return position().add(local.yRot((180-getYRot())*Mth.DEG_TO_RAD));
    }
    public boolean mainFire(){
        if(level().isClientSide||bodyLocksControls()||tickCount+1e-5<nextShotTick||getReloadTicks()>0||getCoreChargeTicks()>0||isVortexActive())return false;
        if(!isCoreActive()&&getMagazine()<=0){reload();return false;}
        if(isCoreActive()&&coreShots<=0)return false;
        double spacing=20.0/(isCoreActive()?20:12);nextShotTick=Math.max(nextShotTick,tickCount-1)+spacing;
        if(isCoreActive())coreShots--;else entityData.set(MAGAZINE,getMagazine()-1);
        entityData.set(SHOT_COUNT,getShotCount()+1);
        Vec3 from=muzzlePosition(),to=aimPoint();
        var block=level().clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,this));
        if(block.getType()!=HitResult.Type.MISS)to=block.getLocation();
        var hit=ProjectileUtil.getEntityHitResult(level(),this,from,to,new AABB(from,to).inflate(1),this::canHit);
        if(hit!=null){
            to=hit.getLocation();
            DamageSource cannon=new DamageSource(level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(CANNON_DAMAGE),this,
                getControllingPassenger()!=null?getControllingPassenger():this);
            float range=(float)from.distanceTo(to);float falloff=Mth.clamp((range-30.48F)/(50.8F-30.48F),0,1);
            float damage=isCoreActive()?150:hit.getEntity() instanceof TitanEntity?Mth.lerp(falloff,120,100):Mth.lerp(falloff,100,50);
            if(hit.getEntity().hurt(cannon,damage)){addCore(1.8F);if(!hit.getEntity().isAlive())voice("voice_kill");}
        }
        ServerLevel sl=(ServerLevel)level();Vec3 delta=to.subtract(from);int count=Math.max(1,Math.min(24,(int)(delta.length()*2)));
        for(int i=0;i<count;i++){Vec3 q=from.add(delta.scale(i/(double)count));sl.sendParticles(ParticleTypes.CRIT,q.x,q.y,q.z,1,0,0,0,0);}
        sl.sendParticles(ParticleTypes.FLAME,from.x,from.y,from.z,5,.1,.1,.1,.02);
        sl.sendParticles(ParticleTypes.SMOKE,to.x,to.y,to.z,4,.1,.1,.1,.02);
        if(getAction().startsWith("dash_"))triggerAnim("action","idle");triggerAnim("weapon","fire");return true;
    }
    public boolean launchRocket(){
        if(level().isClientSide||bodyLocksControls()||getReloadTicks()>0||isVortexActive()||isCoreActive()||getCoreChargeTicks()>0||rocketCooldown>0||rocketBurst>0)return false;
        if(getRocketLocks()==0||rocketTargets.isEmpty())return false;
        rocketBurst=getRocketLocks();entityData.set(ROCKET_LOCKS,0);entityData.set(LOCK_TARGETS,"");rocketBurstDelay=1;rocketCooldown=400;triggerAnim("weapon","rocket");return true;
    }
    private void acquireRocketLock(){
        if(bodyLocksControls()||getReloadTicks()>0||isVortexActive()||isCoreActive()||getCoreChargeTicks()>0)return;
        if(++rocketAcquireTicks%6!=0||getRocketLocks()>=12)return;Vec3 forward=aim();java.util.List<LivingEntity> targets=new java.util.ArrayList<>();
        level().getEntitiesOfClass(LivingEntity.class,getBoundingBox().inflate(64),v->canHit(v)&&hasLineOfSight(v)&&v.getEyePosition().subtract(getEyePosition()).normalize().dot(forward)>.85).stream()
            .sorted(java.util.Comparator.comparingDouble(this::distanceToSqr)).limit(8).forEach(targets::add);
        if(targets.isEmpty())return;LivingEntity target=targets.get(getRocketLocks()%targets.size());rocketTargets.add(target);entityData.set(ROCKET_LOCKS,getRocketLocks()+1);syncRocketLocks();
    }
    private void fireLockedRocket(){
        int index=java.lang.Math.max(0,rocketTargets.size()-rocketBurst)%rocketTargets.size();LivingEntity target=rocketTargets.get(index);Vec3 from=position().add(0,5.2,0);
        TitanRocket rocket=BT7274Mod.ROCKET.get().create(level());if(rocket!=null){rocket.launchTarget(this,from,(target.isAlive()?target.getEyePosition():aimPoint()).subtract(from).normalize().scale(1.5),target);level().addFreshEntity(rocket);sound("rocket",3,1);}rocketBurst--;
    }
    public boolean meleeStrike(){
        if(level().isClientSide||bodyLocksControls()||getReloadTicks()>0||isVortexActive()||isCoreActive()||getCoreChargeTicks()>0||meleeCooldown>0)return false;
        meleeCooldown=BtAnimationTimings.MELEE_TICKS;meleeWindup=BtAnimationTimings.MELEE_HIT_TICKS;triggerAnim("action","melee");return true;
    }
    private void applyMeleeImpact(){
        if(!isAlive()||isDeploying()||embarkTicks>0||getControllingPassenger()==null)return;
        Vec3 center=position().add(aim().multiply(1,0,1).normalize().scale(3));
        boolean hit=false;
        for(LivingEntity v:level().getEntitiesOfClass(LivingEntity.class,new AABB(center.add(-2,0,-2),center.add(2,5,2)),this::canHit)){
            v.hurt(damageSources().mobAttack(this),42);v.knockback(1.5,getX()-v.getX(),getZ()-v.getZ());addCore(4);hit=true;
        }
        if(hit)sound("impact",3,1);
    }
    public boolean dash(){
        if(level().isClientSide||bodyLocksControls()||getReloadTicks()>0||isVortexActive()||isCoreActive()||getCoreChargeTicks()>0||dashCooldown>0||getEnergy()<50)return false;
        dashCooldown=4;dashTicks=6;dashRegenDelay=4;spendEnergy(50);Vec3 d=new Vec3(inputStrafe,0,inputForward);if(d.lengthSqr()<.1)d=new Vec3(0,0,1);
        dashDirection=d.normalize().yRot(-getYRot()*Mth.DEG_TO_RAD).scale(685*.0254/20);setDeltaMovement(dashDirection.x,getDeltaMovement().y,dashDirection.z);hasImpulse=true;
        String action=Math.abs(inputStrafe)>Math.abs(inputForward)?inputStrafe>0?"dash_left":"dash_right":inputForward<0?"dash_back":"dash_forward";
        sound("dash",3,1);triggerAnim("action",action);return true;
    }
    public boolean activateShield(){
        if(level().isClientSide||bodyLocksControls()||isCoreActive()||getCoreChargeTicks()>0||isVortexActive()||getVortexEnergy()<.05F||getReloadTicks()>0)return false;
        entityData.set(VORTEX_ENERGY,getVortexEnergy()-.05F);entityData.set(SHIELD_ACTIVE,1);vortexCaptured=0;entityData.set(VORTEX_CAPTURED,0);vortexCapturedDamage=0;vortexProjectiles.clear();triggerAnim("action","shield");return true;
    }
    private void vortexTick(){
        entityData.set(VORTEX_ENERGY,Math.max(0,getVortexEnergy()-1/60F));if(getVortexEnergy()<=0){vortexExhausted=true;releaseVortex();return;}
        Vec3 center=getEyePosition().add(aim().scale(2.8));
        for(Entity projectile:level().getEntities(this,new AABB(center,center).inflate(3.81),v->(v instanceof net.minecraft.world.entity.projectile.Projectile p&&p.getOwner()!=this&&p.getOwner()!=getControllingPassenger()||v instanceof TitanRocket r&&!getUUID().equals(r.getTitanOwnerUUID()))&&!vortexProjectiles.contains(v))){
            Vec3 incoming=projectile.position().subtract(getEyePosition()).normalize();if(incoming.dot(aim())<.5)continue;
            if(vortexProjectiles.size()<64){projectile.setDeltaMovement(Vec3.ZERO);projectile.setNoGravity(true);projectile.setPos(center);vortexProjectiles.add(projectile);if(projectile instanceof TitanRocket rocket)rocket.holdForVortex(this);vortexCaptured++;entityData.set(VORTEX_CAPTURED,vortexCaptured);}
        }
        for(Entity projectile:vortexProjectiles)if(projectile.isAlive()){projectile.setPos(center);projectile.setDeltaMovement(Vec3.ZERO);}
        if(tickCount%3==0)shieldParticles();
    }
    public void releaseVortex(){if(!isVortexActive())return;entityData.set(SHIELD_ACTIVE,0);vortexDelay=20;
        for(Entity entity:vortexProjectiles)if(entity.isAlive()){if(entity instanceof TitanRocket rocket)rocket.reflectFromVortex(this,aim().scale(2.7));else{if(entity instanceof net.minecraft.world.entity.projectile.Projectile projectile)projectile.setOwner(this);entity.setNoGravity(false);entity.setDeltaMovement(aim().scale(2.7));}entity.hasImpulse=true;}
        if(vortexCapturedDamage>0){Vec3 from=getEyePosition(),to=from.add(aim().scale(100));var obstruction=level().clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,this));if(obstruction.getType()!=HitResult.Type.MISS)to=obstruction.getLocation();var hit=ProjectileUtil.getEntityHitResult(level(),this,from,to,new AABB(from,to).inflate(1),this::canHit);if(hit!=null)hit.getEntity().hurt(damageSources().mobAttack(this),Math.min(1000,vortexCapturedDamage));}
        vortexProjectiles.clear();vortexCapturedDamage=0;if(getAction().equals("shield"))triggerAnim("action","idle");
    }
    public boolean activateCore(){
        if(level().isClientSide||bodyLocksControls()||getReloadTicks()>0||isCoreActive()||getCoreChargeTicks()>0)return false;
        if(getCoreCharge()<100){voice("voice_core_not_ready");return false;}
        releaseVortex();entityData.set(CORE,0F);entityData.set(CORE_CHARGE,BtAnimationTimings.CORE_CHARGE_TICKS);entityData.set(RELOAD,0);triggerAnim("action","core");return true;
    }
    public boolean deploySmoke(){if(level().isClientSide||bodyLocksControls()||smokeCooldown>0)return false;smokeCooldown=400;smokeTicks=100;smokeOrigin=position();entityData.set(SMOKE_TICKS,100);entityData.set(SMOKE_ORIGIN,blockPosition());sound("smoke",3,1);return true;}
    private void smokeTick(){smokeTicks--;entityData.set(SMOKE_TICKS,smokeTicks);ServerLevel sl=(ServerLevel)level();sl.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE,smokeOrigin.x,smokeOrigin.y+2,smokeOrigin.z,8,3,2,3,.01);if(smokeTicks<80&&tickCount%10==0)for(LivingEntity target:level().getEntitiesOfClass(LivingEntity.class,new AABB(smokeOrigin,smokeOrigin).inflate(8.1,5,8.1),this::canHit))target.hurt(damageSources().mobAttack(this),target instanceof TitanEntity?225:22.5F);}
    public void ejectPilot(){if(getControllingPassenger() instanceof ServerPlayer pilot){forceDismount(pilot);pilot.setDeltaMovement(0,1.8,0);pilot.hurtMarked=true;sound("eject_launch",3,1);}}
    public void requestEject(){
        if(level().isClientSide||!isAlive()||isDeploying()||getControllingPassenger()==null||getDisembarkTicks()>0||getEjectTicks()>0)return;
        if(getEjectConfirmTicks()==0){entityData.set(EJECT_CONFIRM,60);return;}
        entityData.set(EJECT_CONFIRM,0);entityData.set(EJECT_TICKS,BtAnimationTimings.EJECT_TICKS);releaseVortex();entityData.set(CORE_ACTIVE,0);entityData.set(CORE_CHARGE,0);entityData.set(RELOAD,0);meleeWindup=0;dashTicks=0;clearInput();triggerAnim("action","eject");sound("eject_start",2,1);
    }
    private void ejectSequenceTick(){
        int remaining=getEjectTicks();entityData.set(EJECT_TICKS,remaining-1);ServerLevel sl=(ServerLevel)level();
        sl.sendParticles(ParticleTypes.ELECTRIC_SPARK,getX(),getY()+4,getZ(),12,1.3,.7,1.3,.05);
        if(remaining==BtAnimationTimings.EJECT_TICKS-BtAnimationTimings.EJECT_RELEASE_TICKS&&getControllingPassenger() instanceof ServerPlayer pilot){forceDismount(pilot);pilot.connection.teleport(getX(),getY()+6.8,getZ(),pilot.getYRot(),pilot.getXRot());pilot.setDeltaMovement(0,2.4,0);pilot.hurtMarked=true;pilot.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.SLOW_FALLING,200,0,false,false));sound("eject_launch",3,1);}
        if(remaining==1){level().explode(this,getX(),getY()+2,getZ(),4,Level.ExplosionInteraction.NONE);super.hurt(damageSources().genericKill(),Float.MAX_VALUE);}
    }
    public void addCore(float amount){if(!isCoreActive()&&getCoreChargeTicks()==0){float before=getCoreCharge();entityData.set(CORE,Math.min(100,before+amount));if(before<100&&getCoreCharge()>=100)voice("voice_core_ready");}}
    private void spendEnergy(float amount){entityData.set(ENERGY,Math.max(0,getEnergy()-amount));}
    private void shieldParticles(){
        ServerLevel sl=(ServerLevel)level();
        for(int i=0;i<12;i++){
            double a=i*Math.PI/6;sl.sendParticles(ParticleTypes.ELECTRIC_SPARK,getX()+Math.cos(a)*2,getY()+1.5+random.nextDouble()*3,getZ()+Math.sin(a)*2,1,0,0,0,0);
        }
    }
    @Override public boolean hurt(DamageSource source,float amount){
        if(source.is(net.minecraft.world.damagesource.DamageTypes.DROWN))return false;
        if(isDeploying()||source.getEntity()!=null&&(source.getEntity()==this||hasPassenger(source.getEntity())||source.getEntity().getUUID().equals(getOwnerUUID())))return false;
        if(!Float.isFinite(amount)||amount<=0)return false;
        if(level().isClientSide)return false;
        hurtCooldown=120;
        // Vortex is a directional projectile collector, not blanket damage reduction.
        if(isVortexActive()&&source.is(net.minecraft.tags.DamageTypeTags.IS_PROJECTILE)&&source.getSourcePosition()!=null&&source.getSourcePosition().subtract(getEyePosition()).normalize().dot(aim())>.5){vortexCaptured++;entityData.set(VORTEX_CAPTURED,vortexCaptured);vortexCapturedDamage+=amount;return false;}
        boolean environmental=isEnvironmentalContact(source);
        if(environmental&&!movementFeedback.acceptEnvironmentalDamage(tickCount))return false;
        float absorb=Math.min(getShield(),amount);entityData.set(SHIELD,getShield()-absorb);amount-=absorb;
        if(absorb>0)addCore(absorb*.08F);
        if(amount<=0){if(absorb>0)damageFeedback(environmental,true);return absorb>0;}
        if(!isDoomed()&&amount>=getTitanHull()&&!source.is(net.minecraft.world.damagesource.DamageTypes.GENERIC_KILL)){
            lastDamageTick=tickCount;
            entityData.set(DOOMED,true);entityData.set(SHIELD,0F);getAttribute(Attributes.MAX_HEALTH).setBaseValue(250);setHealth(250);meleeWindup=0;dashTicks=0;releaseVortex();if(getDisembarkTicks()==0&&getEjectTicks()==0)triggerAnim("action","doomed_enter");warnedHealth=true;voiceCooldown=0;voice("voice_doomed");return true;
        }
        boolean damaged=super.hurt(source,amount*.1F);
        if(absorb>0||damaged)damageFeedback(environmental,false);
        return absorb>0||damaged;
    }
    private static boolean isEnvironmentalContact(DamageSource source){
        if(source.getEntity()!=null||source.getDirectEntity()!=null)return false;
        return source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE)
            ||source.is(net.minecraft.world.damagesource.DamageTypes.IN_WALL)
            ||source.is(net.minecraft.world.damagesource.DamageTypes.CACTUS)
            ||source.is(net.minecraft.world.damagesource.DamageTypes.SWEET_BERRY_BUSH)
            ||source.is(net.minecraft.world.damagesource.DamageTypes.FREEZE)
            ||source.is(net.minecraft.world.damagesource.DamageTypes.HOT_FLOOR);
    }
    private void damageFeedback(boolean environmental,boolean shieldOnly){
        lastDamageTick=tickCount;
        if(!movementFeedback.playCombatFeedback(tickCount,environmental))return;
        sound(shieldOnly?"shield_hit":"hurt",.6F,1);
        if(isAlive()&&!bodyLocksControls()&&!isCoreActive()&&getReloadTicks()==0&&!getAction().equals("hurt"))triggerAnim("action","hurt");
    }
    @Override public boolean causeFallDamage(float distance,float multiplier,DamageSource source){return false;}
    @Override protected void checkFallDamage(double y,boolean ground,BlockState block,BlockPos pos){fallDistance=0;}
    @Override public boolean isPushable(){return false;}
    @Override public boolean showVehicleHealth(){return false;}
    @Override public boolean isPushedByFluid(){return false;}
    @Override public boolean removeWhenFarAway(double distance){return false;}
    @Override public void die(DamageSource source){
        if(!level().isClientSide){forceEjectPassengers();clearInput();triggerAnim("action","death");sound("destroy",5,1);voiceCooldown=0;voice("voice_destroy");}
        super.die(source);
    }
    @Override protected void tickDeath(){
        deathTime++;if(!level().isClientSide && deathTime==BtAnimationTimings.DEATH_TICKS){
            ((ServerLevel)level()).sendParticles(ParticleTypes.EXPLOSION_EMITTER,getX(),getY()+2,getZ(),2,.8,.8,.8,0);
            remove(RemovalReason.KILLED);
        }
    }
    public void sound(String event,float volume,float pitch){
        if(!level().isClientSide)level().playSound(null,getX(),getY()+3,getZ(),SoundEvent.createVariableRangeEvent(BT7274Mod.id(event)),SoundSource.NEUTRAL,volume,pitch);
    }
    public void voice(String event){
        if(level().isClientSide)return;
        if(voiceCooldown>0){if(!event.equals("voice_kill"))pendingVoice=event;return;}
        voiceCooldown=75;sound(event,3,1);
    }
    @Override public void addAdditionalSaveData(CompoundTag tag){
        super.addAdditionalSaveData(tag);if(getOwnerUUID()!=null)tag.putUUID("Owner",getOwnerUUID());
        tag.putFloat("Shield",getShield());tag.putFloat("Energy",getEnergy());tag.putFloat("Core",getCoreCharge());tag.putInt("Mode",getMode());
        tag.putInt("Deployment",getDeploymentTicks());tag.putLong("Landing",landing.asLong());tag.putDouble("DropHeight",dropHeight);
        tag.putBoolean("Managed",managed);
        tag.putInt("Magazine",getMagazine());tag.putFloat("VortexEnergy",getVortexEnergy());tag.putInt("ShotCount",getShotCount());
        tag.putBoolean("Doomed",isDoomed());
        tag.putInt("RuntimeStateVersion",1);tag.putString("BodyAction",getAction());tag.putInt("BodyElapsed",bodyActionElapsed());
        tag.putString("WeaponAction",getWeaponAction());tag.putInt("WeaponElapsed",Math.max(0,(int)(level().getGameTime()-getWeaponActionStartTick())));
        tag.putInt("ReloadRemaining",getReloadTicks());tag.putInt("EmbarkRemaining",embarkTicks);tag.putInt("ExitRemaining",getDisembarkTicks());
        tag.putInt("EjectRemaining",getEjectTicks());tag.putInt("EjectConfirm",getEjectConfirmTicks());tag.putBoolean("TitanCrouching",isTitanCrouching());
        tag.putInt("RocketCooldown",rocketCooldown);tag.putInt("SmokeCooldown",smokeCooldown);tag.putInt("DashCooldown",dashCooldown);tag.putInt("DashRegenDelay",dashRegenDelay);tag.putInt("MeleeCooldown",meleeCooldown);tag.putInt("HurtCooldown",hurtCooldown);
        tag.putInt("CoreChargeRemaining",getCoreChargeTicks());tag.putInt("CoreActiveRemaining",getCoreActiveTicks());tag.putInt("CoreShots",coreShots);tag.putInt("VortexDelay",vortexDelay);
        tag.putInt("SmokeRemaining",smokeTicks);tag.putDouble("SmokeX",smokeOrigin.x);tag.putDouble("SmokeY",smokeOrigin.y);tag.putDouble("SmokeZ",smokeOrigin.z);
        tag.putDouble("ShotDelay",Math.max(0,nextShotTick-tickCount));
    }
    @Override public void readAdditionalSaveData(CompoundTag tag){
        super.readAdditionalSaveData(tag);if(tag.hasUUID("Owner"))setOwner(tag.getUUID("Owner"));
        managed=tag.getBoolean("Managed");
        entityData.set(DOOMED,tag.getBoolean("Doomed"));if(isDoomed())getAttribute(Attributes.MAX_HEALTH).setBaseValue(250);
        entityData.set(MAGAZINE,tag.contains("Magazine")?Mth.clamp(tag.getInt("Magazine"),0,30):30);entityData.set(VORTEX_ENERGY,tag.contains("VortexEnergy")?Mth.clamp(tag.getFloat("VortexEnergy"),0,1):1F);entityData.set(SHOT_COUNT,Math.max(0,tag.getInt("ShotCount")));
        entityData.set(SHIELD,Mth.clamp(tag.getFloat("Shield"),0,MAX_SHIELD));entityData.set(ENERGY,Mth.clamp(tag.getFloat("Energy"),0,MAX_ENERGY));
        entityData.set(CORE,Mth.clamp(tag.getFloat("Core"),0,100));entityData.set(MODE,Mth.clamp(tag.getInt("Mode"),0,1));
        landing=BlockPos.of(tag.getLong("Landing"));dropHeight=Mth.clamp(Double.isFinite(tag.getDouble("DropHeight"))?tag.getDouble("DropHeight"):70,0,70);entityData.set(DEPLOY,Mth.clamp(tag.getInt("Deployment"),0,DEPLOYMENT_TICKS));
        setNoGravity(isAirborneDeployment());noPhysics=isAirborneDeployment();
        if(tag.getInt("RuntimeStateVersion")==1){
            entityData.set(ACTION,tag.getString("BodyAction"));entityData.set(ACTION_START,(int)level().getGameTime()-Math.max(0,tag.getInt("BodyElapsed")));
            entityData.set(WEAPON_ACTION,tag.getString("WeaponAction"));entityData.set(WEAPON_START,(int)level().getGameTime()-Math.max(0,tag.getInt("WeaponElapsed")));
            entityData.set(RELOAD,Mth.clamp(tag.getInt("ReloadRemaining"),0,BtAnimationTimings.RELOAD_TICKS));embarkTicks=Mth.clamp(tag.getInt("EmbarkRemaining"),0,EMBARK_TICKS);
            entityData.set(EXIT_TICKS,Mth.clamp(tag.getInt("ExitRemaining"),0,BtAnimationTimings.DISEMBARK_TICKS));entityData.set(EJECT_TICKS,Mth.clamp(tag.getInt("EjectRemaining"),0,BtAnimationTimings.EJECT_TICKS));entityData.set(EJECT_CONFIRM,Mth.clamp(tag.getInt("EjectConfirm"),0,60));entityData.set(CROUCHING,tag.getBoolean("TitanCrouching"));
            rocketCooldown=Mth.clamp(tag.getInt("RocketCooldown"),0,400);smokeCooldown=Mth.clamp(tag.getInt("SmokeCooldown"),0,400);dashCooldown=Mth.clamp(tag.getInt("DashCooldown"),0,4);dashRegenDelay=Mth.clamp(tag.getInt("DashRegenDelay"),0,4);meleeCooldown=Mth.clamp(tag.getInt("MeleeCooldown"),0,BtAnimationTimings.MELEE_TICKS);hurtCooldown=Mth.clamp(tag.getInt("HurtCooldown"),0,120);
            entityData.set(CORE_CHARGE,Mth.clamp(tag.getInt("CoreChargeRemaining"),0,BtAnimationTimings.CORE_CHARGE_TICKS));entityData.set(CORE_ACTIVE,Mth.clamp(tag.getInt("CoreActiveRemaining"),0,BtAnimationTimings.CORE_ACTIVE_TICKS));coreShots=Mth.clamp(tag.getInt("CoreShots"),0,200);vortexDelay=Mth.clamp(tag.getInt("VortexDelay"),0,20);
            smokeTicks=Mth.clamp(tag.getInt("SmokeRemaining"),0,100);smokeOrigin=new Vec3(tag.getDouble("SmokeX"),tag.getDouble("SmokeY"),tag.getDouble("SmokeZ"));entityData.set(SMOKE_TICKS,smokeTicks);entityData.set(SMOKE_ORIGIN,BlockPos.containing(smokeOrigin));nextShotTick=tickCount+Mth.clamp(tag.getDouble("ShotDelay"),0,20);
        }
        // Held projectiles / unfinished target locks cannot safely survive chunk unloading.
        entityData.set(SHIELD_ACTIVE,0);entityData.set(ROCKET_LOCKS,0);entityData.set(LOCK_TARGETS,"");rocketTargets.clear();rocketBurst=0;meleeWindup=0;dashTicks=0;currentSpeed=0;
        entityData.set(ROCKET_COOLDOWN,rocketCooldown);entityData.set(SMOKE_COOLDOWN,smokeCooldown);
    }
    @Override public void remove(RemovalReason reason){
        if(!level().isClientSide&&isVortexActive())releaseVortex();
        if(!level().isClientSide&&managed&&getOwnerUUID()!=null&&(reason==RemovalReason.KILLED||reason==RemovalReason.DISCARDED))
            TitanRegistry.get(level().getServer()).release(getOwnerUUID(),getUUID());
        super.remove(reason);
    }
    @Override public boolean canChangeDimensions(Level from,Level to){return false;}
}
