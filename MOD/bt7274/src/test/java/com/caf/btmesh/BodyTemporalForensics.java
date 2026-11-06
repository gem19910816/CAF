package com.caf.btmesh;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
/** Compare final sampled IK flips with pre-IK and original source poses. */
public final class BodyTemporalForensics {
 static float angle(Quaternionf a,Quaternionf b){return (float)Math.toDegrees(2*Math.acos(Math.min(1,Math.abs(a.dot(b)))));}
 static Quaternionf q(GltfSkin.Pose p,int i){return p.world[i].getUnnormalizedRotation(new Quaternionf()).normalize();}
 static Quaternionf local(GltfSkin model,GltfSkin.Pose p,int i){int parent=model.nodes[i].parent;Quaternionf result=q(p,i);if(parent>=0)result.premul(q(p,parent).invert());return result.normalize();}
 public static void main(String[] args)throws Exception{
  Path project=Path.of(args[0]),root=project.resolve("mod_mesh/src/main/resources/assets/bt7274");var model=GltfSkin.load(Files.readAllBytes(root.resolve("models/native/body.glb")));var meta=JsonParser.parseString(Files.readString(root.resolve("motion/body.json"))).getAsJsonObject();var motion=new NativeMotionPlayer(model,meta);var adapter=new CubePoseAdapter(model,meta.getAsJsonObject("cube_rig"));var pose=model.createPose();Map<String,Integer> n=new HashMap<>();for(int i=0;i<model.nodes.length;i++)n.put(model.nodes[i].name,i);var params=Map.of("aim_yaw",0f,"aim_pitch",0f);List<Object> results=new ArrayList<>();
  Object[][] cases={{"bt_combat_idle",17.366666666666667,17.4,"left",false,1.},{"bt_combat_walk_forward",3.,3.033333333333333,"right",true,1.},{"bt_combat_run_forward",2.4,2.433333333333333,"right",true,1.},{"bt_dash_forward_01",.2,.23333333333333334,"right",false,.5},{"bt_dash_backward_01",.06666666666666667,.1,"left",false,.5},{"bt_dash_left",.26666666666666666,.3,"left",false,.5},{"bt_dash_right",.16666666666666666,.2,"left",false,.5}};
  for(Object[] c:cases){String seq=(String)c[0],side=(String)c[3],s=side.equals("left")?"l":"r";double duration=motion.sequenceDuration(seq,params);boolean loco=(Boolean)c[4];Quaternionf[] before=new Quaternionf[2],after=new Quaternionf[2],source=new Quaternionf[2];List<Object> samples=new ArrayList<>();
   for(int f=0;f<2;f++){double elapsed=(Double)c[f+1],time=loco||seq.equals("bt_combat_idle")?elapsed:elapsed*duration/(Double)c[5];motion.sample(seq,time,params,pose);adapter.apply(pose);int shin=n.get("cube_shin_"+side),hip=n.get("cube_thigh_"+side),foot=n.get("cube_foot_"+side);before[f]=local(model,pose,shin);source[f]=local(model,pose,n.get("def_"+s+"_knee"));Vector3f a=pose.world[hip].getTranslation(new Vector3f()),b=pose.world[shin].getTranslation(new Vector3f()),e=pose.world[foot].getTranslation(new Vector3f());Vector3f direction=new Vector3f(e).sub(a).normalize(),pole=new Vector3f(b).sub(a);pole.fma(-pole.dot(direction),direction);float poleLength=pole.length();float left=loco?motion.footContact(seq,time/duration,"left"):1,right=loco?motion.footContact(seq,time/duration,"right"):1;float sole=adapter.soleHeight(pose,side);adapter.groundFeet(pose,0,0,left,right);after[f]=local(model,pose,shin);samples.add(Map.of("elapsed",elapsed,"source_seconds",time,"raw_knee_pole_length_m",poleLength,"raw_knee_pole",new float[]{pole.x,pole.y,pole.z},"raw_sole_y",sole,"ground_contact_weight",side.equals("left")?left:right));
   }
   results.add(Map.of("sequence",seq,"side",side,"pre_ik_local_knee_step_degrees",angle(before[0],before[1]),"post_ik_local_knee_step_degrees",angle(after[0],after[1]),"source_local_knee_step_degrees",angle(source[0],source[1]),"samples",samples));
  }
  Files.writeString(project.resolve("qa/body_temporal_forensics.json"),new GsonBuilder().setPrettyPrinting().create().toJson(results));System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(results));
 }
}
