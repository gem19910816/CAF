package com.caf.btmesh;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.joml.Matrix4f;

/** Actual original asset checks: bind-vs-reference framing, additive layering,
 * deterministic sampling, transition endpoints, and exact native event clocks. */
public final class NativeMotionAudit {
    private static int assertions;
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private static final Path ROOT=Path.of("F:/自制武器源文件/08_BT7274");
    private static void finite(GltfSkin.Pose pose){for(Matrix4f m:pose.world)for(float x:m.get(new float[16]))check(Float.isFinite(x),"finite original layered pose");}
    private static NativeMotionPlayer player(GltfSkin m,String name)throws Exception{
        return new NativeMotionPlayer(m,JsonParser.parseString(Files.readString(ROOT.resolve("mod_mesh/src/main/resources/assets/bt7274/motion/"+name+".json"))).getAsJsonObject());
    }
    public static void main(String[] args)throws Exception{
        var body=GltfSkin.load(Files.readAllBytes(ROOT.resolve("mod_mesh/src/main/resources/assets/bt7274/models/native/body.glb")));
        var motion=player(body,"body");var pose=body.createPose();var reference=body.createPose();var params=Map.of("aim_yaw",0f,"aim_pitch",0f);
        for(String sequence:List.of("bt_combat_idle","bt_combat_walk_forward","bt_combat_run_forward","at_Crouch_Aim","bt_fire_additive","bt_reload_additive"))check(motion.hasSequence(sequence),"all original layer clips present: "+sequence);
        motion.sample("bt_casual_walk_forward",.833333333,params,pose);reference.sample("@bt_casual_walk_forward",.833333333,true);
        for(int i=0;i<pose.world.length;i++)check(pose.world[i].equals(reference.world[i],1e-5f),"absolute source clip agrees with original GLTF sampler");
        motion.sample("bt_combat_walk_forward",.833333333,params,pose);finite(pose);
        Matrix4f[] snapshot=Arrays.stream(pose.world).map(Matrix4f::new).toArray(Matrix4f[]::new);
        for(int repeat=0;repeat<20;repeat++)motion.sample("bt_combat_walk_forward",.833333333,params,pose);
        for(int i=0;i<pose.world.length;i++)check(pose.world[i].equals(snapshot[i],0),"no additive accumulation across render frames");
        motion.sampleTransition("bt_combat_idle",.4,"bt_combat_run_forward",.2,0,params,pose);motion.sample("bt_combat_idle",.4,params,reference);
        for(int i=0;i<pose.world.length;i++)check(pose.world[i].equals(reference.world[i],1e-5f),"transition zero endpoint");
        motion.sampleTransition("bt_combat_idle",.4,"bt_combat_run_forward",.2,1,params,pose);motion.sample("bt_combat_run_forward",.2,params,reference);
        for(int i=0;i<pose.world.length;i++)check(pose.world[i].equals(reference.world[i],1e-5f),"transition one endpoint");
        motion.sampleLayered("bt_combat_run_forward",.6,"bt_fire_additive",.1,.3,1,params,pose);finite(pose);
        var fp=GltfSkin.load(Files.readAllBytes(ROOT.resolve("mod_mesh/src/main/resources/assets/bt7274/models/native/fp.glb")));
        var fm=player(fp,"fp");var fpose=fp.createPose();
        for(var state:NativeMotionPlayer.FirstPersonState.values())for(float ads:new float[]{0,.5f,1}){
            fm.sampleFirstPerson(state,.1,ads,.4,.05,fpose);finite(fpose);
            check(fm.attachment(fpose,"CAMERA",new Matrix4f()),"original FP camera attachment");
        }
        var events=fm.events("reload_seq",-.001,2.6,2.6);
        check(events.stream().anyMatch(e->e.name().equals("AE_WPN_FILLAMMO")&&Math.abs(e.seconds()-2.4375)<1e-6),"original fill ammo at cycle .9375 of actual 2.6s");
        check(events.stream().anyMatch(e->e.options().equals("Wpn_XO16_1p_Reload_Pt1")&&e.seconds()==0),"frame-zero reload sound included exactly once");
        check(fm.events("reload_seq",2.6,2.7,2.6).isEmpty(),"event not replayed after crossing");
        Map<String,Object> result=new LinkedHashMap<>();result.put("status","passed");result.put("assertions",assertions);
        result.put("scope","Native source curve/sequence evaluation numerical checks; runtime IK, viewkick, bob and engine layer graph equivalence are not claimed.");
        Files.writeString(ROOT.resolve("qa/native_motion_java_audit.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));System.out.println("PASS NativeMotionAudit "+assertions+" assertions");
    }
}
