package com.caf.bt7274.client;

import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.sound.*;

/** Actual sound request/source-allocation timestamps, not a claim of audible or mix acceptance. */
@EventBusSubscriber(modid="bt7274",value=Dist.CLIENT)
public final class BbAudioQa {
    private static final Path OUT=Path.of(System.getProperty("bt.qaOut","F:/自制武器源文件/08_BT7274/previews/bb_runtime"));
    private static synchronized void record(String stage,SoundInstance sound){
        if((!Boolean.getBoolean("bt.bbQa")&&!Boolean.getBoolean("bt.bbEjectionFlightQa")&&!Boolean.getBoolean("bt.bbCoverageQa"))||sound==null||!sound.getLocation().getNamespace().equals("bt7274"))return;
        try{Files.createDirectories(OUT);Map<String,Object> row=new LinkedHashMap<>(Boolean.getBoolean("bt.bbCoverageQa")?BbCoverageQa.context():Boolean.getBoolean("bt.bbEjectionFlightQa")?BbEjectionFlightQa.context():BbRuntimeQa.playbackContext());row.put("audio_stage",stage);row.put("event_id",sound.getLocation().toString());row.put("relative",sound.isRelative());row.put("loop",sound.isLooping());row.put("source",sound.getSource().name());row.put("position",List.of(sound.getX(),sound.getY(),sound.getZ()));if(sound.getSound()!=null){row.put("pitch",sound.getPitch());row.put("volume",sound.getVolume());row.put("resolved_sample",sound.getSound().getLocation().toString());}else row.put("sample_resolution","pending");Files.writeString(OUT.resolve("actual_sound_events.jsonl"),new com.google.gson.Gson().toJson(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception error){try{Files.createDirectories(OUT);Files.writeString(OUT.resolve("audio_qa_diagnostics.log"),System.currentTimeMillis()+" "+stage+" "+error+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception ignored){/* Diagnostics must never stop actual game audio or rendering. */}}
    }
    @SubscribeEvent(priority=EventPriority.LOWEST)public static void request(PlaySoundEvent event){record("requested_final_sound",event.getSound());}
    @SubscribeEvent(priority=EventPriority.LOWEST)public static void allocated(PlaySoundSourceEvent event){record("static_audio_source_allocated",event.getSound());}
    @SubscribeEvent(priority=EventPriority.LOWEST)public static void streaming(PlayStreamingSourceEvent event){record("streaming_audio_source_allocated",event.getSound());}
}
