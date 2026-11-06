package com.caf.btmesh;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Read-only stage-by-stage diagnostics; not an acceptance test. */
public final class BodyAdaptationForensics {
 static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();static final Map<String,Float> P=Map.of("aim_yaw",0f,"aim_pitch",0f);
 static Vector3f pos(GltfSkin.Pose pose,int node){return pose.world[node].getTranslation(new Vector3f());}
 static float angle(Quaternionf a,Quaternionf b){return (float)Math.toDegrees(2*Math.acos(Math.min(1,Math.abs(a.dot(b)))));}
 static float[] v(Vector3f x){return new float[]{x.x,x.y,x.z};}
 public static void main(String[] args)throws Exception{
  Path project=Path.of(args[0]),root=project.resolve("mod_mesh/src/main/resources/assets/bt7274"),out=Path.of(args[1]);var model=GltfSkin.load(Files.readAllBytes(root.resolve("models/native/body.glb")));var metadata=JsonParser.parseString(Files.readString(root.resolve("motion/body.json"))).getAsJsonObject();var motion=new NativeMotionPlayer(model,metadata);var adapter=new CubePoseAdapter(model,metadata.getAsJsonObject("cube_rig"));var pose=model.createPose();Map<String,Integer> n=new HashMap<>();for(int i=0;i<model.nodes.length;i++)n.put(model.nodes[i].name,i);
  motion.sample("bt_combat_idle",0,P,pose);adapter.apply(pose);adapter.alignWeaponToRightGrip(pose,1);var combatAnchor=adapter.captureWeaponRelease(pose);
  var results=new LinkedHashMap<String,Object>();String[] seqs={"bt_casual_idle","bt_combat_idle","bt_combat_walk_forward","bt_combat_run_forward","bt_combat_sprint_forward_noaim","at_Crouch_Aim","at_hotdrop_drop_2knee","at_hotdrop_quickstand","at_MP_embark","at_dismount_stand","at_player_melee_punch_01","bt_dash_forward_01","bt_dash_backward_01","bt_dash_left","bt_dash_right","at_doomed_idle","at_doomed_walk_F","at_MP_death_explosion"};
  for(String seq:seqs){double duration=motion.sequenceDuration(seq,P);float maxIK=0,maxKnee=0,maxSoleBefore=0,maxSoleAfter=0,maxIKAngle=0,maxWeaponEntryDifference=0,maxHandWeaponDistance=0;String worstBone="";double worstTime=0;List<Object> points=new ArrayList<>();
   motion.sample(seq,0,P,pose);adapter.apply(pose);adapter.alignWeaponToRightGrip(pose,1);var stateAnchor=adapter.captureWeaponRelease(pose);
   for(int f=0;f<=30;f++){double time=f*duration/30;motion.sample(seq,time,P,pose);adapter.apply(pose);Matrix4f[] raw=Arrays.stream(pose.world).map(Matrix4f::new).toArray(Matrix4f[]::new);
    adapter.applyWeaponRelease(pose,combatAnchor);Vector3f gunCombat=pos(pose,n.get("cube_weapon"));adapter.applyWeaponRelease(pose,stateAnchor);Vector3f gunState=pos(pose,n.get("cube_weapon"));maxWeaponEntryDifference=Math.max(maxWeaponEntryDifference,gunCombat.distance(gunState));
    maxHandWeaponDistance=Math.max(maxHandWeaponDistance,gunCombat.distance(pos(pose,n.get("cube_hand_right"))));
    float beforeL=adapter.soleHeight(pose,"left"),beforeR=adapter.soleHeight(pose,"right");maxSoleBefore=Math.max(maxSoleBefore,Math.max(Math.abs(beforeL),Math.abs(beforeR)));
    adapter.groundFeet(pose,0,0,1,1);float afterL=adapter.soleHeight(pose,"left"),afterR=adapter.soleHeight(pose,"right");maxSoleAfter=Math.max(maxSoleAfter,Math.max(Math.abs(afterL),Math.abs(afterR)));
    for(String bone:List.of("cube_thigh_left","cube_thigh_right","cube_shin_left","cube_shin_right","cube_foot_left","cube_foot_right")){
     int i=n.get(bone);float distance=raw[i].getTranslation(new Vector3f()).distance(pos(pose,i));float a=angle(raw[i].getUnnormalizedRotation(new Quaternionf()).normalize(),pose.world[i].getUnnormalizedRotation(new Quaternionf()).normalize());if(distance>maxIK){maxIK=distance;worstBone=bone;worstTime=time;}maxIKAngle=Math.max(maxIKAngle,a);if(bone.contains("shin"))maxKnee=Math.max(maxKnee,distance);
    }
    if(f==0||f==15||f==30){var point=new LinkedHashMap<String,Object>();point.put("time",time);point.put("sole_before",new float[]{beforeL,beforeR});point.put("sole_after",new float[]{afterL,afterR});point.put("weapon_combat_entry",v(gunCombat));point.put("weapon_same_action_entry",v(gunState));var j=new LinkedHashMap<String,Object>();for(String bone:List.of("def_r_shoulder","def_r_elbow","def_r_wrist","def_r_hip","def_r_knee","def_r_ankle","def_l_shoulder","def_l_elbow","def_l_wrist","cube_upper_arm_right","cube_forearm_right","cube_hand_right","cube_thigh_right","cube_shin_right","cube_foot_right","cube_upper_arm_left","cube_forearm_left","cube_hand_left"))j.put(bone,v(raw[n.get(bone)].getTranslation(new Vector3f())));point.put("raw_joint_positions",j);points.add(point);}
   }
   var result=new LinkedHashMap<String,Object>();result.put("duration",duration);result.put("max_grounding_joint_displacement_m",maxIK);result.put("max_grounding_knee_displacement_m",maxKnee);result.put("max_grounding_rotation_degrees",maxIKAngle);result.put("worst_grounding_bone",worstBone);result.put("worst_grounding_time",worstTime);result.put("max_abs_sole_before_m",maxSoleBefore);result.put("max_abs_sole_after_m",maxSoleAfter);result.put("combat_vs_action_entry_weapon_displacement_m",maxWeaponEntryDifference);result.put("max_released_weapon_origin_to_right_wrist_m",maxHandWeaponDistance);result.put("samples",points);results.put(seq,result);
  }
  var rig=metadata.getAsJsonObject("cube_rig");JsonArray align=rig.getAsJsonArray("source_to_cube_rotation_xyzw");Quaternionf A=new Quaternionf(align.get(0).getAsFloat(),align.get(1).getAsFloat(),align.get(2).getAsFloat(),align.get(3).getAsFloat());motion.sample("bt_casual_idle",0,P,pose);adapter.apply(pose);var axes=new LinkedHashMap<String,Object>();
  for(String side:List.of("right","left")){String s=side.equals("right")?"r":"l";for(String[] chain:new String[][]{{"shoulder","elbow","upper_arm","forearm"},{"elbow","wrist","forearm","hand"},{"hip","knee","thigh","shin"},{"knee","ankle","shin","foot"}}){Vector3f source=pos(pose,n.get("def_"+s+"_"+chain[1])).sub(pos(pose,n.get("def_"+s+"_"+chain[0]))).normalize();A.transform(source);Vector3f target=pos(pose,n.get("cube_"+chain[3]+"_"+side)).sub(pos(pose,n.get("cube_"+chain[2]+"_"+side))).normalize();float degrees=(float)Math.toDegrees(source.angle(target));axes.put(side+"_"+chain[2],Map.of("aligned_source_reference_direction",v(source),"authored_reference_direction",v(target),"reference_direction_error_degrees",degrees));}}
  results.put("reference_anatomical_limb_axes",axes);Files.createDirectories(out.getParent());Files.writeString(out,JSON.toJson(results));System.out.println("WROTE "+out);
 }
}
