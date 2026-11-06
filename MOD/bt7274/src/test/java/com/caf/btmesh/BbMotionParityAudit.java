package com.caf.btmesh;

import com.google.gson.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Independent saved-Blockbench/resource/player parity audit. No game, renderer, or source retargeter. */
public final class BbMotionParityAudit {
    record Group(String name,String uuid,double[] p,double[] r,double[] s,int parent) {}
    record Key(double t,double[] v) {}
    record Track(int group,int channel,List<Key> keys) {}
    record Clip(String name,String uuid,double duration,int loop,Map<String,Track> tracks) {}
    record Library(String hash,List<Group> groups,List<Clip> clips) {}
    static long checks,rawKeys,frameChecks,matrixChecks,denseFrames;
    static double maximumChannelError,maximumMatrixError,maximumQuaternionMatrixError,maximumFloatWorldError,maximumFloatTranslationError;
    static final double CHANNEL_TOL=2e-11,MATRIX_TOL=1e-10;
    static final List<Map<String,Object>> results=new ArrayList<>();
    static String str(DataInputStream in)throws IOException {int n=in.readInt();require(n>=0&&n<10000000,"string length");return new String(in.readNBytes(n),StandardCharsets.UTF_8);}
    static double[] vec(DataInputStream in)throws IOException {return new double[]{in.readDouble(),in.readDouble(),in.readDouble()};}
    static void require(boolean ok,String what){checks++;if(!ok)throw new AssertionError(what);}
    static void close(double a,double b,double eps,String what){require(Double.isFinite(a)&&Double.isFinite(b)&&Math.abs(a-b)<=eps,what+" expected="+a+" actual="+b);}
    static String hash(byte[] data)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
    static String key(int g,int ch){return g+":"+ch;}
    static double[] triple(JsonArray a){return new double[]{a.get(0).getAsDouble(),a.get(1).getAsDouble(),a.get(2).getAsDouble()};}
    static Library binary(Path path,Map<String,Integer> parents)throws Exception{
        try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))){
            require(Arrays.equals(in.readNBytes(8),"BTBBC001".getBytes(StandardCharsets.US_ASCII)),"binary magic/version");
            String sha=str(in);int ng=in.readInt();require(ng>0&&ng<10000,"group count bound");List<Group> groups=new ArrayList<>();
            for(int i=0;i<ng;i++){String name=str(in),uuid=str(in);groups.add(new Group(name,uuid,vec(in),vec(in),vec(in),parents.getOrDefault(uuid,-1)));}
            int nc=in.readInt();require(nc>0&&nc<10000,"clip count bound");List<Clip> clips=new ArrayList<>();
            for(int i=0;i<nc;i++){
                String name=str(in),uuid=str(in);double duration=in.readDouble();int loop=in.readUnsignedByte(),nt=in.readInt();
                require(Double.isFinite(duration)&&duration>0&&loop<=2&&nt>=0&&nt<=ng*3,"clip header "+name);
                Map<String,Track> tracks=new LinkedHashMap<>();
                for(int j=0;j<nt;j++){
                    int group=in.readInt(),channel=in.readUnsignedByte(),nk=in.readInt();
                    require(group>=0&&group<ng&&channel<=2&&nk>0&&nk<1000000,"track header "+name);
                    List<Key> keys=new ArrayList<>();double previous=-Double.MAX_VALUE;
                    for(int k=0;k<nk;k++){double t=in.readDouble();double[] v=vec(in);require(Double.isFinite(t)&&t>=previous,"nondecreasing key clock "+name);for(double x:v)require(Double.isFinite(x),"finite value "+name);keys.add(new Key(t,v));previous=t;rawKeys++;}
                    require(tracks.put(key(group,channel),new Track(group,channel,keys))==null,"no duplicate tracks "+name);
                }
                clips.add(new Clip(name,uuid,duration,loop,tracks));
            }
            require(in.read()==-1,"no unexpected trailing bytes");return new Library(sha,groups,clips);
        }
    }
    static Library author(JsonObject saved,JsonObject geometry,String sha)throws Exception{
        JsonArray gs=geometry.getAsJsonArray("groups");Map<String,Integer> indices=new HashMap<>();for(int i=0;i<gs.size();i++)indices.put(gs.get(i).getAsJsonObject().get("uuid").getAsString(),i);
        List<Group> groups=new ArrayList<>();
        for(JsonElement e:gs){var g=e.getAsJsonObject();double[] r=triple(g.getAsJsonArray("rotation"));JsonElement parent=g.get("parent");groups.add(new Group(g.get("name").getAsString(),g.get("uuid").getAsString(),triple(g.getAsJsonArray("position")),r,new double[]{1,1,1},parent==null||parent.isJsonNull()?-1:indices.get(parent.getAsString())));require(g.get("order").getAsString().equals("ZYX"),"native BB ZYX order");}
        List<Clip> clips=new ArrayList<>();
        for(JsonElement ae:saved.getAsJsonArray("animations")){
            var a=ae.getAsJsonObject();Map<String,Track> tracks=new LinkedHashMap<>();
            for(var be:a.getAsJsonObject("animators").entrySet()){
                var b=be.getValue().getAsJsonObject();if(!b.has("keyframes")||b.getAsJsonArray("keyframes").isEmpty())continue;
                require(indices.containsKey(be.getKey()),"every animated bone exported "+be.getKey());int gi=indices.get(be.getKey());
                require(!b.has("rotation_global")||!b.get("rotation_global").getAsBoolean(),"no global rotation");require(!b.has("quaternion_interpolation")||!b.get("quaternion_interpolation").getAsBoolean(),"no source slerp");
                Map<Integer,List<Key>> channels=new TreeMap<>();
                for(var ke:b.getAsJsonArray("keyframes")){
                    var k=ke.getAsJsonObject();int ch=switch(k.get("channel").getAsString()){case "position"->0;case "rotation"->1;case "scale"->2;default->throw new AssertionError("unsupported animated channel");};
                    require(k.get("interpolation").getAsString().equals("linear"),"linear curve contract");require(k.getAsJsonArray("data_points").size()==1,"single numeric data point");var dp=k.getAsJsonArray("data_points").get(0).getAsJsonObject();double[] v=new double[3];for(int i=0;i<3;i++)v[i]=dp.get("xyz".substring(i,i+1)).getAsDouble();channels.computeIfAbsent(ch,ignored->new ArrayList<>()).add(new Key(k.get("time").getAsDouble(),v));
                }
                for(var ch:channels.entrySet()){ch.getValue().sort(Comparator.comparingDouble(Key::t));tracks.put(key(gi,ch.getKey()),new Track(gi,ch.getKey(),ch.getValue()));}
            }
            String loop=a.get("loop").getAsString();clips.add(new Clip(a.get("name").getAsString(),a.get("uuid").getAsString(),a.get("length").getAsDouble(),switch(loop){case "once"->0;case "loop"->1;case "hold"->2;default->throw new AssertionError("loop mode "+loop);},tracks));
        }
        return new Library(sha,groups,clips);
    }
    static void rawParity(Library expected,Library actual){
        require(expected.hash.equals(actual.hash),"binary identifies exact saved BB SHA256");require(expected.groups.size()==actual.groups.size(),"group count");require(expected.clips.size()==actual.clips.size(),"clip count");
        for(int i=0;i<expected.groups.size();i++){var e=expected.groups.get(i);var a=actual.groups.get(i);require(e.name.equals(a.name)&&e.uuid.equals(a.uuid),"group identity/order "+i);for(int c=0;c<3;c++){close(e.p[c],a.p[c],1e-12,"native base position");close(e.r[c],a.r[c],1e-12,"native base Euler");close(e.s[c],a.s[c],1e-12,"native base scale");}}
        for(int i=0;i<expected.clips.size();i++){
            var e=expected.clips.get(i);var a=actual.clips.get(i);require(e.name.equals(a.name)&&e.uuid.equals(a.uuid),"clip identity/order "+i);close(e.duration,a.duration,0,"duration "+e.name);require(e.loop==a.loop,"loop mode "+e.name);require(e.tracks.keySet().equals(a.tracks.keySet()),"track set "+e.name);
            for(String id:e.tracks.keySet()){var et=e.tracks.get(id);var at=a.tracks.get(id);require(et.keys.size()==at.keys.size(),"unmodified key count "+e.name+" "+id);for(int k=0;k<et.keys.size();k++){var ek=et.keys.get(k);var ak=at.keys.get(k);close(ek.t,ak.t,0,"lossless key time");for(int c=0;c<3;c++)close(ek.v[c],ak.v[c],0,"lossless key value");}}
        }
    }
    static double clock(Clip c,double seconds){if(c.loop!=1)return Math.max(0,Math.min(c.duration,seconds));double t=seconds%c.duration;return t<0?t+c.duration:t;}
    static double[] interpolate(List<Key> keys,double t){
        int low=0,high=keys.size()-1;if(t<=keys.get(low).t)return keys.get(low).v.clone();if(t>=keys.get(high).t)return keys.get(high).v.clone();
        while(high-low>1){int middle=(low+high)>>>1;if(keys.get(middle).t<=t)low=middle;else high=middle;}
        Key a=keys.get(low),b=keys.get(high);if(Math.abs(a.t-t)<=1.0/1200)return a.v.clone();if(Math.abs(b.t-t)<=1.0/1200)return b.v.clone();double f=(t-a.t)/(b.t-a.t);return new double[]{a.v[0]*(1-f)+b.v[0]*f,a.v[1]*(1-f)+b.v[1]*f,a.v[2]*(1-f)+b.v[2]*f};
    }
    static double[][] reference(Library lib,Clip clip,double seconds){
        int n=lib.groups.size();double[][] frame={new double[n*3],new double[n*3],new double[n*3]};for(int g=0;g<n;g++){Group b=lib.groups.get(g);System.arraycopy(b.p,0,frame[0],g*3,3);System.arraycopy(b.r,0,frame[1],g*3,3);System.arraycopy(b.s,0,frame[2],g*3,3);}
        double t=clock(clip,seconds);for(Track tr:clip.tracks.values()){double[] v=interpolate(tr.keys,t);for(int c=0;c<3;c++){int i=tr.group*3+c;if(tr.channel==2)frame[2][i]*=v[c]==0?1e-5:v[c];else frame[tr.channel][i]+=tr.channel==1?Math.toRadians(v[c]):v[c];}}return frame;
    }
    // Hand-derived Rz*Ry*Rx, row-major. Independent of JOML or Three.js.
    static double[] local(double[][] f,int g){int i=g*3;double x=f[1][i],y=f[1][i+1],z=f[1][i+2],cx=Math.cos(x),sx=Math.sin(x),cy=Math.cos(y),sy=Math.sin(y),cz=Math.cos(z),sz=Math.sin(z),a=f[2][i],b=f[2][i+1],c=f[2][i+2];return new double[]{cz*cy*a,(cz*sy*sx-sz*cx)*b,(cz*sy*cx+sz*sx)*c,f[0][i],sz*cy*a,(sz*sy*sx+cz*cx)*b,(sz*sy*cx-cz*sx)*c,f[0][i+1],-sy*a,cy*sx*b,cy*cx*c,f[0][i+2],0,0,0,1};}
    static double[] localJoml(double[][] f,int g){int i=g*3;var m=new org.joml.Matrix4d().translation(f[0][i],f[0][i+1],f[0][i+2]).rotateZYX(f[1][i+2],f[1][i+1],f[1][i]).scale(f[2][i],f[2][i+1],f[2][i+2]);double[] out=new double[16];for(int r=0;r<4;r++)for(int c=0;c<4;c++)out[r*4+c]=m.get(c,r);return out;}
    static double[] worldJoml(List<Group> groups,double[][] f,int i,double[][] cache){if(cache[i]!=null)return cache[i];int p=groups.get(i).parent;return cache[i]=p<0?localJoml(f,i):multiply(worldJoml(groups,f,p,cache),localJoml(f,i));}
    static double[] multiply(double[] a,double[] b){double[] r=new double[16];for(int i=0;i<4;i++)for(int j=0;j<4;j++)for(int k=0;k<4;k++)r[i*4+j]+=a[i*4+k]*b[k*4+j];return r;}
    static double[] world(List<Group> groups,double[][] f,int i,double[][] cache){if(cache[i]!=null)return cache[i];int p=groups.get(i).parent;return cache[i]=p<0?local(f,i):multiply(world(groups,f,p,cache),local(f,i));}
    static final class PlayerBridge {
        final Object player,frame,pose;final Method sample,submit;final int[] nodes;final double unitScale;final Field p,r,s,q;
        PlayerBridge(Path bin,Path root)throws Exception{
            Class<?> type=Class.forName("com.caf.btmesh.BbMotionPlayer");
            // The runtime class documents its construction through the small adapter below.
            player=create(type,Files.readAllBytes(bin),Files.readAllBytes(bin.getParent().getParent().resolve("models/native/body.glb")));
            Method make=type.getMethod("newFrame");frame=make.invoke(player);sample=type.getMethod("sample",int.class,double.class,frame.getClass());p=frame.getClass().getField("position");r=frame.getClass().getField("euler");s=frame.getClass().getField("scale");q=frame.getClass().getField("rotation");
            Class<?> skin=Class.forName("com.caf.btmesh.GltfSkin"),poseType=Class.forName("com.caf.btmesh.GltfSkin$Pose");Object model=type.getField("model").get(player);try{pose=java.lang.invoke.MethodHandles.publicLookup().findVirtual(skin,"createPose",java.lang.invoke.MethodType.methodType(poseType)).invoke(model);}catch(Throwable error){throw new RuntimeException(error);}submit=type.getMethod("submit",frame.getClass(),poseType);Object[] bones=(Object[])type.getField("bones").get(player);nodes=new int[bones.length];for(int i=0;i<bones.length;i++)nodes[i]=((Number)bones[i].getClass().getMethod("node").invoke(bones[i])).intValue();
            Path report=root.resolve("qa/bb_runtime_export.json");
            // The exact exporter unit value is authoritative; no guessed block conversion.
            unitScale=JsonParser.parseString(Files.readString(report)).getAsJsonObject().get("unit_scale_metres").getAsDouble();
        }
        static Object create(Class<?> type,byte[] bytes,byte[] glb)throws Exception{Class<?> skin=Class.forName("com.caf.btmesh.GltfSkin");Object model;try{model=java.lang.invoke.MethodHandles.publicLookup().findStatic(skin,"load",java.lang.invoke.MethodType.methodType(skin,byte[].class)).invoke(glb);}catch(Throwable error){throw new RuntimeException(error);}return type.getConstructor(skin,byte[].class).newInstance(model,bytes);}
        org.joml.Matrix4f[] finalFloatWorld()throws Exception{submit.invoke(player,frame,pose);return (org.joml.Matrix4f[])pose.getClass().getField("world").get(pose);}
        org.joml.Quaternionf[] quaternions()throws Exception{return (org.joml.Quaternionf[])q.get(frame);}
        double[][] sample(int clip,double seconds)throws Exception{sample.invoke(player,clip,seconds,frame);return new double[][]{((double[])p.get(frame)).clone(),((double[])r.get(frame)).clone(),((double[])s.get(frame)).clone()};}
    }
    static void playerParity(Library lib,PlayerBridge player)throws Exception{
        for(int ci=0;ci<lib.clips.size();ci++){
            Clip clip=lib.clips.get(ci);TreeSet<Double> times=new TreeSet<>(List.of(-.2,0.0,clip.duration,clip.duration+.001,clip.duration+.25,clip.duration*2+.137));
            for(Track tr:clip.tracks.values())for(int i=0;i<tr.keys.size();i++){double t=tr.keys.get(i).t;times.add(t);if(i>0)times.add((tr.keys.get(i-1).t+t)*.5);}
            if(ci==10)for(int i=0;i<=250;i++)times.add(1.30+i*.001);
            long start=frameChecks;for(double t:times){
                double[][] expected=reference(lib,clip,t),actual=player.sample(ci+1,t);frameChecks++;if(ci==10&&t>=1.30&&t<=1.55)denseFrames++;
                for(int ch=0;ch<3;ch++){require(actual[ch].length==expected[ch].length,"frame array length");for(int i=0;i<expected[ch].length;i++){maximumChannelError=Math.max(maximumChannelError,Math.abs(expected[ch][i]-actual[ch][i]));close(expected[ch][i],actual[ch][i],CHANNEL_TOL,"player channel "+ci+" at "+t+" ch="+ch+" index="+i);}}
                var quaternions=player.quaternions();for(int g=0;g<lib.groups.size();g++){double[][] unit={new double[expected[0].length],expected[1],new double[expected[2].length]};Arrays.fill(unit[2],1);double[] rotation=local(unit,g);var actualRotation=new org.joml.Matrix3d().rotation(quaternions[g]);for(int row=0;row<3;row++)for(int column=0;column<3;column++){double error=Math.abs(rotation[row*4+column]-actualRotation.get(column,row));maximumQuaternionMatrixError=Math.max(maximumQuaternionMatrixError,error);require(error<=4e-6,"actual player quaternion uses BB ZYX; clip="+ci+" t="+t+" group="+g+" error="+error);}}
                var floatWorld=player.finalFloatWorld();double u=player.unitScale;double[] wrapper={0,0,-u,0,0,u,0,0,u,0,0,0,0,0,0,1};
                // Every group, every sampled time, including nonzero rest-rotated knee rod groups.
                double[][] ew=new double[lib.groups.size()][],aw=new double[lib.groups.size()][];for(int i=0;i<lib.groups.size();i++){double[] e=world(lib.groups,expected,i,ew),a=worldJoml(lib.groups,actual,i,aw);for(int k=0;k<16;k++){maximumMatrixError=Math.max(maximumMatrixError,Math.abs(e[k]-a[k]));close(e[k],a[k],MATRIX_TOL,"world matrix "+ci+" at "+t+" group="+i);}double[] expectedMetres=multiply(wrapper,e);org.joml.Matrix4f actualFloat=floatWorld[player.nodes[i]];for(int row=0;row<4;row++)for(int col=0;col<4;col++){double error=Math.abs(expectedMetres[row*4+col]-actualFloat.get(col,row));maximumFloatWorldError=Math.max(maximumFloatWorldError,error);if(col==3&&row<3)maximumFloatTranslationError=Math.max(maximumFloatTranslationError,error);require(error<=1e-4,"actual GltfSkin.Pose float world bound clip="+ci+" t="+t+" bone="+i+" error="+error);}matrixChecks++;}
            }
            results.add(Map.of("clip",clip.name,"duration",clip.duration,"loop",clip.loop,"sampled_frames",frameChecks-start));
        }
    }
    public static void main(String[] args)throws Exception{
        Path root=args.length>0?Path.of(args[0]):Path.of("..").toAbsolutePath().normalize();Path bb=root.resolve("model/BT7274_BB当前修订.bbmodel"),geo=root.resolve("qa/bb_runtime_native_geometry.json"),bin=root.resolve("mod_mesh/src/main/resources/assets/bt7274/motion/body_bb.bin");
        byte[] saved=Files.readAllBytes(bb);JsonObject geometry=JsonParser.parseString(Files.readString(geo)).getAsJsonObject();Library expected=author(JsonParser.parseString(new String(saved,StandardCharsets.UTF_8)).getAsJsonObject(),geometry,hash(saved));Map<String,Integer> parentMap=new HashMap<>();for(Group g:expected.groups)parentMap.put(g.uuid,g.parent);Library actual=binary(bin,parentMap);rawParity(expected,actual);playerParity(expected,new PlayerBridge(bin,root));
        Map<String,Object> report=new LinkedHashMap<>();report.put("status","passed");report.put("scope","Exact saved BB and native geometry snapshot versus binary keys and actual BbMotionPlayer channels and independently constructed JOML ZYX world matrices; no game or visual acceptance asserted");report.put("bb_sha256",hash(saved));report.put("bin_sha256",hash(Files.readAllBytes(bin)));report.put("groups",actual.groups.size());report.put("clips",actual.clips.size());report.put("binary_keys",rawKeys);report.put("sampled_frames",frameChecks);report.put("world_matrices",matrixChecks);report.put("landing_dense_1.30_to_1.55_frames",denseFrames);report.put("max_channel_abs_error",maximumChannelError);report.put("max_matrix_abs_error",maximumMatrixError);report.put("max_player_float_quaternion_matrix_abs_error",maximumQuaternionMatrixError);report.put("max_actual_GltfSkin_Pose_float_world_abs_error",maximumFloatWorldError);report.put("max_actual_GltfSkin_Pose_translation_error_metres",maximumFloatTranslationError);report.put("actual_float_world_tolerance",1e-4);report.put("checks",checks);report.put("clips_checked",results);
        String json=new GsonBuilder().setPrettyPrinting().create().toJson(report);Files.writeString(root.resolve("qa/bb_runtime_parity_report.json"),json);System.out.println(json);
    }
}
