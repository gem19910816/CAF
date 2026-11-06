package com.caf.btmesh;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Geometry-independent original-curve transitions plus pure-cube FP checks. */
public final class R3MotionAudit {
    private static long assertions;
    private static void check(boolean b,String m){assertions++;if(!b)throw new AssertionError(m);}
    private static Matrix4f[] snapshot(GltfSkin.Pose p){return Arrays.stream(p.world).map(Matrix4f::new).toArray(Matrix4f[]::new);}
    private static void same(Matrix4f[] a,GltfSkin.Pose b,float tolerance,String message){for(int n=0;n<a.length;n++)check(a[n].equals(b.world[n],tolerance),message+" node "+n);}
    private static void finite(GltfSkin.Pose p){for(Matrix4f m:p.world)for(float f:m.get(new float[16]))check(Float.isFinite(f),"finite skeletal state");}
    public static void main(String[] args)throws Exception{
        Path folder=Path.of(args[0]),reportPath=Path.of(args[1]);var params=Map.of("aim_yaw",0f,"aim_pitch",0f);
        String bodyStem=Files.exists(folder.resolve("body.glb"))?"body":"blockout_body",fpStem=Files.exists(folder.resolve("fp.glb"))?"fp":"blockout_fp";
        GltfSkin body=GltfSkin.load(Files.readAllBytes(folder.resolve(bodyStem+".glb")));
        JsonObject bm=JsonParser.parseString(Files.readString(folder.resolve(bodyStem+".motion.json"))).getAsJsonObject();
        NativeMotionPlayer player=new NativeMotionPlayer(body,bm);var pose=body.createPose();var reference=body.createPose();
        List<String> sequences=List.of("bt_combat_idle","bt_combat_walk_forward","bt_combat_run_forward","bt_combat_sprint_forward_noaim","at_hotdrop_drop_2knee","at_hotdrop_quickstand","at_MP_embark","at_dismount_stand","at_player_melee_punch_01","bt_vortex_arm_pose_add","bt_fire_additive","bt_reload_additive","at_doomed_idle","at_doomed_walk_F","at_MP_death_explosion");
        for(String s:sequences)check(player.hasSequence(s),"R3 original sequence available "+s);
        List<NativeMotionPlayer.BodyPose> states=List.of(
            new NativeMotionPlayer.BodyPose("bt_combat_idle",.6),
            new NativeMotionPlayer.BodyPose("bt_combat_walk_forward",.8),
            new NativeMotionPlayer.BodyPose("at_hotdrop_drop_2knee",9.74),
            new NativeMotionPlayer.BodyPose("at_hotdrop_quickstand",.35),
            new NativeMotionPlayer.BodyPose("at_MP_embark",1.2),
            new NativeMotionPlayer.BodyPose("at_player_melee_punch_01",.2),
            new NativeMotionPlayer.BodyPose("bt_combat_idle",.6,"bt_vortex_arm_pose_add",2,0,1),
            new NativeMotionPlayer.BodyPose("bt_combat_walk_forward",.8,"bt_reload_additive",1.1,2.6,1),
            new NativeMotionPlayer.BodyPose("bt_combat_run_forward",.4,"bt_fire_additive",.06,.15,1),
            new NativeMotionPlayer.BodyPose("at_doomed_idle",.5));
        for(var from:states){
            player.sampleBodyTransition(from,from,1,params,pose);var original=snapshot(pose);var saved=player.snapshotCurrent();
            for(var to:states){
                player.sampleBodyFromSnapshot(saved,to,0,params,pose);same(original,pose,1e-6f,"interrupt has no frame-zero snap");
                player.sampleBodyTransition(to,to,1,params,reference);player.sampleBodyFromSnapshot(saved,to,1,params,pose);same(snapshot(reference),pose,1e-6f,"crossfade target endpoint");
                player.sampleBodyFromSnapshot(saved,to,.43f,params,pose);finite(pose);
                var interrupted=snapshot(pose);var snapshot=player.snapshotCurrent();
                player.sampleBodyFromSnapshot(snapshot,from,0,params,pose);same(interrupted,pose,1e-6f,"nested transition interruption is continuous");
            }
        }
        // Segment playback must land on the original impact frame at MC landing.
        double impact=.8366762399673462,d=player.sequenceDuration("at_hotdrop_drop_2knee",params);
        player.sampleSegment("at_hotdrop_drop_2knee",3,3,0,impact,params,pose);
        player.sample("at_hotdrop_drop_2knee",d*impact,params,reference);same(snapshot(reference),pose,1e-5f,"physical landing aligns original impact");
        for(int i=0;i<100;i++)for(String side:List.of("left","right")){
            float w=player.footContact("bt_combat_walk_forward",i/100.0,side);check(Float.isFinite(w)&&w>=0&&w<=1,"contact envelope bounded");
        }
        check(player.footContact("bt_combat_walk_forward",0,"left")==0,"old left stance has ended across loop boundary");
        check(player.footContact("bt_combat_walk_forward",0,"right")>.9f,"right stance crosses loop boundary");
        check(player.footContact("bt_combat_walk_forward",.12,"left")>.9f,"left stance follows native event");
        check(player.footContact("bt_combat_walk_forward",.32,"left")==0,"left stance released after next right event");
        var fp=GltfSkin.load(Files.readAllBytes(folder.resolve(fpStem+".glb")));
        var fm=JsonParser.parseString(Files.readString(folder.resolve(fpStem+".motion.json"))).getAsJsonObject();
        var first=new NativeMotionPlayer(fp,fm);var pp=fp.createPose();
        int visibleVertices=0,totalVertices=0;float closest=Float.POSITIVE_INFINITY,farthest=Float.NEGATIVE_INFINITY;
        for(var p:fp.primitives){check(p.positions().length/3<=24,"each FP primitive is a cuboid");totalVertices+=p.positions().length/3;}
        first.sampleFirstPerson(NativeMotionPlayer.FirstPersonState.IDLE,0,0,.3,-1,pp);
        Matrix4f camera=new Matrix4f();check(first.attachment(pp,"CAMERA",camera),"FP source camera");camera.invert();
        for(var p:fp.primitives)for(int v=0;v<p.positions().length/3;v++){
            int j=p.joints()[v*4];Vector3f point=new Vector3f(p.positions()[v*3],p.positions()[v*3+1],p.positions()[v*3+2]);
            pp.joints[p.skin()][j].transformPosition(point);camera.transformPosition(point);
            closest=Math.min(closest,point.x);farthest=Math.max(farthest,point.x);if(point.x>0)visibleVertices++;
            check(Float.isFinite(point.x)&&Float.isFinite(point.y)&&Float.isFinite(point.z),"cube FP skin finite");
        }
        check(visibleVertices>totalVertices/2,"majority cube FP geometry in front of original camera");
        for(var state:NativeMotionPlayer.FirstPersonState.values()){
            first.sampleFirstPerson(state,.2,0,.3,.05,pp);var saved=first.snapshotCurrent();var original=snapshot(pp);
            for(var next:NativeMotionPlayer.FirstPersonState.values()){
                first.sampleFirstPersonFromSnapshot(saved,next,.1,.5f,.4,.03,0,pp);same(original,pp,1e-6f,"FP interruption continuous");
                first.sampleFirstPersonFromSnapshot(saved,next,.1,.5f,.4,.03,.6f,pp);finite(pp);
            }
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("status","passed");result.put("assertions",assertions);result.put("original_sequences",sequences);result.put("fp_cube_vertices",totalVertices);result.put("fp_vertices_in_front_of_camera",visibleVertices);result.put("fp_camera_forward_range_source_units",List.of(closest,farthest));result.put("scope","Pure cube R3 blockout rig and original curve/state continuity; geometry/pivots/material are still unfrozen and visual runtime verification remains separate.");
        Files.writeString(reportPath,new GsonBuilder().setPrettyPrinting().create().toJson(result));System.out.println("PASS R3MotionAudit "+assertions+" assertions");
    }
}
