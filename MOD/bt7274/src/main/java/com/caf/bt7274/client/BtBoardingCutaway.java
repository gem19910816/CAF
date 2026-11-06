package com.caf.bt7274.client;

import com.google.gson.*;
import com.caf.bt7274.BT7274Mod;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.client.Minecraft;

/** Whitelisted per-action windows for first-person solid-cube near-plane cutaway. */
final class BtBoardingCutaway {
    private record Window(int clip,double start,double end){}
    private static final Map<String,List<Window>> WINDOWS=load();
    private BtBoardingCutaway(){}
    private static Map<String,List<Window>> load(){
        try(var reader=Minecraft.getInstance().getResourceManager().openAsReader(BT7274Mod.id("motion/boarding/camera_cutaway.json"))){
            JsonObject root=JsonParser.parseReader(reader).getAsJsonObject();Map<String,List<Window>> out=new HashMap<>();
            for(JsonElement raw:root.getAsJsonArray("entries")){JsonObject entry=raw.getAsJsonObject();String uuid=entry.get("author_uuid").getAsString();List<Window> windows=new ArrayList<>();
                for(JsonElement w:entry.getAsJsonArray("windows")){JsonObject item=w.getAsJsonObject();windows.add(new Window(item.get("body_clip").getAsInt(),item.get("bb_start").getAsDouble(),item.get("bb_end").getAsDouble()));}out.put(uuid,List.copyOf(windows));}
            return Map.copyOf(out);
        }catch(Exception e){throw new IllegalStateException("Boarding camera cutaway whitelist missing",e);}
    }
    static Set<String> eligible(String action,double elapsedTicks){
        int clip=action.equals("embark")?19:action.equals("disembark")?20:-1;
        if(clip<0)return Set.of();double seconds=elapsedTicks/20;Set<String> result=new HashSet<>();
        WINDOWS.forEach((uuid,windows)->{for(Window w:windows)if(w.clip==clip&&seconds>=w.start&&seconds<=w.end){result.add(uuid);break;}});
        return result;
    }
}
