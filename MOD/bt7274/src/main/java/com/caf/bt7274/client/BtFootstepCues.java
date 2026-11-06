package com.caf.bt7274.client;

import com.caf.bt7274.BT7274Mod;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;

/** Original event cycles for each final BB locomotion clip; loaded once from
 * the same checked metadata that supplies the renderer's stride distances. */
final class BtFootstepCues {
    private static final float[] EMPTY=new float[0];
    private static final Map<Integer,float[]> feet=new HashMap<>(),servos=new HashMap<>();
    private static final Map<Integer,boolean[]> left=new HashMap<>();
    private static final Map<Integer,Double> strides=new HashMap<>(),durations=new HashMap<>();
    private static boolean loaded;
    private BtFootstepCues(){}
    static float[] markers(int id){load();return feet.getOrDefault(id,EMPTY);}
    static float[] servos(int id){load();return servos.getOrDefault(id,EMPTY);}
    static boolean[] left(int id){load();return left.getOrDefault(id,new boolean[0]);}
    static double stride(int id){load();return strides.getOrDefault(id,0.0);}
    static double duration(int id){load();return durations.getOrDefault(id,1.0);}
    private static void load(){
        if(loaded)return;
        try(var reader=Minecraft.getInstance().getResourceManager().openAsReader(BT7274Mod.id("motion/bb_locomotion.json"))){
            var clips=JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("clips");
            for(var entry:clips.entrySet()){
                int id=Integer.parseInt(entry.getKey());var clip=entry.getValue().getAsJsonObject();
                strides.put(id,clip.get("stride_metres").getAsDouble());durations.put(id,clip.get("duration").getAsDouble());
                var source=clip.getAsJsonArray("foot_events");float[] values=new float[source.size()];boolean[] sides=new boolean[values.length];
                for(int i=0;i<values.length;i++){var marker=source.get(i).getAsJsonObject();values[i]=marker.get("cycle").getAsFloat();sides[i]=marker.get("name").getAsString().endsWith("_L");}
                left.put(id,sides);
                feet.put(id,values);source=clip.getAsJsonArray("servo_events");values=new float[source.size()];
                for(int i=0;i<values.length;i++)values[i]=source.get(i).getAsJsonObject().get("cycle").getAsFloat();
                servos.put(id,values);
            }
            loaded=true;
        }catch(Exception failure){throw new IllegalStateException("Final BB locomotion cue metadata failed",failure);}
    }
}
