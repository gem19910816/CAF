package com.caf.btmesh;

import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Quaternionf;

/** Samples final approved BB bones, not source animation or a synthetic rig.
 * Uses the production BtLocomotionBlend; runtime state selection still needs client QA. */
public final class BodyLocomotionTransitionAudit {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Path root=Path.of("src/main/resources/assets/bt7274");
        var model=GltfSkin.load(Files.readAllBytes(root.resolve("models/native/body.glb")));
        var player=new BbMotionPlayer(model,Files.readAllBytes(root.resolve("motion/body_bb.bin")));
        var run=player.newFrame();var idle=player.newFrame();var blend=player.newFrame();
        var advancing=player.newFrame();var departure=player.newFrame();var from=player.newFrame();
        var runPose=model.createPose();var idlePose=model.createPose();var blendPose=model.createPose();
        var nativeBefore=model.createPose();var nativeAfter=model.createPose();
        double duration=BtLocomotionBlend.duration(4,1);
        check(duration>=.28&&duration<=.45,"stopping needs a bounded visible transition");
        double dt=1.0/120,maxFrame=0,maxEntry=0,maxExit=0,maxHardCut=0,maxAngle=0,maxNativeAngle=0;
        int scenarios=0;
        List<Map<String,Object>> worst=new ArrayList<>();
        // Include the forward run, walk, reverse, strafes, and doomed walk.
        for(int clip:new int[]{2,4,23,24,25,26})for(int phase=0;phase<96;phase++){
            double source=player.duration(clip)*phase/96.;
            player.sample(clip,source,run);player.submit(run,runPose);
            from.copyFrom(run);
            player.sample(1,0,idle);player.submit(idle,idlePose);
            maxHardCut=Math.max(maxHardCut,difference(runPose.world,idlePose.world));
            Matrix4f[] previous=copy(runPose.world);double localMax=0;
            for(int frame=0;frame<=Math.ceil(duration/dt)+1;frame++){
                double t=Math.min(duration,frame*dt),u=t/duration;
                player.sample(1,t,idle);
                BtLocomotionBlend.apply(player,from,idle,clip,source,1,t,duration,0,advancing,departure,blend);
                player.submit(blend,blendPose);
                double delta=difference(previous,blendPose.world);localMax=Math.max(localMax,delta);
                double angle=angleDifference(previous,blendPose.world);maxAngle=Math.max(maxAngle,angle);
                player.sample(clip,source+Math.max(0,t-dt),run);player.submit(run,nativeBefore);
                player.sample(clip,source+t,run);player.submit(run,nativeAfter);
                double nativeAngle=angleDifference(nativeBefore.world,nativeAfter.world);maxNativeAngle=Math.max(maxNativeAngle,nativeAngle);
                if(frame==0){maxEntry=Math.max(maxEntry,delta);check(delta<1e-5,"entry pose discontinuity at clip "+clip+" phase "+phase+": "+delta);}
                check(Double.isFinite(delta)&&delta<.30,"visible one-frame bone jump at clip "+clip+" phase "+phase+" t="+t+": "+delta+"m");
                check(angle<18,"rotation snap at 120fps clip="+clip+" phase="+phase+" t="+t+": "+angle+" degrees node="+worstAngleNode(model,previous,blendPose.world));
                if(angle>18)worst.add(Map.of("kind","angular_velocity_warning","clip",clip,"phase",phase/96.,"age",t,"node",worstAngleNode(model,previous,blendPose.world),"degrees_per_120fps_frame",angle,"unmodified_native_degrees_per_frame",nativeAngle));
                previous=copy(blendPose.world);
            }
            player.sample(1,duration,idle);player.submit(idle,idlePose);
            double exit=difference(blendPose.world,idlePose.world);maxExit=Math.max(maxExit,exit);
            check(exit<1e-5,"stop blend did not reach actual idle pose");
            maxFrame=Math.max(maxFrame,localMax);scenarios++;
            if(localMax>.05)worst.add(Map.of("clip",clip,"phase",phase/96.,"max_120fps_step_metres",localMax));
        }
        var report=new LinkedHashMap<String,Object>();report.put("scope","Actual final BB geometry/binary; candidate live-phase stop blend continuity only. Not a runtime latch, feet-ground contact or visual approval claim.");
        report.put("stop_blend_seconds",duration);report.put("arbitrary_stop_phases",scenarios);report.put("assertions",checks);
        report.put("max_bone_rotation_step_120fps_degrees",maxAngle);
        report.put("max_unmodified_native_rotation_step_120fps_degrees",maxNativeAngle);
        report.put("max_entry_jump_metres",maxEntry);report.put("max_exit_error_metres",maxExit);report.put("max_bone_step_at_120fps_metres",maxFrame);report.put("unblended_run_idle_jump_metres",maxHardCut);
        clockAudit();
        report.put("assertions",checks);report.put("larger_steps",worst);Path out=Path.of("build/audits/body-stop-transition.json");Files.createDirectories(out.getParent());Files.writeString(out,new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("PASS final BB stop candidates: "+scenarios+" arbitrary phases, "+checks+" checks; max bone step="+maxFrame+"m, hard cut="+maxHardCut+"m; "+out);
    }
    private static Matrix4f[] copy(Matrix4f[] matrices){return Arrays.stream(matrices).map(Matrix4f::new).toArray(Matrix4f[]::new);}
    private static void clockAudit(){
        var clock=new NativeLocomotionClock();double last=0,x=0;
        for(int tick=0;tick<100;tick++){
            boolean moving=tick>=1&&tick<20||tick>=60&&tick<80;
            if(moving)x+=.4;
            var sample=clock.advance(tick,x,0,moving?"4":"1",moving?35:0);
            for(int f=0;f<10;f++){
                double cycle=sample.cycle(f/10F);check(cycle+1e-10>=last,"stopping clock phase bounced backwards at tick "+tick);last=cycle;
            }
            check(clock.advance(tick,x+5,0,"mismatch",.1)==sample,"render count changes tick-idempotent distance sample");
            if(tick>=21&&tick<60)check(sample.increment()==0,"idle advances phase from old stale position");
        }
        double cycle=clock.current().cycle();var teleported=clock.advance(101,x+30,0,"4",35);
        check(teleported.cycle()==cycle,"teleport incorrectly advances footstep phase");
    }
    private static double angleDifference(Matrix4f[] a,Matrix4f[] b){
        double max=0;
        for(int i=0;i<a.length;i++){
            var x=a[i].getUnnormalizedRotation(new Quaternionf()).normalize();var y=b[i].getUnnormalizedRotation(new Quaternionf()).normalize();
            double angle=Math.toDegrees(2*Math.acos(Math.min(1,Math.abs(x.dot(y)))));max=Math.max(max,angle);
        }
        return max;
    }
    private static String worstAngleNode(GltfSkin model,Matrix4f[] a,Matrix4f[] b){
        double max=-1;String name="";for(int i=0;i<a.length;i++){var x=a[i].getUnnormalizedRotation(new Quaternionf()).normalize();var y=b[i].getUnnormalizedRotation(new Quaternionf()).normalize();double angle=Math.toDegrees(2*Math.acos(Math.min(1,Math.abs(x.dot(y)))));if(angle>max){max=angle;name=model.nodes[i].name;}}return name;
    }
    private static double difference(Matrix4f[] a,Matrix4f[] b){
        double largest=0;
        // Sample each bone origin and a point 25cm along its local axes, so a
        // rotation-only snap is caught even when its pivot never moves.
        for(int i=0;i<a.length;i++)for(int axis=-1;axis<3;axis++){
            Vector3f point=new Vector3f();if(axis==0)point.x=4.6242F;if(axis==1)point.y=4.6242F;if(axis==2)point.z=4.6242F;
            Vector3f x=a[i].transformPosition(new Vector3f(point));Vector3f y=b[i].transformPosition(point);largest=Math.max(largest,x.distance(y));
        }
        return largest;
    }
}
