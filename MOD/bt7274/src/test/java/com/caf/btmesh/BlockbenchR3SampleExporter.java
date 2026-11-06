package com.caf.btmesh;

import com.google.gson.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.DoubleFunction;
import org.joml.Vector3f;
import org.joml.Quaternionf;

/** QA-only sampling of the selected evaluator + candidate cube adapter. Never edits assets. */
public final class BlockbenchR3SampleExporter {
 static final Gson JSON=new GsonBuilder().disableHtmlEscaping().create();
 static final Map<String,Float> PARAMS=Map.of("aim_yaw",0f,"aim_pitch",0f);
 static final double IMPACT=.8366762399673462;
 // One-shot actions keep the original playback rate; only the most active
 // native window of the MC action length is used. Squeezing a 2.1 s dash into
 // 0.5 s sampled at 30 Hz is temporal aliasing, not an animation.
 static final Map<String,Double> WINDOWS=Map.of("bt_dash_forward_01",.033,"bt_dash_backward_01",.033,"bt_dash_left",.5,"bt_dash_right",.767,"at_MP_embark",1.2,"at_hotdrop_drop_2knee",8.367);
 static long frames,values;static float maxNormError;
 static record BodyTarget(NativeMotionPlayer.BodyPose pose,String key,boolean deploying,boolean airborne,boolean locomotion,boolean nativeNoTransition){}
 static final class Rig {
  final GltfSkin model;final JsonObject meta;final NativeMotionPlayer motion;final GltfSkin.Pose pose;final CubePoseAdapter adapter;
  final Vector3f[] translations,scales;final Quaternionf[] rotations;
  Rig(Path glb,Path metadata,boolean body)throws Exception{
   model=GltfSkin.load(Files.readAllBytes(glb));meta=JsonParser.parseString(Files.readString(metadata)).getAsJsonObject();motion=new NativeMotionPlayer(model,meta);pose=model.createPose();adapter=body?new CubePoseAdapter(model,meta.getAsJsonObject("cube_rig")):null;
   translations=(Vector3f[])field("t").get(pose);rotations=(Quaternionf[])field("r").get(pose);scales=(Vector3f[])field("s").get(pose);
  }
 }
 static Field field(String name)throws Exception{Field f=GltfSkin.Pose.class.getDeclaredField(name);f.setAccessible(true);return f;}
 static float[] vec(Vector3f v){return new float[]{v.x,v.y,v.z};}
 static float[] quat(Quaternionf q){return new float[]{q.x,q.y,q.z,q.w};}
 static String sha(Path p)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
 static void write(Path p,Object value)throws Exception{Files.createDirectories(p.getParent());try(Writer w=Files.newBufferedWriter(p)){JSON.toJson(value,w);}}
 static Map<String,Object> skeleton(Rig r,int[] targets,boolean body){
  List<Object> nodes=new ArrayList<>();for(int i=0;i<r.model.nodes.length;i++){var n=r.model.nodes[i];var value=new LinkedHashMap<String,Object>();value.put("index",i);value.put("name",n.name);value.put("parent",n.parent);value.put("translation",vec(n.translation));value.put("rotation_xyzw",quat(n.rotation));value.put("scale",vec(n.scale));if(n.matrix!=null)value.put("matrix_column_major",n.matrix.get(new float[16]));nodes.add(value);}
  List<Object> targetsInfo=new ArrayList<>();for(int i:targets){var n=r.model.nodes[i];targetsInfo.add(Map.of("node",i,"name",n.name,"author_group",body?n.name.replaceFirst("^cube_",""):n.name,"parent",n.parent));}
  var result=new LinkedHashMap<String,Object>();result.put("nodes",nodes);result.put("targets",targetsInfo);result.put("frame_layout","Each frame is target-major flat TRS: tx,ty,tz,qx,qy,qz,qw,sx,sy,sz; local to actual glTF parent; no Euler conversion");result.put("source_attachments",r.meta.getAsJsonArray("attachments"));result.put("skins",Arrays.stream(r.model.skins).map(s->Map.of("joints",s.joints(),"inverse_bind_column_major",Arrays.stream(s.inverseBind()).map(m->m.get(new float[16])).toList())).toList());if(body)result.put("cube_rig",r.meta.getAsJsonObject("cube_rig"));return result;
 }
 static float[] frame(Rig r,int[] target){float[] out=new float[target.length*10];int k=0;for(int i:target){Vector3f t=r.translations[i],s=r.scales[i];Quaternionf q=r.rotations[i];float err=Math.abs(q.lengthSquared()-1);maxNormError=Math.max(maxNormError,err);for(float v:new float[]{t.x,t.y,t.z,q.x,q.y,q.z,q.w,s.x,s.y,s.z}){if(!Float.isFinite(v))throw new AssertionError("nonfinite "+r.model.nodes[i].name);out[k++]=v;values++;}}frames++;return out;}
 static List<Double> times(double duration){List<Double> out=new ArrayList<>();int count=(int)Math.floor(duration*30+1e-9);for(int i=0;i<=count;i++){double t=i/30.;if(t<duration-1e-10)out.add(t);}out.add(duration);return out;}
 static void sample(Path folder,String name,double duration,boolean loop,String semantic,int[] targets,Rig r,java.util.function.DoubleConsumer eval,List<Object> index)throws Exception{
  List<Double> ts=times(duration);List<float[]> data=new ArrayList<>();for(double t:ts){eval.accept(t);data.add(frame(r,targets));}
  String file=name+".json";var clip=new LinkedHashMap<String,Object>();clip.put("name",name);clip.put("duration",duration);clip.put("loop",loop);clip.put("sample_rate_hz",30);clip.put("semantics",semantic);clip.put("times",ts);clip.put("target_count",targets.length);clip.put("frames",data);write(folder.resolve(file),clip);index.add(Map.of("name",name,"file",file,"duration",duration,"frame_count",ts.size(),"loop",loop,"semantics",semantic));System.out.println(name+" "+ts.size()+" frames / "+targets.length+" targets");
 }
 static final class BodySimulation {
  final Rig r;NativeMotionPlayer.PoseSnapshot last,from;CubePoseAdapter.WeaponReleaseAnchor anchor;String key;double changed;float grip=1,gripFrom=1,support=1,supportFrom=1;
  BodySimulation(Rig rig){r=rig;r.motion.sample("bt_combat_idle",0,PARAMS,r.pose);r.adapter.apply(r.pose);r.adapter.alignWeaponToRightGrip(r.pose,1);anchor=r.adapter.captureWeaponRelease(r.pose);last=r.motion.snapshotCurrent();}
  void step(double time,BodyTarget target){
   var p=target.pose;float requested=(p.sequence().startsWith("bt_combat_")||p.sequence().startsWith("at_doomed_")||p.sequence().equals("at_Crouch_Aim"))&&!p.overlay().equals("bt_vortex_arm_pose_add")?1:0;
   if(key==null&&(target.deploying||target.airborne))support=0;
   if(!Objects.equals(key,target.key)){
    r.motion.sampleBodyFromSnapshot(last,p,0,PARAMS,r.pose);r.adapter.apply(r.pose);r.adapter.applyWeaponRelease(r.pose,anchor);r.adapter.alignWeaponToRightGrip(r.pose,grip);anchor=r.adapter.captureWeaponRelease(r.pose);from=last;key=target.key;changed=time;gripFrom=grip;supportFrom=support;
   }
   float blend=target.nativeNoTransition?1:(float)Math.max(0,Math.min(1,(time-changed)/.15));grip=target.deploying?0:gripFrom+(requested-gripFrom)*blend;
   if(!target.deploying&&from!=null&&blend<1)r.motion.sampleBodyFromSnapshot(from,p,blend,PARAMS,r.pose);
   else if(p.overlay().isEmpty())r.motion.sample(p.sequence(),p.seconds(),PARAMS,r.pose);
   else r.motion.sampleLayered(p.sequence(),p.seconds(),p.overlay(),p.overlaySeconds(),p.overlayDuration(),p.overlayWeight(),PARAMS,r.pose);
   last=r.motion.snapshotCurrent();r.adapter.apply(r.pose);
   boolean groundHeightState=(p.sequence().startsWith("bt_combat_")||p.sequence().equals("bt_casual_idle")||p.sequence().equals("at_Crouch_Aim")||p.sequence().startsWith("at_doomed_"))&&!target.deploying&&!target.airborne;
   if(groundHeightState)r.adapter.retargetGroundedPelvisHeight(r.pose);
   boolean twoFootStance=p.sequence().equals("bt_combat_idle")||p.sequence().equals("bt_casual_idle")||p.sequence().equals("at_Crouch_Aim")||p.sequence().equals("at_doomed_idle");
   if(twoFootStance&&!target.deploying&&!target.airborne)r.adapter.fitTwoFootStance(r.pose,0);
   r.adapter.applyWeaponRelease(r.pose,anchor);r.adapter.alignWeaponToRightGrip(r.pose,grip);
   float requestedSupport=groundHeightState&&!p.overlay().equals("bt_vortex_arm_pose_add")?1:0;support=supportFrom+(requestedSupport-supportFrom)*blend;
   boolean reloading=p.overlay().equals("bt_reload_additive");
   if(reloading){r.adapter.stabilizeReloadWeapon(r.pose);r.adapter.applyMagazineReload(r.pose,(float)(p.overlaySeconds()/Math.max(.001,p.overlayDuration())),p.overlayWeight());}
   else if(support>0)r.adapter.fitWeaponSupport(r.pose,support);
   if(groundHeightState){double cycle=target.locomotion?p.seconds()/r.motion.sequenceDuration(p.sequence(),PARAMS):0;float left=target.locomotion?r.motion.footContact(p.sequence(),cycle,"left"):1,right=target.locomotion?r.motion.footContact(p.sequence(),cycle,"right"):1;r.adapter.groundFeet(r.pose,0,0,left,right);}
  }
 }
 static void bodyClip(Path folder,List<Object> index,Rig r,int[] targets,String name,String seq,double duration,boolean loop,boolean locomotion,boolean airborne,boolean deploying,boolean nativeTime)throws Exception{
  double source=r.motion.sequenceDuration(seq,PARAMS);BodySimulation simulation=new BodySimulation(r);sample(folder,name,duration,loop,"Review fix: actual anatomical source endpoint directions with evaluated twist, authored lengths/hydraulics, SP left main grip + MC chest/fore-end contact adaptation; "+(airborne||deploying?"terrain IK disabled":"flat ground Y=0; source foot-contact weights, only near-floor reachable nonsingular small IK")+"; "+(nativeTime?"native timing, steady state without state-entry blend":"MC timing + 0.15s entry snapshot crossfade; entity physics omitted"),targets,r,t->simulation.step(t,new BodyTarget(new NativeMotionPlayer.BodyPose(seq,WINDOWS.containsKey(seq)?WINDOWS.get(seq)+t:t*source/duration),seq,deploying,airborne,locomotion,nativeTime)),index);
 }
 static void body(Path out,Rig r)throws Exception{
  JsonArray binds=r.meta.getAsJsonObject("cube_rig").getAsJsonArray("bindings");int[] targets=new int[binds.size()];for(int i=0;i<targets.length;i++)targets[i]=binds.get(i).getAsJsonObject().get("target_node").getAsInt();write(out.resolve("skeleton.json"),skeleton(r,targets,true));List<Object> clips=new ArrayList<>();
  for(String[] pair:new String[][]{{"idle_casual","bt_casual_idle"},{"idle_combat","bt_combat_idle"},{"walk_forward","bt_combat_walk_forward"},{"run_combat","bt_combat_run_forward"},{"sprint_noaim","bt_combat_sprint_forward_noaim"},{"crouch_aim","at_Crouch_Aim"},{"doomed_idle","at_doomed_idle"},{"doomed_walk","at_doomed_walk_F"}}){String seq=pair[1];bodyClip(out,clips,r,targets,pair[0],seq,r.motion.sequenceDuration(seq,PARAMS),true,pair[0].contains("walk")||pair[0].contains("run")||pair[0].contains("sprint"),false,false,true);}
  for(String[] pair:new String[][]{{"dash_forward_mc","bt_dash_forward_01"},{"dash_back_mc","bt_dash_backward_01"},{"dash_left_mc","bt_dash_left"},{"dash_right_mc","bt_dash_right"},{"melee_mc","at_player_melee_punch_01"},{"embark_mc","at_MP_embark"},{"disembark_mc","at_dismount_stand"},{"death_mc","at_MP_death_explosion"}}){double d=pair[0].startsWith("dash")?.5:pair[0].equals("embark_mc")?2.6:pair[0].equals("death_mc")?2.9:1.2;bodyClip(out,clips,r,targets,pair[0],pair[1],d,false,false,false,false,false);}
  for(String seq:List.of("at_hotdrop_drop_2knee","at_hotdrop_quickstand","at_MP_embark","at_dismount_stand","at_player_melee_punch_01"))bodyClip(out,clips,r,targets,seq+"_native",seq,r.motion.sequenceDuration(seq,PARAMS),false,false,true,true,true);
  for(boolean walk:List.of(false,true))for(boolean reload:List.of(false,true)){
   String base=walk?"bt_combat_walk_forward":"bt_combat_idle",overlay=reload?"bt_reload_additive":"bt_fire_additive",name=(walk?"walk_":"idle_")+(reload?"reload_layered":"fire_layered");double duration=reload?2.6:.3;BodySimulation sim=new BodySimulation(r);
   sample(out,name,duration,false,"Selected source gesture over locomotion (may be ABS or DELTA; see metadata), MC action timing, 0.15s interruption snapshot; candidate anatomical/finger retarget + grounded stance; weapon role adaptation still under review",targets,r,t->sim.step(t,new BodyTarget(new NativeMotionPlayer.BodyPose(base,t,overlay,t,duration,1),name,false,false,walk,false)),clips);
  }
  BodySimulation shield=new BodySimulation(r);sample(out,"vortex_enter_hold_exit",2,false,"Original static ABSOLUTE weighted source RIGHT-arm body pose; explicit MC 0.15s snapshot entry/exit. SP left main grip retained; right shield arm source pose; chest support disabled during active shield",targets,r,t->{boolean active=t<1.7;var p=active?new NativeMotionPlayer.BodyPose("bt_combat_idle",t,"bt_vortex_arm_pose_add",Math.min(.2,t),.2,1):new NativeMotionPlayer.BodyPose("bt_combat_idle",t);shield.step(t,new BodyTarget(p,active?"vortex":"idle",false,false,false,false));},clips);
  BodySimulation drop=new BodySimulation(r);double dd=r.motion.sequenceDuration("at_hotdrop_drop_2knee",PARAMS),rise=r.motion.sequenceDuration("at_hotdrop_quickstand",PARAMS);sample(out,"titanfall_mc_full",5.5,false,"Runtime timing: airborne3s drop[0,impact0.83667624], settle0.35s drop[impact,1], quickstand1.15s, ready1s. Deployment bypasses body crossfade/grounding; no entity vertical physics/camera/HUD",targets,r,t->{BodyTarget target;if(t<3)target=new BodyTarget(new NativeMotionPlayer.BodyPose("at_hotdrop_drop_2knee",WINDOWS.get("at_hotdrop_drop_2knee")+t),"drop",true,true,false,false);else if(t<3.35)target=new BodyTarget(new NativeMotionPlayer.BodyPose("at_hotdrop_drop_2knee",WINDOWS.get("at_hotdrop_drop_2knee")+3+(t-3)/.35*(dd-WINDOWS.get("at_hotdrop_drop_2knee")-3)),"land_settle",true,false,false,false);else if(t<4.5)target=new BodyTarget(new NativeMotionPlayer.BodyPose("at_hotdrop_quickstand",(t-3.35)/1.15*rise),"land_rise",true,false,false,false);else target=new BodyTarget(new NativeMotionPlayer.BodyPose("bt_combat_idle",t-4.5),"ready",false,false,false,false);drop.step(t,target);},clips);
  write(out.resolve("index.json"),clips);
 }
 static void fp(Path out,Rig r)throws Exception{
  int[] targets=new int[r.meta.getAsJsonArray("bones").size()];for(int i=0;i<targets.length;i++)targets[i]=i;write(out.resolve("skeleton.json"),skeleton(r,targets,false));List<Object> clips=new ArrayList<>();JsonArray anims=r.meta.getAsJsonArray("animations"),seqs=r.meta.getAsJsonArray("sequences");
  for(int ai=0;ai<anims.size();ai++){
   JsonObject chosen=null;int blendIndex=0;for(JsonElement raw:seqs){var q=raw.getAsJsonObject();JsonArray indices=q.getAsJsonArray("animations");for(int bi=0;bi<indices.size();bi++)if(indices.get(bi).getAsInt()==ai){chosen=q;blendIndex=bi;break;}if(chosen!=null)break;}
   if(chosen==null)continue;String seq=chosen.get("name").getAsString();String label=String.format(Locale.ROOT,"%02d_",ai)+seq+(blendIndex>0?"_ads":"");Map<String,Float> params=Map.of("ads_blend",blendIndex>0?1f:0f);double duration=r.motion.sequenceDuration(seq,params);boolean delta=(chosen.get("flags").getAsInt()&4)!=0,offhand=seq.startsWith("vortex_fp_");
   sample(out,label,duration,(chosen.get("flags").getAsInt()&1)!=0,"Final original sequence evaluation with source weights/layers/pose blend; "+(offhand?"absolute masked Vortex left chain over XO16 idle":delta?"source DELTA/POST layered over ads_in absolute reference":"absolute source sequence")+"; source bone locals, original inch units. Rebuild pure cuboids from final GLB bind vertices, not body author pivots",targets,r,t->{if(offhand){r.motion.sampleFirstPerson(NativeMotionPlayer.FirstPersonState.IDLE,0,0,0,-1,r.pose);r.motion.applyFirstPersonOffhand(seq,t,1,r.pose);}else if(delta)r.motion.sampleLayered("ads_in_seq",0,seq,t,0,1,params,r.pose);else r.motion.sample(seq,t,params,r.pose);},clips);
  }
  sample(out,"runtime_vortex_full",2,false,"Exact production FP left-chain transitions: draw0.4s with0.1s MC weight fade, original hold until1.5s, original holster0.1667s with final0.1s fade, then XO16 idle. CAMERA retained in skeleton",targets,r,t->{r.motion.sampleFirstPerson(NativeMotionPlayer.FirstPersonState.IDLE,t,0,t,-1,r.pose);if(t<.4)r.motion.applyFirstPersonOffhand("vortex_fp_draw",t,(float)Math.min(1,t/.1),r.pose);else if(t<1.5)r.motion.applyFirstPersonOffhand("vortex_fp_hold",t-.4,1,r.pose);else if(t<1.5+1/6.)r.motion.applyFirstPersonOffhand("vortex_fp_holster",t-1.5,(float)Math.min(1,(1/6.-(t-1.5))/.1),r.pose);},clips);
  write(out.resolve("index.json"),clips);
 }
 public static void main(String[] args)throws Exception{
  Path project=Path.of(args[0]),out=Path.of(args[1]),assets=project.resolve("mod_mesh/src/main/resources/assets/bt7274");Files.createDirectories(out);Path bodyGlb=assets.resolve("models/native/body.glb"),fpGlb=assets.resolve("models/native/fp.glb"),bodyMeta=assets.resolve("motion/body.json"),fpMeta=assets.resolve("motion/fp.json");
  Rig body=new Rig(bodyGlb,bodyMeta,true);body(out.resolve("body"),body);Rig fp=new Rig(fpGlb,fpMeta,false);fp(out.resolve("fp"),fp);
  var manifest=new LinkedHashMap<String,Object>();manifest.put("format","caf_blockbench_final_evaluated_trs_v1");manifest.put("sample_rate_hz",30);manifest.put("body_glb_sha256",sha(bodyGlb));manifest.put("fp_glb_sha256",sha(fpGlb));manifest.put("body_motion_sha256",sha(bodyMeta));manifest.put("fp_motion_sha256",sha(fpMeta));manifest.put("body_author",project.resolve(body.meta.getAsJsonObject("cube_rig").getAsJsonArray("bindings").asList().stream().anyMatch(b->b.getAsJsonObject().get("author_group").getAsString().equals("magazine"))?"model/BT7274_Cube_R3_动作修订.bbmodel":"model/BT7274_Cube_R3.bbmodel").toString());manifest.put("fp_author",project.resolve("model/BT7274_Cube_R3_FP.bbmodel").toString());manifest.put("scope","Offline selected source evaluator + candidate authored cube retarget/rigid skin. Candidate adapter may differ from delivered production JAR; see QA status. No entity physics, server/root movement, world terrain other than stated flat ground, cockpit/HUD, sound or Minecraft rendering. Source quaternions XYZW, column-major matrices. Body target local translations are author units under wrapper; FP source locals are original inches under SourceToMetres wrapper. Exact 30Hz times plus final endpoint, no simplification.");manifest.put("frames",frames);manifest.put("sampled_finite_values",values);manifest.put("max_quaternion_length_squared_error",maxNormError);write(out.resolve("manifest.json"),manifest);System.out.println("DONE "+frames+" frames, "+values+" finite values");
 }
}
