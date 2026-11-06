package com.caf.bt7274.client;

import com.caf.bt7274.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.*;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import org.joml.Vector3f;
import org.joml.Quaternionf;

/** Source paired actor/VDU camera, rendered with authored Minecraft skin cubes. */
@EventBusSubscriber(modid=BT7274Mod.ID,value=Dist.CLIENT)
public final class BtBoardingView {
    private static ArmorStand camera;
    private static TitanEntity activeTitan;
    private static CameraType previous=CameraType.FIRST_PERSON;
    private static float roll;
    private static int boardingStart=-1;
    private static boolean boardingHandoff;
    private static final int HANDOFF_TICKS=4;
    private static final class PilotCamera extends ArmorStand {
        PilotCamera(net.minecraft.world.level.Level level){super(level,0,0,0);}
        @Override public net.minecraft.world.entity.EntityDimensions getDefaultDimensions(net.minecraft.world.entity.Pose pose){
            var player=Minecraft.getInstance().player;
            return super.getDefaultDimensions(pose).withEyeHeight(player==null?1.62F:player.getEyeHeight());
        }
    }
    private BtBoardingView(){}
    public static boolean active(){return activeTitan!=null;}
    public static boolean isActiveFor(TitanEntity titan){return activeTitan==titan;}
    public static boolean cabinView(TitanEntity titan){return isActiveFor(titan)&&boardingStart>=0
        &&(titan.getAction().equals("embark")||boardingHandoff)
        &&titan.level().getGameTime()-boardingStart>=60;}
    public static float rollDegrees(){return roll;}
    private static double elapsed(TitanEntity t,float partial){return t.level().getGameTime()+partial-t.getActionStartTick();}
    public static boolean hideWeapon(TitanEntity t){return isActiveFor(t);}
    private static boolean pilotVisible(TitanEntity t,double ticks){
        return !t.getPassengers().isEmpty()&&BtBoardingMotion.active(t)&&ticks>=0&&ticks<4+20*BtBoardingMotion.duration(t.getAction());
    }
    public static void renderPilot(TitanEntity titan,PoseStack stack,MultiBufferSource buffers,int light,float partial){
        double age=elapsed(titan,partial);if(!pilotVisible(titan,age))return;
        var player=titan.getOwnerUUID()==null?null:titan.level().getPlayerByUUID(titan.getOwnerUUID());
        if(!(player instanceof AbstractClientPlayer client))return;
        // Exit uses the original player's first-person camera; do not draw the
        // full head directly across its lens. External observers see every limb.
        var mc=Minecraft.getInstance();if(titan.getAction().equals("disembark")&&activeTitan==titan&&mc.options.getCameraType().isFirstPerson()){
            stack.pushPose();stack.mulPose(Axis.YP.rotationDegrees(-titan.getYRot()));
            BtBoardingPilotRenderer.renderArms(client,BtBoardingMotion.firstPersonExit(age),stack,buffers,light);stack.popPose();return;
        }
        stack.pushPose();stack.mulPose(Axis.YP.rotationDegrees(-titan.getYRot()));
        BtBoardingPilotRenderer.renderJoints(client,BtBoardingMotion.sample(titan.getAction(),age).joints(),stack,buffers,light);stack.popPose();
    }
    @SubscribeEvent(priority=EventPriority.LOWEST)public static void frame(RenderFrameEvent.Pre event){
        var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null){activeTitan=null;camera=null;boardingStart=-1;return;}
        TitanEntity titan=mc.player.getVehicle() instanceof TitanEntity t?t:null;
        // Keep the exit view until the authoritative source SetVelocity release.
        if(titan==null&&activeTitan!=null&&activeTitan.getAction().equals("disembark")&&elapsed(activeTitan,0)<BtAnimationTimings.DISEMBARK_RELEASE_TICKS)titan=activeTitan;
        float partial=event.getPartialTick().getGameTimeDeltaPartialTick(false);
        if(titan!=null&&titan.getAction().equals("embark"))boardingStart=titan.getActionStartTick();
        double boardingAge=boardingStart<0?Double.POSITIVE_INFINITY:mc.level.getGameTime()+partial-boardingStart;
        boardingHandoff=titan!=null&&activeTitan==titan
            &&(titan.getAction().equals("idle")||titan.getAction().equals("embark"))
            &&boardingAge>=BtAnimationTimings.EMBARK_TICKS&&boardingAge<BtAnimationTimings.EMBARK_TICKS+HANDOFF_TICKS;
        boolean playing=titan!=null&&BtBoardingMotion.active(titan)&&!titan.isRemoved()
            &&elapsed(titan,partial)<(titan.getAction().equals("embark")?BtAnimationTimings.EMBARK_TICKS:BtAnimationTimings.DISEMBARK_RELEASE_TICKS);
        playing|=boardingHandoff;
        // External-view regression deliberately keeps its observer camera.
        if(Boolean.getBoolean("bt.qaBoardingExternal"))playing=false;
        if(!playing){if(activeTitan!=null){if(mc.getCameraEntity()==camera)mc.setCameraEntity(mc.player);mc.options.setCameraType(mc.player.getVehicle() instanceof TitanEntity?CameraType.FIRST_PERSON:previous);}activeTitan=null;roll=0;return;}
        if(activeTitan==null)previous=mc.options.getCameraType();activeTitan=titan;
        mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.empty(),false);
        if(camera==null||camera.level()!=mc.level)camera=new PilotCamera(mc.level);
        camera.refreshDimensions();
        String action=boardingHandoff?"embark":titan.getAction();
        double ticks=boardingHandoff?boardingAge:elapsed(titan,partial);var sample=BtBoardingMotion.sample(action,ticks);
        Vec3 local=sample.camera();
        Quaternionf rotation=new Quaternionf(sample.rotation());
        if(action.equals("embark")){
            double start=4+20*BtBoardingMotion.duration("embark"),u=Math.max(0,Math.min(1,(ticks-start)/(BtAnimationTimings.EMBARK_TICKS-start)));u=u*u*(3-2*u);
            local=local.lerp(BtBoardingMotion.seat().add(0,mc.player.getEyeHeight(),0),u);
            float relativeYaw=mc.player.getYRot()-titan.getYRot();
            rotation.slerp(new Quaternionf().rotationY((float)Math.toRadians(-relativeYaw)).rotateX((float)Math.toRadians(mc.player.getXRot())),(float)u);
        }else if(ticks<4){
            double u=ticks/4;u=u*u*(3-2*u);local=BtBoardingMotion.seat().add(0,mc.player.getEyeHeight(),0).lerp(local,u);
        }
        Vec3 at=BtBoardingMotion.world(titan,local);
        Vector3f forward=rotation.transform(new Vector3f(0,0,1));
        float yaw=titan.getYRot()+(float)Math.toDegrees(Math.atan2(-forward.x,forward.z));
        float pitch=(float)-Math.toDegrees(Math.atan2(forward.y,Math.hypot(forward.x,forward.z)));
        Vector3f up=rotation.transform(new Vector3f(0,1,0));
        Vector3f right=new Vector3f(forward.z,0,-forward.x).normalize();
        roll=(float)Math.toDegrees(Math.atan2(up.dot(right),up.y));
        camera.moveTo(at.x,at.y-camera.getEyeHeight(),at.z,yaw,pitch);camera.setYHeadRot(yaw);camera.yHeadRotO=yaw;camera.yRotO=yaw;camera.xRotO=pitch;camera.xo=camera.getX();camera.yo=camera.getY();camera.zo=camera.getZ();
        mc.setCameraEntity(camera);mc.options.setCameraType(CameraType.FIRST_PERSON);
    }
    @SubscribeEvent public static void camera(ViewportEvent.ComputeCameraAngles event){if(active()&&event.getCamera().getEntity()==camera)event.setRoll(roll);}
    public static double fovDegrees(double normal,double partial){
        if(activeTitan==null||!activeTitan.getAction().equals("embark")&&!boardingHandoff)return 70;
        var mc=Minecraft.getInstance();double age=boardingStart<0?0:mc.level.getGameTime()+partial-boardingStart;
        double u=Math.max(0,Math.min(1,(age-60)/20));u=u*u*(3-2*u);
        return 70+(normal-70)*u;
    }
    @SubscribeEvent public static void fov(ViewportEvent.ComputeFov event){if(active())event.setFOV(fovDegrees(event.getFOV(),event.getPartialTick()));}
    /** The cockpit shutter closes during the final body-to-cabin render handoff,
     * then opens on the powered cockpit before the weapon draw starts. */
    @SubscribeEvent(priority=EventPriority.LOWEST)public static void shutter(RenderGuiEvent.Post event){
        if(!active()||boardingStart<0||Boolean.getBoolean("bt.qaBoardingExternal"))return;
        var mc=Minecraft.getInstance();if(mc.level==null)return;
        double age=mc.level.getGameTime()+event.getPartialTick().getGameTimeDeltaPartialTick(false)-boardingStart;
        double opacity=age<60?(age-56)/4:(64-age)/4;
        int alpha=(int)(255*Math.max(0,Math.min(1,opacity)));if(alpha==0)return;
        event.getGuiGraphics().fill(0,0,mc.getWindow().getGuiScaledWidth(),mc.getWindow().getGuiScaledHeight(),alpha<<24);
    }
    @SubscribeEvent(receiveCanceled=true)public static void hands(RenderHandEvent event){if(active())event.setCanceled(true);}
}
