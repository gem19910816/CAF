package com.caf.bt7274;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Only original paired animation samples; never original character geometry.
 * Source player REF is matched to animated Titan HIJACK before unit conversion.
 */
public final class BtBoardingMotion {
    public record Sample(Map<String,Vector3f> joints,Vec3 actor,Vec3 camera,Quaternionf rotation){}
    private record Clip(double duration,Map<String,float[][]> joints,float[][] actor,float[][] camera){}
    private static final Map<String,Clip> CLIPS=load();
    private BtBoardingMotion(){}
    public static boolean active(TitanEntity t){return t.getAction().equals("embark")||t.getAction().equals("disembark");}
    private static Map<String,Clip> load(){
        try(var input=BtBoardingMotion.class.getResourceAsStream("/assets/bt7274/motion/boarding/paired_player.json")){
            if(input==null)throw new IOException("Paired pilot motion missing");
            var json=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String,Clip> clips=new HashMap<>();
            for(var e:json.getAsJsonObject("clips").entrySet()){
                var c=e.getValue().getAsJsonObject();Map<String,float[][]> joints=new HashMap<>();
                for(var point:c.getAsJsonObject("points").entrySet())joints.put(point.getKey(),arrays(point.getValue().getAsJsonArray()));
                String key=switch(e.getKey()){case "embark_3p"->"embark";case "disembark_3p"->"disembark";default->e.getKey();};
                clips.put(key,new Clip(c.get("duration").getAsDouble(),joints,arrays(c.getAsJsonArray("actor_transform")),arrays(c.getAsJsonArray("camera_transform"))));
            }return Map.copyOf(clips);
        }catch(Exception e){throw new IllegalStateException("Cannot load original paired pilot motion",e);}
    }
    private static float[][] arrays(JsonArray array){float[][] values=new float[array.size()][];for(int i=0;i<values.length;i++){JsonArray row=array.get(i).getAsJsonArray();values[i]=new float[row.size()];for(int j=0;j<row.size();j++)values[i][j]=row.get(j).getAsFloat();}return values;}
    public static double duration(String action){return CLIPS.get(action).duration;}
    private static Vector3f position(float[][] values,double frame){int a=Math.min(values.length-1,(int)frame),b=Math.min(values.length-1,a+1);return new Vector3f(values[a]).lerp(new Vector3f(values[b]),(float)(frame-a));}
    private static Vec3 vec(Vector3f p){return new Vec3(p.x,p.y,p.z);}
    public static Sample sample(String action,double elapsedTicks){
        Clip clip=CLIPS.get(action);if(clip==null)throw new IllegalArgumentException(action);
        double seconds=Math.max(0,Math.min(clip.duration,elapsedTicks/20-.2)),frame=seconds*60;
        Map<String,Vector3f> joints=new HashMap<>();clip.joints.forEach((name,values)->joints.put(name,position(values,frame)));
        joints.put("chest",joints.get("spineC"));
        for(String side:List.of("left","right"))for(String part:List.of("Shoulder","Elbow","Wrist","Hip","Knee","Ankle"))
            joints.put((side.equals("left")?"l_":"r_")+part.toLowerCase(Locale.ROOT),joints.get(side+part));
        int a=Math.min(clip.camera.length-1,(int)frame),b=Math.min(clip.camera.length-1,a+1);
        float[] ca=clip.camera[a],cb=clip.camera[b];
        var rotation=new Quaternionf(ca[3],ca[4],ca[5],ca[6]).slerp(new Quaternionf(cb[3],cb[4],cb[5],cb[6]),(float)(frame-a)).normalize();
        return new Sample(joints,vec(position(clip.actor,frame)),vec(position(clip.camera,frame)),rotation);
    }
    public static Vec3 world(TitanEntity t,Vec3 local){return t.position().add(local.yRot((float)Math.toRadians(-t.getYRot())));}
    public static Vec3 seat(){return new Vec3(0,3.08,0);}
    public static Map<String,Vector3f> firstPersonExit(double elapsedTicks){return sample("disembark_fp",elapsedTicks).joints();}
    public static Vec3 passenger(String action,double elapsedTicks){
        Sample s=sample(action,elapsedTicks);
        if(action.equals("embark")){
            double tail=Math.max(0,Math.min(1,(elapsedTicks/20-.2-duration(action))/(BtAnimationTimings.EMBARK_TICKS/20.0-.2-duration(action))));
            tail=tail*tail*(3-2*tail);Vec3 actor=s.actor.lerp(seat(),tail);
            // Source REF is a rig origin, not Minecraft's collision-box feet.
            // Keep the physical rider above the approach floor without changing
            // the authored joints, camera, horizontal approach or clip clock.
            return actor.y<0?new Vec3(actor.x,0,actor.z):actor;
        }return s.actor;
    }
}
