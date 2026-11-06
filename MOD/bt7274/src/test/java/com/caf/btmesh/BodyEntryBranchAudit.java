package com.caf.btmesh;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.joml.Quaternionf;
/** Regression for moving-target pi crossing in source snapshot transitions. */
public final class BodyEntryBranchAudit {
 static final Map<String,Float> P=Map.of("aim_yaw",0f,"aim_pitch",0f);
 static Quaternionf q(GltfSkin.Pose p,int i){return p.world[i].getUnnormalizedRotation(new Quaternionf()).normalize();}
 static double angle(Quaternionf a,Quaternionf b){return Math.toDegrees(2*Math.acos(Math.min(1,Math.abs(a.dot(b)))));}
 public static void main(String[]args)throws Exception{
  Path root=Path.of(args[0]),assets=root.resolve("mod_mesh/src/main/resources/assets/bt7274"),out=Path.of(args[1]);
  var model=GltfSkin.load(Files.readAllBytes(assets.resolve("models/native/body.glb")));var meta=JsonParser.parseString(Files.readString(assets.resolve("motion/body.json"))).getAsJsonObject();var motion=new NativeMotionPlayer(model,meta);var pose=model.createPose();var adapter=new CubePoseAdapter(model,meta.getAsJsonObject("cube_rig"));
  int ankle=-1,foot=-1;for(int i=0;i<model.nodes.length;i++){if(model.nodes[i].name.equals("def_r_ankle"))ankle=i;if(model.nodes[i].name.equals("cube_foot_right"))foot=i;}
  var result=new LinkedHashMap<String,Object>();double worst=0;
  for(int hz:new int[]{30,60,144})for(double phase:new double[]{0,.04,.1,.3}){
   motion.sample("bt_combat_idle",0,P,pose);var source=motion.snapshotCurrent();Quaternionf previous=null,previousCube=null;var samples=new ArrayList<Object>();double max=0,maxCube=0;
   for(int frame=0;frame<=Math.ceil(.2*hz);frame++){
    double t=frame/(double)hz;float alpha=(float)Math.min(1,t/.15);var target=new NativeMotionPlayer.BodyPose("at_player_melee_punch_01",phase+t/1.2);motion.sampleBodyFromSnapshot(source,target,alpha,P,pose);Quaternionf current=q(pose,ankle);double step=previous==null?0:angle(previous,current);previous=current;max=Math.max(max,step);adapter.apply(pose);Quaternionf cube=q(pose,foot);double cubeStep=previousCube==null?0:angle(previousCube,cube);previousCube=cube;maxCube=Math.max(maxCube,cubeStep);
    samples.add(Map.of("time",t,"alpha",alpha,"source_seconds",target.seconds(),"source_ankle_q",new float[]{current.x,current.y,current.z,current.w},"source_step_deg",step,"cube_foot_step_deg",cubeStep));
   }
   result.put(hz+"hz_phase_"+phase,Map.of("max_source_ankle_step_deg",max,"max_cube_foot_step_deg",maxCube,"samples",samples));worst=Math.max(worst,max);
  }
  // Snapshot ownership must survive another entity using the shared player.
  motion.sample("bt_combat_idle",0,P,pose);var a=motion.snapshotCurrent();motion.sample("bt_casual_idle",0,P,pose);var b=motion.snapshotCurrent();
  motion.sampleBodyFromSnapshot(a,new NativeMotionPlayer.BodyPose("at_player_melee_punch_01",1/36.),2/9f,P,pose);motion.sampleBodyFromSnapshot(b,new NativeMotionPlayer.BodyPose("bt_combat_idle",.03),.2f,P,pose);motion.sampleBodyFromSnapshot(a,new NativeMotionPlayer.BodyPose("at_player_melee_punch_01",1/18.),4/9f,P,pose);Quaternionf interleaved=q(pose,ankle);
  motion.sample("bt_combat_idle",0,P,pose);var isolated=motion.snapshotCurrent();motion.sampleBodyFromSnapshot(isolated,new NativeMotionPlayer.BodyPose("at_player_melee_punch_01",1/36.),2/9f,P,pose);motion.sampleBodyFromSnapshot(isolated,new NativeMotionPlayer.BodyPose("at_player_melee_punch_01",1/18.),4/9f,P,pose);double entityError=angle(interleaved,q(pose,ankle));
  var interrupted=motion.snapshotCurrent();Quaternionf before=q(pose,ankle);motion.sampleBodyFromSnapshot(interrupted,new NativeMotionPlayer.BodyPose("bt_combat_idle",.09),0,P,pose);double interruptError=angle(before,q(pose,ankle));
  result.put("ownership_and_interruption",Map.of("interleaved_entity_rotation_error_deg",entityError,"interruption_alpha0_error_deg",interruptError));
  Files.createDirectories(out.getParent());Files.writeString(out,new GsonBuilder().setPrettyPrinting().create().toJson(result));System.out.println("max entry ankle step across rates/phases: "+worst+" deg; entity error "+entityError+"; interrupt alpha0 error "+interruptError);
  if(worst>80||entityError>.1||interruptError>.1)throw new AssertionError("Body entry branch continuity regression");
  Path candidate=root.resolve("previews/blockbench_r3/entry_branch_candidate/body");Files.createDirectories(candidate);
  var rig=new BlockbenchR3SampleExporter.Rig(assets.resolve("models/native/body.glb"),assets.resolve("motion/body.json"),true);
  JsonArray bindings=rig.meta.getAsJsonObject("cube_rig").getAsJsonArray("bindings");int[] targets=new int[bindings.size()];for(int i=0;i<targets.length;i++)targets[i]=bindings.get(i).getAsJsonObject().get("target_node").getAsInt();
  BlockbenchR3SampleExporter.write(candidate.resolve("skeleton.json"),BlockbenchR3SampleExporter.skeleton(rig,targets,true));var clips=new ArrayList<Object>();
  BlockbenchR3SampleExporter.bodyClip(candidate,clips,rig,targets,"melee_mc","at_player_melee_punch_01",1.2,false,false,false,false,false);
  BlockbenchR3SampleExporter.write(candidate.resolve("index.json"),clips);
 }
}
