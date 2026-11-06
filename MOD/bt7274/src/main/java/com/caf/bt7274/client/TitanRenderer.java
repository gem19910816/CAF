package com.caf.bt7274.client;

import com.caf.bt7274.*;
import com.caf.btmesh.*;
import com.google.gson.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.resources.ResourceLocation;

/** Plays the approved authored BB body directly. Source retargeting and IK are not applied twice. */
public final class TitanRenderer extends EntityRenderer<TitanEntity> {
    private static final Map<Integer,Double> STRIDES=new HashMap<>();
    private static final double[] DURATIONS={0,33.33333,3.26667,2.5,2.7,.15,4,1.5,1.46667,1.33333,3,5.08333,1,2.47,2.43667,2.37,2.57,.83333,2.98667,3.96667,3.33,1.4,8.23333,6.33333,3.1,3.4,3.73333,2.36667};
    private final SkinnedMeshRenderer renderer;
    private final BbMotionPlayer motion;
    private final Set<String> upperBody=new HashSet<>();
    private final Map<TitanEntity,State> states=new WeakHashMap<>();
    private static final class State {
        String key="";double changed;
        BbMotionPlayer.Frame displayed,from,target,action,advancing,departure;
        boolean hadVortex;double vortexStart,vortexEnd=-100;
        int clipId;double seconds;float transition;int baseId=-1;double baseStart;
        double blendDuration=.15,outgoingSeconds,outgoingRate,lastRate;int outgoingClip;
    }
    public record Playback(int clipId,double seconds,float transition,String layers){}
    public Playback playback(TitanEntity titan){State s=states.get(titan);return s==null?null:new Playback(s.clipId,s.seconds,s.transition,s.key);}
    public TitanRenderer(EntityRendererProvider.Context context){
        super(context);shadowRadius=1.5F;
        try{
            var resources=Minecraft.getInstance().getResourceManager();
            byte[] glb,binary;
            try(var in=resources.open(BT7274Mod.id("models/native/body.glb"))){glb=in.readAllBytes();}
            try(var in=resources.open(BT7274Mod.id("motion/body_bb.bin"))){binary=in.readAllBytes();}
            renderer=new SkinnedMeshRenderer(GltfSkin.load(glb));
            motion=new BbMotionPlayer(renderer.model,binary);
            for(var bone:motion.bones){
                int node=bone.node();
                while(node>=0){
                    String name=renderer.model.nodes[node].name;
                    if(name.equals("cube_torso")||name.equals("cube_weapon")){upperBody.add(bone.name());break;}
                    node=renderer.model.nodes[node].parent;
                }
            }
            STRIDES.clear();
            try(var in=resources.openAsReader(BT7274Mod.id("motion/bb_locomotion.json"))){
                var clips=JsonParser.parseReader(in).getAsJsonObject().getAsJsonObject("clips");
                for(var entry:clips.entrySet())if(entry.getValue().getAsJsonObject().has("stride_metres"))STRIDES.put(Integer.parseInt(entry.getKey()),entry.getValue().getAsJsonObject().get("stride_metres").getAsDouble());
            }
            NativeRenderProfiler.describe("body_lod_budgets",renderer.budgets());
        }catch(Exception e){throw new IllegalStateException("Approved BT BB body assets failed to load",e);}
    }
    /** Base locomotion ID, shared with the original footstep scheduler. */
    public static int bodyAnimationId(TitanEntity titan){
        if(titan.isDoomed())return bodyMoving(titan)?23:22;
        if(titan.isTitanCrouching())return 7;
        if(!bodyMoving(titan))return 1;
        boolean armed=titan.isAiming()||titan.getReloadTicks()>0||titan.isVortexActive()||titan.isCoreActive()||titan.getCoreChargeTicks()>0||titan.getWeaponAction().equals("fire")&&titan.level().getGameTime()-titan.getWeaponActionStartTick()<4;
        if(titan.getLocomotion()==2&&!armed)return 4;
        float forward=titan.getMoveForward(),strafe=titan.getMoveStrafe();
        if(Math.abs(strafe)>Math.abs(forward)*1.15F)return strafe>0?25:26;
        return forward<-.1F?24:2;
    }
    public static boolean bodyMoving(TitanEntity titan){
        return !titan.isDeploying()&&titan.hasGroundContact()&&titan.getLocomotion()!=0
            &&titan.getDisembarkTicks()==0&&titan.getEjectTicks()==0
            &&!Set.of("land","embark","disembark","melee","crouch_down","crouch_up","death").contains(titan.getAction());
    }
    public static float bodyCycle(TitanEntity titan,float partial){
        return BtGaitFeedback.bodyCycle(titan,partial);
    }
    @Override public ResourceLocation getTextureLocation(TitanEntity titan){return BT7274Mod.id("textures/entity/bt7274.png");}
    @Override public void render(TitanEntity titan,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light){
        var mc=Minecraft.getInstance();if(titan.hasPassenger(mc.player)&&mc.options.getCameraType().isFirstPerson()
            &&(!BtBoardingView.isActiveFor(titan)||BtBoardingView.cabinView(titan)))return;
        long renderStart=System.nanoTime(),cpuStart=NativeRenderProfiler.cpu();
        double clock=(titan.level().getGameTime()+partial)/20.0;
        double elapsed=Math.max(0,(titan.level().getGameTime()+partial-titan.getActionStartTick())/20.0);
        State s=states.computeIfAbsent(titan,t->{State n=new State();n.displayed=motion.newFrame();n.from=motion.newFrame();n.target=motion.newFrame();n.action=motion.newFrame();n.advancing=motion.newFrame();n.departure=motion.newFrame();return n;});
        if(titan.isVortexActive()&&!s.hadVortex)s.vortexStart=clock;
        if(!titan.isVortexActive()&&s.hadVortex)s.vortexEnd=clock;
        s.hadVortex=titan.isVortexActive();
        int id=bodyAnimationId(titan);if(s.baseId!=id){s.baseId=id;s.baseStart=clock;}
        float cycle=bodyCycle(titan,partial); // Advance the clock while stopped too.
        double seconds=bodyMoving(titan)?cycle*motion.duration(id):clock-s.baseStart;
        String key="base:"+id;boolean actionBody=false;
        int bodyId=switch(titan.getAction()){
            case "skyfall"->12;case "land"->11;case "embark"->19;case "disembark"->20;
            case "melee"->10;case "crouch_down"->8;case "crouch_up"->9;
            case "dash_forward"->13;case "dash_back"->14;case "dash_left"->15;case "dash_right"->16;
            case "hurt"->17;case "death"->18;case "eject"->27;default->0;
        };
        int bodyTicks=BtAnimationTimings.bodyTicks(titan.getAction());
        if(bodyId!=0&&(bodyId==12||bodyId==18||bodyId==27||elapsed<bodyTicks/20.0)){
            id=bodyId;actionBody=true;key="body:"+id+":"+titan.getActionStartTick();
            seconds=bodyId==12?elapsed:bodyTicks>0?Math.min(motion.duration(id),elapsed*20/bodyTicks*motion.duration(id)):elapsed;
        }
        motion.sample(id,seconds,s.target);
        if(!actionBody){
            boolean moving=bodyMoving(titan);
            double weaponTime=Math.max(0,(titan.level().getGameTime()+partial-titan.getWeaponActionStartTick())/20.0);
            int weaponId=titan.getReloadTicks()>0?6:titan.getWeaponAction().equals("fire")&&weaponTime<motion.duration(5)?5:0;
            if(weaponId!=0){
                double wt=weaponId==6?Math.min(motion.duration(6),weaponTime*motion.duration(6)/(BtAnimationTimings.RELOAD_TICKS/20.0)):weaponTime;
                motion.sample(weaponId,wt,s.action);
                if(moving)copyUpper(s.action,s.target);else{s.target.copyFrom(s.action);id=weaponId;seconds=wt;}
                key+="|weapon:"+weaponId;
            }
            if(titan.isVortexActive()||clock-s.vortexEnd<.2){
                double vt=titan.isVortexActive()?Math.min(.2,clock-s.vortexStart):1.2+Math.max(0,clock-s.vortexEnd);
                motion.sample(21,vt,s.action);
                copyUpper(s.action,s.target);if(!moving){id=21;seconds=vt;}key+="|vortex";
            }
            float pitch=titan.getControllingPassenger()!=null?titan.getControllingPassenger().getXRot():titan.getXRot();
            // MC aim is one shared rigid torso adjustment; it never re-solves a hand or changes a local BB curve.
            motion.aim(s.target,pitch);
        }
        if(!key.equals(s.key)){
            if(!s.key.isEmpty())s.from.copyFrom(s.displayed);else s.from.copyFrom(s.target);
            boolean locomotion=!actionBody&&s.key.startsWith("base:")&&!key.contains("|")&&!s.key.contains("|");
            s.blendDuration=locomotion?BtLocomotionBlend.duration(s.clipId,id):.15;
            s.outgoingClip=locomotion&&BtLocomotionBlend.movingClip(s.clipId)&&(id==1||id==22)?s.clipId:0;
            s.outgoingSeconds=s.seconds;s.outgoingRate=s.lastRate;
            s.key=key;s.changed=clock;
        }
        float aim=titan.getControllingPassenger()!=null?titan.getControllingPassenger().getXRot():titan.getXRot();
        float blend=BtLocomotionBlend.apply(motion,s.from,s.target,s.outgoingClip,s.outgoingSeconds,s.outgoingRate,
            clock-s.changed,s.blendDuration,aim,s.advancing,s.departure,s.displayed);
        if(BtLocomotionBlend.movingClip(id))s.lastRate=titan.getGroundSpeed()*20/STRIDES.getOrDefault(id,10.0)*motion.duration(id);
        s.clipId=id;s.seconds=seconds;s.transition=blend;
        motion.submit(s.displayed,renderer.pose);
        renderer.detailDistance=(float)mc.gameRenderer.getMainCamera().getPosition().distanceTo(titan.position());
        boolean drivingBoarding=BtBoardingView.isActiveFor(titan);
        var mainCamera=mc.gameRenderer.getMainCamera();var cameraPosition=mainCamera.getPosition();
        float inverseBodyYaw=(float)Math.toRadians(90+yaw);
        renderer.cockpitCamera=drivingBoarding?new org.joml.Vector3f(
            (float)(cameraPosition.x-titan.getX()),(float)(cameraPosition.y-titan.getY()),
            (float)(cameraPosition.z-titan.getZ())).rotateY(inverseBodyYaw):null;
        renderer.cockpitNearPlane=null;
        if(drivingBoarding){
            // Camera supplies actual aspect/near distance. Its helper uses option FOV
            // and omits the ComputeFov/roll events, so match our boarding projection.
            var near=mainCamera.getNearPlane();
            var offsets=new net.minecraft.world.phys.Vec3[]{near.getTopLeft(),near.getTopRight(),near.getBottomRight(),near.getBottomLeft()};
            var look=new org.joml.Vector3f(mainCamera.getLookVector()).normalize();
            float fovScale=(float)(Math.tan(Math.toRadians(BtBoardingView.fovDegrees(mc.options.fov().get(),partial)/2))/Math.tan(Math.toRadians(mc.options.fov().get()/2.0)));
            renderer.cockpitNearPlane=new org.joml.Vector3f[4];
            for(int i=0;i<4;i++){
                var offset=new org.joml.Vector3f((float)offsets[i].x,(float)offsets[i].y,(float)offsets[i].z);
                float depth=offset.dot(look);offset.fma(-depth,look).mul(fovScale).fma(depth,look);
                var p=cameraPosition.add(offset.x,offset.y,offset.z);
                renderer.cockpitNearPlane[i]=new org.joml.Vector3f((float)(p.x-titan.getX()),
                    (float)(p.y-titan.getY()),(float)(p.z-titan.getZ())).rotateY(inverseBodyYaw);}
        }
        renderer.cockpitCutaway=drivingBoarding?BtBoardingCutaway.eligible(titan.getAction(),elapsed*20):java.util.Set.of();
        pose.pushPose();pose.mulPose(Axis.YP.rotationDegrees(-90-yaw));renderer.renderPose(pose,buffers,light);
        TitanEffects.body(titan,pose,buffers,renderer.model,renderer.pose,partial);pose.popPose();
        TitanEffects.worldShield(titan,pose,buffers,partial);
        BtBoardingView.renderPilot(titan,pose,buffers,light,partial);
        super.render(titan,yaw,partial,pose,buffers,light);NativeRenderProfiler.record("body_pose_skin_emit",renderStart,cpuStart);
    }
    private void copyUpper(BbMotionPlayer.Frame from,BbMotionPlayer.Frame to){
        for(int i=0;i<motion.bones.length;i++)if(upperBody.contains(motion.bones[i].name())){
            System.arraycopy(from.position,i*3,to.position,i*3,3);System.arraycopy(from.euler,i*3,to.euler,i*3,3);
            System.arraycopy(from.scale,i*3,to.scale,i*3,3);to.rotation[i].set(from.rotation[i]);
        }
    }
}
