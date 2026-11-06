package com.caf.btmesh;

import com.google.gson.*;
import java.nio.file.*;
import java.lang.reflect.Method;
import java.util.*;
import org.joml.*;

/** Exercises real loader/sampler and writes native model joint/skin samples for independent comparison. */
public final class MeshNumericalAudit {
    private static int assertions;
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private static void close(float actual,float expected,String message){check(java.lang.Math.abs(actual-expected)<1e-4F,message+": "+actual+" expected "+expected);}
    private static float[] sample(GltfSkin.Channel channel,float time)throws Exception{
        Method m=GltfSkin.class.getDeclaredMethod("sample",GltfSkin.Channel.class,float.class,float[].class);m.setAccessible(true);float[] out=new float[4];m.invoke(null,channel,time,out);return out;
    }
    public static void main(String[] args)throws Exception{
        var step=new GltfSkin.Channel(0,"translation","STEP",new float[]{0,1,2},new float[]{0,0,0,10,20,30,20,40,60},3);
        close(sample(step,.999F)[0],0,"STEP holds previous value");close(sample(step,1)[0],10,"STEP exact key uses that key");close(sample(step,2)[0],20,"STEP final key");
        var linear=new GltfSkin.Channel(0,"translation","LINEAR",new float[]{0,2},new float[]{0,0,0,10,20,30},3);
        close(sample(linear,1)[1],10,"linear intermediate");close(sample(linear,-1)[2],0,"before first key clamps");
        var anti=new GltfSkin.Channel(0,"rotation","LINEAR",new float[]{0,1},new float[]{0,0,0,1,0,0,0,-1},4);
        close(java.lang.Math.abs(sample(anti,.5F)[3]),1,"antipodal quaternion shortest path");
        var rotation=new GltfSkin.Channel(0,"rotation","LINEAR",new float[]{0,1},new float[]{0,0,0,1,0,1,0,0},4);
        float[] qr=sample(rotation,.5F);close(qr[1],(float)java.lang.Math.sqrt(.5),"SLERP halfway y");close(qr[3],(float)java.lang.Math.sqrt(.5),"SLERP halfway w");
        var cubic=new GltfSkin.Channel(0,"translation","CUBICSPLINE",new float[]{0,2},new float[]{0,0,0,0,0,0,1,0,0,1,0,0,2,0,0,0,0,0},3);
        close(sample(cubic,1)[0],1,"Hermite scales tangent by interval");
        String hierarchy="{\"asset\":{\"version\":\"2.0\"},\"nodes\":[{\"translation\":[10,0,0],\"children\":[1]},{\"translation\":[0,2,0],\"scale\":[.1,.1,.1]}],\"skins\":[{\"joints\":[1]}]}";
        var simple=GltfSkin.load(hierarchy.getBytes(java.nio.charset.StandardCharsets.UTF_8));var sp=simple.createPose();sp.sample("",0,false);
        close(sp.world[1].m30(),10,"parent global translation");close(sp.world[1].m31(),2,"child global translation");close(sp.world[1].m00(),.1F,"root scale retained");
        Path modelPath=Path.of("F:/自制武器源文件/08_BT7274/research/native_bt/converted/titan_buddy/native_animated.glb");var model=GltfSkin.load(Files.readAllBytes(modelPath));var pose=model.createPose();
        check(model.nodes.length>=198,"native bone count");check(model.skins.length>0,"native skin");check(!model.clips.isEmpty(),"native clips");
        int vertices=0,triangles=0;float maxWeightError=0;
        for(var p:model.primitives){vertices+=p.positions().length/3;triangles+=p.indices().length/3;check(p.weights().length==p.positions().length/3*4,"four weights per vertex");for(int v=0;v<p.weights().length/4;v++){float sum=0;for(int k=0;k<4;k++){sum+=p.weights()[v*4+k];check(p.joints()[v*4+k]>=0&&p.joints()[v*4+k]<model.skins[p.skin()].joints().length,"joint range");}maxWeightError=java.lang.Math.max(maxWeightError,java.lang.Math.abs(sum-1));}}
        check(maxWeightError<1e-4F,"weights sum");List<Map<String,Object>> frames=new ArrayList<>();
        for(String clip:model.clips.keySet())for(double time:new double[]{0,.833333333,1.666666667,2.5}){
            pose.sample(clip,time,true);Map<String,Object> frame=new LinkedHashMap<>();frame.put("clip",clip);frame.put("seconds",time);Map<String,float[]> joints=new LinkedHashMap<>();
            for(int i=0;i<model.nodes.length;i++){Matrix4f m=pose.world[i];check(Float.isFinite(m.m30())&&Float.isFinite(m.m31())&&Float.isFinite(m.m32()),"finite native joint");joints.put(model.nodes[i].name,new float[]{m.m30(),m.m31(),m.m32()});}frame.put("joint_world",joints);
            float[] min={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY},max={Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
            for(var p:model.primitives)for(int v=0;v<p.positions().length/3;v++){Vector3f out=new Vector3f();for(int k=0;k<4;k++){float weight=p.weights()[v*4+k];if(weight==0)continue;Vector3f input=new Vector3f(p.positions()[v*3],p.positions()[v*3+1],p.positions()[v*3+2]);pose.joints[p.skin()][p.joints()[v*4+k]].transformPosition(input);out.fma(weight,input);}float[] xyz={out.x,out.y,out.z};for(int axis=0;axis<3;axis++){check(Float.isFinite(xyz[axis]),"finite native skin");min[axis]=java.lang.Math.min(min[axis],xyz[axis]);max[axis]=java.lang.Math.max(max[axis],xyz[axis]);}}
            frame.put("skinned_aabb_min",min);frame.put("skinned_aabb_max",max);frames.add(frame);
        }
        Map<String,Object> report=new LinkedHashMap<>();report.put("status","passed");report.put("assertions",assertions);report.put("model",modelPath.toString());report.put("nodes",model.nodes.length);report.put("vertices",vertices);report.put("triangles",triangles);report.put("max_weight_error",maxWeightError);report.put("clips",model.clips.keySet());report.put("frames",frames);
        Files.writeString(Path.of("F:/自制武器源文件/08_BT7274/qa/native_mesh_java_audit.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));System.out.println("PASS "+assertions+" assertions, "+vertices+" vertices, "+triangles+" triangles, clips="+model.clips.keySet());
    }
}
