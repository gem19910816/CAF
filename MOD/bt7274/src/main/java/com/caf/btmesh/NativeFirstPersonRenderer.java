package com.caf.btmesh;

import com.caf.bt7274.BT7274Mod;
import com.caf.bt7274.BtAnimationTimings;
import com.caf.bt7274.TitanEntity;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.caf.bt7274.client.TitanEffects;
import com.caf.bt7274.client.BtBoardingView;
import com.caf.bt7274.client.BtGaitFeedback;
import com.caf.bt7274.client.TitanRenderer;

/** Authored cube XO16 viewmodel on the original rig and CAMERA attachment.
 * All clocks advance on simulation ticks; render interpolation never accumulates time.
 */
@EventBusSubscriber(modid=BT7274Mod.ID,value=Dist.CLIENT)
public final class NativeFirstPersonRenderer {
    private static SkinnedMeshRenderer renderer;
    private static NativeMotionPlayer motion;
    private static NativeMotionPlayer cameraMotion;
    private static GltfSkin.Pose cameraPose;
    private static CubePoseAdapter cubeRig;
    private static NativeMotionPlayer.FirstPersonState displayedState;
    private static NativeMotionPlayer.PoseSnapshot transitionFrom;
    private static double transitionTick;
    private static int titanId=-1,mountTick,lastShotCount,shotTick=-10000,sprintExitTick=-10000,sprintStartTick;
    private static int cockpitReadyTick=-10000;
    private static int vortexStartTick,vortexReleaseTick=-10000;
    private static boolean previousSprint,reloading,emptyReload,vortexActive;
    private static float previousAds,ads;
    private static final Matrix4f sprintCameraReferenceInverse=new Matrix4f();
    private static final Matrix4f cameraDelta=new Matrix4f(),cameraBasisInverse=new Matrix4f();
    private static final Vector3f cameraAngles=new Vector3f();
    private static final Matrix4f camera=new Matrix4f(),view=new Matrix4f();
    // Source attachment camera basis is X forward, Y left, Z up.
    // Minecraft hand renderer uses X right, Y up, -Z forward.
    private static final Matrix4f SOURCE_CAMERA_TO_VIEW=new Matrix4f().set(
        0,0,-1,0, -1,0,0,0, 0,1,0,0, 0,0,0,1);
    private NativeFirstPersonRenderer(){}

    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();
        if(mc.isPaused())return;
        if(mc.player==null||!(mc.player.getVehicle() instanceof TitanEntity titan)){
            titanId=-1;previousAds=ads=0;previousSprint=false;reloading=false;vortexActive=false;vortexReleaseTick=-10000;displayedState=null;transitionFrom=null;return;
        }
        if(titanId!=titan.getId()){
            titanId=titan.getId();mountTick=titan.tickCount;lastShotCount=titan.getShotCount();shotTick=-10000;
            cockpitReadyTick=mountTick;
            previousAds=ads=0;previousSprint=false;reloading=false;emptyReload=false;vortexActive=false;vortexReleaseTick=-10000;sprintExitTick=-10000;displayedState=null;transitionFrom=null;

        }
        if(titan.getAction().equals("embark"))cockpitReadyTick=titan.tickCount+Math.max(0,BtAnimationTimings.EMBARK_TICKS-(int)(titan.level().getGameTime()-titan.getActionStartTick()))+4;
        previousAds=ads;float target=titan.isAiming()?1:0;
        // Original weapon zoom_time_in/out = 0.3 seconds = six MC simulation ticks.
        ads=target>ads?Math.min(target,ads+1/6f):Math.max(target,ads-1/6f);
        if(lastShotCount!=titan.getShotCount()){lastShotCount=titan.getShotCount();shotTick=titan.tickCount;}
        if(titan.getReloadTicks()>0&&!reloading)emptyReload=titan.getMagazine()==0;
        reloading=titan.getReloadTicks()>0;
        boolean sprint=TitanRenderer.bodyMoving(titan)&&TitanRenderer.bodyAnimationId(titan)==4;
        if(!previousSprint&&sprint)sprintStartTick=titan.tickCount;
        if(previousSprint&&!sprint)sprintExitTick=titan.tickCount;
        previousSprint=sprint;
        if(titan.isVortexActive()!=vortexActive){vortexActive=titan.isVortexActive();if(vortexActive)vortexStartTick=titan.tickCount;else vortexReleaseTick=titan.tickCount;}
    }

    private static void load(){
        if(renderer!=null)return;
        try{
            var resources=Minecraft.getInstance().getResourceManager();byte[] bytes;JsonObject metadata;
            try(var in=resources.open(BT7274Mod.id("models/native/fp.glb"))){bytes=in.readAllBytes();}
            try(var in=resources.openAsReader(BT7274Mod.id("motion/fp.json"))){metadata=JsonParser.parseReader(in).getAsJsonObject();}
            renderer=new SkinnedMeshRenderer(GltfSkin.load(bytes));motion=new NativeMotionPlayer(renderer.model,metadata);
            cameraMotion=new NativeMotionPlayer(renderer.model,metadata);cameraPose=renderer.model.createPose();
            cameraMotion.sampleFirstPerson(NativeMotionPlayer.FirstPersonState.SPRINT,0,0,0,100,cameraPose);
            if(!cameraMotion.attachment(cameraPose,"CAMERA",sprintCameraReferenceInverse))throw new IllegalStateException("Sprint CAMERA absent");
            sprintCameraReferenceInverse.invert();cameraBasisInverse.set(SOURCE_CAMERA_TO_VIEW).invert();
            NativeRenderProfiler.describe("first_person_budgets",renderer.budgets());
            cubeRig=metadata.has("cube_rig")?new CubePoseAdapter(renderer.model,metadata.getAsJsonObject("cube_rig")):null;
        }catch(Exception error){throw new IllegalStateException("Cube XO16 first-person assets failed",error);}
    }

    // TitanClient already cancels the vanilla hands. Receive canceled events to
    // render the original model exactly once, on the main-hand pass.
    @SubscribeEvent(receiveCanceled=true) public static void render(RenderHandEvent event){
        var mc=Minecraft.getInstance();
        if(mc.player==null||!(mc.player.getVehicle() instanceof TitanEntity titan)||!mc.options.getCameraType().isFirstPerson())return;
        event.setCanceled(true);if(event.getHand()!=InteractionHand.MAIN_HAND)return;load();
        if(BtBoardingView.hideWeapon(titan))return;
        float partial=event.getPartialTick();double ticks=titan.tickCount+partial;
        double idle=ticks/20.0,shot=(ticks-shotTick)/20.0,elapsed=(ticks-mountTick)/20.0;
        float zoom=previousAds+(ads-previousAds)*partial;
        NativeMotionPlayer.FirstPersonState state=NativeMotionPlayer.FirstPersonState.IDLE;
        double stateTime=elapsed;
        double actionElapsed=(titan.level().getGameTime()+partial-titan.getActionStartTick())/20.0;
        boolean boarding=titan.getAction().equals("embark")&&actionElapsed<BtAnimationTimings.EMBARK_TICKS/20.0;
        boolean leaving=titan.getDisembarkTicks()>0||titan.getEjectTicks()>0;
        if(boarding){
            state=NativeMotionPlayer.FirstPersonState.HOLSTER;stateTime=motion.sequenceDuration("holster_seq",java.util.Map.of());
        }
        else if(leaving){state=NativeMotionPlayer.FirstPersonState.HOLSTER;stateTime=Math.min(motion.sequenceDuration("holster_seq",java.util.Map.of()),Math.max(0,actionElapsed));}
        else if(titan.getReloadTicks()>0){
            state=emptyReload?NativeMotionPlayer.FirstPersonState.RELOAD_EMPTY:NativeMotionPlayer.FirstPersonState.RELOAD;
            // Both FP and foley use the same continuous source clock. Its
            // original fill-ammo pose reaches the approved BB contact at 58 ticks.
            double cycle=BtActionClock.reloadCycle(titan,partial);
            stateTime=2.6*cycle;
        }
        else if(titan.getAction().equals("melee")&&actionElapsed>=0&&actionElapsed<BtAnimationTimings.MELEE_TICKS/20.0){
            state=NativeMotionPlayer.FirstPersonState.MELEE;
            // Keep the first native AE_MELEE_ATTACK (cycle 10/24) exactly on
            // the BB contact / server hit tick, rather than stretching blindly.
            double cycle=BtActionClock.meleeCycle(actionElapsed);
            stateTime=Math.min(1,cycle)*motion.sequenceDuration("melee_seq",java.util.Map.of());
        }
        else if(titan.isCoreActive()||(titan.getWeaponAction().equals("fire")&&shot>=0&&shot<.3)){state=NativeMotionPlayer.FirstPersonState.IDLE;}
        else if(previousSprint){state=NativeMotionPlayer.FirstPersonState.SPRINT;stateTime=sprintSeconds(BtGaitFeedback.frame(titan,partial));}
        else if(!vortexActive&&ticks-sprintExitTick<10.01){state=NativeMotionPlayer.FirstPersonState.SPRINT_RAISE;stateTime=(ticks-sprintExitTick)/20.0;}
        else if(ticks-cockpitReadyTick<motion.sequenceDuration("draw_seq",java.util.Map.of())*20){state=NativeMotionPlayer.FirstPersonState.DRAW;stateTime=Math.max(0,(ticks-cockpitReadyTick)/20.0);}
        if(displayedState!=state){
            transitionFrom=displayedState==null?null:motion.snapshotCurrent();transitionTick=ticks;displayedState=state;
        }
        float blend=(float)Math.max(0,Math.min(1,(ticks-transitionTick)/(state==NativeMotionPlayer.FirstPersonState.SPRINT?4:2)));
        if(transitionFrom!=null&&blend<1)motion.sampleFirstPersonFromSnapshot(transitionFrom,state,stateTime,zoom,idle,shot,blend,renderer.pose);
        else motion.sampleFirstPerson(state,stateTime,zoom,idle,shot,renderer.pose);
        if(vortexActive&&!boarding&&!leaving){
            double elapsedVortex=Math.max(0,(ticks-vortexStartTick)/20.0),draw=motion.sequenceDuration("vortex_fp_draw",java.util.Map.of());
            motion.applyFirstPersonOffhand(elapsedVortex<draw?"vortex_fp_draw":"vortex_fp_hold",elapsedVortex<draw?elapsedVortex:elapsedVortex-draw,(float)Math.min(1,elapsedVortex/.1),renderer.pose);
        }else if(!boarding&&!leaving){
            double elapsedVortex=(ticks-vortexReleaseTick)/20.0,duration=motion.sequenceDuration("vortex_fp_holster",java.util.Map.of());
            if(elapsedVortex>=0&&elapsedVortex<duration){float weight=(float)Math.min(1,(duration-elapsedVortex)/.1);motion.applyFirstPersonOffhand("vortex_fp_holster",elapsedVortex,weight,renderer.pose);}
        }
        if(cubeRig!=null)cubeRig.apply(renderer.pose);
        if(!motion.attachment(renderer.pose,"CAMERA",camera))throw new IllegalStateException("Original XO16 CAMERA attachment absent");
        // Inverting the exported camera removes its wrapper scale as well. Restore
        // Source-inch -> world-meter conversion exactly once, no hand-tuned offset.
        view.set(SOURCE_CAMERA_TO_VIEW).scale(.0254f).mul(camera.invert());
        var stack=event.getPoseStack();stack.pushPose();
        if(!boarding&&!leaving)applyEngineBob(stack,BtGaitFeedback.frame(titan,partial),zoom);
        stack.mulPose(view);
        renderer.renderPose(stack,event.getMultiBufferSource(),event.getPackedLight());
        TitanEffects.firstPerson(titan,stack,event.getMultiBufferSource(),renderer.model,renderer.pose,partial);
        stack.popPose();
    }

    /** One source left/right pair maps to one original sprint loop. The keys
     * stay intact; distance and source foot markers now own their playback rate. */
    private static double sprintSeconds(BtGaitFeedback.Sample gait){
        return gait.stepPhase()*.5*motion.sequenceDuration("sprint_seq",java.util.Map.of());
    }

    /** Original XO16 engine amplitudes, driven by the same contact phase as
     * foot audio. Weight only fades amplitude; it never changes the frequency. */
    private static void applyEngineBob(PoseStack stack,BtGaitFeedback.Sample gait,float zoom){
        if(gait.weight()<=0)return;
        // Short server ground grace keeps visual sway continuous over stair
        // interpolation; impact is already zero without real hull contact.
        float weight=gait.weight()*(1-.8f*zoom);
        double phase=Math.PI*gait.stepPhase(),side=Math.sin(phase),vertical=-Math.cos(2*phase);
        stack.translate(.1*.0254*side*weight,.6*.0254*vertical*weight-.008*gait.impact()*(1-.8f*zoom),0);
        stack.mulPose(Axis.XP.rotationDegrees((float)(.75*vertical*weight)));
        stack.mulPose(Axis.YP.rotationDegrees((float)(-1.7*side*weight)));
        stack.mulPose(Axis.ZP.rotationDegrees((float)(-1.2*side*weight)));
    }

    /** The original sprint CAMERA curve used to be inverted out of the weapon
     * render and discarded. Apply its relative rotation to the MC camera too.
     * A separate player prevents camera callbacks corrupting pose crossfades. */
    @SubscribeEvent public static void camera(ViewportEvent.ComputeCameraAngles event){
        var mc=Minecraft.getInstance();
        if(mc.player==null||event.getCamera().getEntity()!=mc.player||!(mc.player.getVehicle() instanceof TitanEntity titan)
            ||!mc.options.getCameraType().isFirstPerson())return;
        load();float partial=(float)event.getPartialTick();
        double ticks=titan.tickCount+partial;
        var gait=BtGaitFeedback.frame(titan,partial);
        if(gait.weight()<=0||BtBoardingView.isActiveFor(titan))return;
        // The short contact accent peaks on exactly the marker that requests
        // step audio. Both perspectives consume this frame's immutable sample.
        float zoom=previousAds+(ads-previousAds)*partial;
        float impact=gait.impact()*(1-.8f*zoom);
        event.setPitch(event.getPitch()+.35f*impact);
        event.setRoll(event.getRoll()+(gait.left()?1:-1)*.18f*impact);
        boolean sprintTail=!previousSprint&&ticks-sprintExitTick<=.36*20;
        if(!previousSprint&&!sprintTail)return;
        double seconds=sprintSeconds(gait);
        cameraMotion.sampleFirstPerson(NativeMotionPlayer.FirstPersonState.SPRINT,seconds,0,0,100,cameraPose);
        cameraMotion.attachment(cameraPose,"CAMERA",cameraDelta);
        sprintCameraReferenceInverse.mul(cameraDelta,cameraDelta);
        SOURCE_CAMERA_TO_VIEW.mul(cameraDelta,cameraDelta);cameraDelta.mul(cameraBasisInverse);
        cameraDelta.getUnnormalizedRotation(new Quaternionf()).normalize().getEulerAnglesXYZ(cameraAngles);
        float entry=(float)Math.max(0,Math.min(1,(ticks-sprintStartTick)/4));
        float exit=(float)Math.max(0,Math.min(1,1-(ticks-sprintExitTick)/(.36*20)));
        // Walking after releasing sprint still has full gait weight. Fade only
        // the outgoing sprint CAMERA layer instead of cutting it at tail end.
        float sprintFade=previousSprint||!gait.moving()?1:exit;
        float degrees=(float)(180/Math.PI)*gait.weight()*entry*sprintFade;
        event.setPitch(event.getPitch()+cameraAngles.x*degrees);
        event.setYaw(event.getYaw()-cameraAngles.y*degrees);
        event.setRoll(event.getRoll()+cameraAngles.z*degrees);
    }
}
