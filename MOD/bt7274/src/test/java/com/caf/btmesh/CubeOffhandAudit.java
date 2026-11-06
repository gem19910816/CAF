package com.caf.btmesh;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
/** Verifies original Vortex left-chain import preserves the main weapon/camera. */
public final class CubeOffhandAudit {
 public static void main(String[] args)throws Exception{
  var model=GltfSkin.load(Files.readAllBytes(Path.of(args[0])));var metadata=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();var motion=new NativeMotionPlayer(model,metadata);var pose=model.createPose();int checks=0;float maxMove=0;
  for(String seq:List.of("vortex_fp_draw","vortex_fp_hold","vortex_fp_holster")){
   if(!motion.hasSequence(seq))throw new AssertionError(seq);
   for(int frame=0;frame<=24;frame++){
    motion.sampleFirstPerson(NativeMotionPlayer.FirstPersonState.IDLE,0,0,0,-1,pose);Matrix4f[] original=Arrays.stream(pose.world).map(Matrix4f::new).toArray(Matrix4f[]::new);
    motion.applyFirstPersonOffhand(seq,frame*motion.sequenceDuration(seq,Map.of())/24,1,pose);
    for(int i=0;i<model.nodes.length;i++){
     String name=model.nodes[i].name;
     if(name.startsWith("def_r_")||List.of("def_c_base","jx_c_camera","jx_c_pov","jx_c_delta","def_c_intBase").contains(name)){checks++;if(!original[i].equals(pose.world[i],1e-6f))throw new AssertionError("offhand touched main/camera "+name);}
     if(name.equals("def_l_wrist"))maxMove=Math.max(maxMove,original[i].getTranslation(new Vector3f()).distance(pose.world[i].getTranslation(new Vector3f())));
     for(float value:pose.world[i].get(new float[16])){checks++;if(!Float.isFinite(value))throw new AssertionError("offhand finite");}
    }
   }
  }
  if(maxMove<.5f)throw new AssertionError("shield hand did not move");
  var result=Map.of("status","passed","assertions",checks,"max_left_wrist_motion_metres",maxMove,"source_sequences",List.of("draw_seq 0.4s","toss_hold_seq 4.0s loop","holster_seq 0.1667s"),"scope","Original Vortex FP left-chain curves, main hand/gun/camera unchanged; in-game visual acceptance separate");Files.writeString(Path.of(args[2]),new GsonBuilder().setPrettyPrinting().create().toJson(result));System.out.println("PASS CubeOffhandAudit "+checks+" assertions, left wrist displacement="+maxMove);
 }
}
