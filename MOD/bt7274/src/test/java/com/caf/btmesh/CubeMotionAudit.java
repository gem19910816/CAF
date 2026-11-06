package com.caf.btmesh;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Quaternionf;

/** Checks the delivered cube rig against its authored source and original clips. */
public final class CubeMotionAudit {
    private static int checks;
    private static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
    public static void main(String[] args)throws Exception{
        Path asset=Path.of(args[0]);var model=GltfSkin.load(Files.readAllBytes(asset));
        JsonObject metadata=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
        var motion=new NativeMotionPlayer(model,metadata);var adapter=new CubePoseAdapter(model,metadata.getAsJsonObject("cube_rig"));
        var pose=model.createPose();var rest=model.createPose();rest.resetLocal();rest.finish();Map<String,Float> params=Map.of("aim_yaw",0f,"aim_pitch",0f);
        var rig=metadata.getAsJsonObject("cube_rig");
        if(rig.has("source_to_cube_rotation_xyzw")){
            JsonArray a=rig.getAsJsonArray("source_to_cube_rotation_xyzw"),b=rig.getAsJsonArray("cube_wrapper_rotation_xyzw");
            Quaternionf align=new Quaternionf(a.get(0).getAsFloat(),a.get(1).getAsFloat(),a.get(2).getAsFloat(),a.get(3).getAsFloat());
            Quaternionf wrapper=new Quaternionf(b.get(0).getAsFloat(),b.get(1).getAsFloat(),b.get(2).getAsFloat(),b.get(3).getAsFloat());
            Vector3f entityForward=new Vector3f(0,0,-1);wrapper.transform(entityForward);
            new Quaternionf().rotationY((float)-Math.PI/2).transform(entityForward);
            check(entityForward.distance(new Vector3f(0,0,1))<1e-5,"authored front follows yaw0 MC entity forward");
            for(String axis:List.of("right","up","back")){
                JsonArray source=rig.getAsJsonArray("source_anatomical_"+axis+"_world");Vector3f actual=new Vector3f(source.get(0).getAsFloat(),source.get(1).getAsFloat(),source.get(2).getAsFloat());
                align.transform(actual);Vector3f expected=switch(axis){case "right"->new Vector3f(1,0,0);case "up"->new Vector3f(0,1,0);default->new Vector3f(0,0,1);};wrapper.transform(expected);
                check(actual.distance(expected)<1e-5,"source "+axis+" maps to authored "+axis);
            }
        }
        motion.sample("bt_casual_idle",0,params,pose);adapter.apply(pose);
        for(var raw:metadata.getAsJsonObject("cube_rig").getAsJsonArray("bindings")){
            JsonObject binding=raw.getAsJsonObject();int n=binding.get("target_node").getAsInt();
            if(binding.has("reference_mode")&&binding.get("reference_mode").getAsString().startsWith("bind_neutral"))continue;
            check(pose.world[n].equals(rest.world[n],1e-4f),"authored reference bone "+model.nodes[n].name);
        }
        List<String> clips=List.of("bt_combat_idle","bt_combat_walk_forward","bt_combat_run_forward","at_Crouch_Aim","at_hotdrop_drop_2knee","at_hotdrop_quickstand","at_MP_embark","at_dismount_stand","at_player_melee_punch_01","bt_dash_forward_01","at_MP_death_explosion","bt_vortex_arm_pose_add");
        float maxBoneLengthError=0,maxGroundError=0;
        for(String clip:clips){check(motion.hasSequence(clip),"available "+clip);
            for(int f=0;f<=12;f++){
                double t=f*motion.sequenceDuration(clip,params)/12;motion.sample(clip,t,params,pose);adapter.apply(pose);
                for(var raw:metadata.getAsJsonObject("cube_rig").getAsJsonArray("bindings")){
                    int n=raw.getAsJsonObject().get("target_node").getAsInt(),p=model.nodes[n].parent;
                    if(model.nodes[n].name.equals("cube_pelvis")||model.nodes[n].name.startsWith("cube_abdomen_ram_"))continue;
                    float actual=pose.world[n].getTranslation(new Vector3f()).distance(pose.world[p].getTranslation(new Vector3f()));
                    float expected=rest.world[n].getTranslation(new Vector3f()).distance(rest.world[p].getTranslation(new Vector3f()));
                    maxBoneLengthError=Math.max(maxBoneLengthError,Math.abs(actual-expected));check(Math.abs(actual-expected)<1e-4,"fixed authored bone length");
                }
                for(Matrix4f m:pose.world)for(float v:m.get(new float[16]))check(Float.isFinite(v),"finite retarget pose");
                if(rig.has("endpoint_constraints"))for(JsonElement raw:rig.getAsJsonArray("endpoint_constraints")){
                    JsonObject c=raw.getAsJsonObject();for(String end:List.of("lower","upper")){
                        int anchor=c.get(end+"_anchor_node").getAsInt(),node=c.get(end.equals("lower")?"body_node":"rod_node").getAsInt();
                        JsonArray p=c.getAsJsonArray(end+"_anchor_local");Vector3f expected=new Vector3f(p.get(0).getAsFloat(),p.get(1).getAsFloat(),p.get(2).getAsFloat());pose.world[anchor].transformPosition(expected);
                        check(expected.distance(pose.world[node].getTranslation(new Vector3f()))<1e-4,"hydraulic rigid half remains attached to "+end+" moving endpoint");
                    }
                }
            }
        }
        motion.sample("bt_casual_idle",0,params,pose);adapter.apply(pose);
        float leftTarget=adapter.soleHeight(pose,"left")+.15f,rightTarget=adapter.soleHeight(pose,"right")+.15f;
        adapter.groundFeet(pose,leftTarget,rightTarget,1,1);
        maxGroundError=Math.max(Math.abs(adapter.soleHeight(pose,"left")-leftTarget),Math.abs(adapter.soleHeight(pose,"right")-rightTarget));check(maxGroundError<1e-4,"reachable ground IK soles match terrain");
        var clock=new NativeLocomotionClock();clock.advance(0,0,0,"bt_combat_walk_forward",motion.strideMetres("bt_combat_walk_forward"));
        var state=clock.advance(1,.15,0,"bt_combat_walk_forward",motion.strideMetres("bt_combat_walk_forward"));
        check(state==clock.advance(1,.15,0,"bt_combat_walk_forward",motion.strideMetres("bt_combat_walk_forward")),"same tick is idempotent");
        check(Math.abs(state.cycle()-.15/motion.strideMetres("bt_combat_walk_forward"))<1e-12,"distance controls source phase");
        check(motion.eventsCycles("bt_combat_walk_forward",0,1).stream().filter(e->e.name().startsWith("AE_FOOTSTEP")).count()==6,"six original footsteps per full stride");
        check(motion.eventsCycles("bt_combat_walk_forward",.9,1.1).stream().filter(e->e.name().startsWith("AE_FOOTSTEP")).count()==2,"loop boundary footsteps preserved");
        Map<String,Object> report=new LinkedHashMap<>();report.put("status","passed");report.put("assertions",checks);report.put("max_authored_bone_length_error_metres",maxBoneLengthError);report.put("raised_ground_sole_error_metres",maxGroundError);report.put("sequences_checked",clips);report.put("scope","Source curves, authored cube lengths, exact reference posture, finite action states, analytic MC two-bone IK reachable terrain, normalized footstep clock; in-game terrain and aesthetic review remain separate.");
        Files.writeString(Path.of(args[2]),new GsonBuilder().setPrettyPrinting().create().toJson(report));System.out.println("PASS CubeMotionAudit "+checks+" assertions, ground error="+maxGroundError);
    }
}
