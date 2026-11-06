package com.caf.btmesh;

import com.google.gson.GsonBuilder;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;

/** Opt-in real render-thread measurement. CPU covers Java pose/skin/emit, never claims GPU frame time. */
public final class NativeRenderProfiler {
    private static final boolean ENABLED=Boolean.getBoolean("bt.nativeQa");
    private static final java.lang.management.ThreadMXBean THREAD=ManagementFactory.getThreadMXBean();
    private static final Map<String,Stats> DATA=new LinkedHashMap<>();
    private static final Map<String,Object> DESCRIPTIONS=new LinkedHashMap<>();
    private static final class Stats {long count,wall,cpu,max;final long[] sample=new long[4096];void add(long w,long c){sample[(int)(count%sample.length)]=w;count++;wall+=w;cpu+=c;max=Math.max(max,w);}}
    public static long cpu(){return ENABLED&&THREAD.isCurrentThreadCpuTimeSupported()?THREAD.getCurrentThreadCpuTime():0;}
    public static void describe(String label,Object value){if(ENABLED)DESCRIPTIONS.put(label,value);}
    public static void record(String label,long wallStart,long cpuStart){if(!ENABLED)return;long wall=System.nanoTime()-wallStart,cpu=cpu()-cpuStart;DATA.computeIfAbsent(label,k->new Stats()).add(wall,cpu);}
    public static void write(Path path,int currentFps)throws Exception{Map<String,Object> report=new LinkedHashMap<>();report.put("scope","Actual isolated Minecraft render thread; CPU pose/skin/vertex emission; no GPU timing");report.put("observed_fps",currentFps);report.put("loaded_render_plans",DESCRIPTIONS);for(var entry:DATA.entrySet()){Stats s=entry.getValue();long[] sorted=Arrays.copyOf(s.sample,(int)Math.min(s.count,s.sample.length));Arrays.sort(sorted);report.put(entry.getKey(),Map.of("samples",s.count,"mean_wall_ms",s.wall/(double)s.count/1e6,"mean_cpu_ms",s.cpu/(double)s.count/1e6,"p50_wall_ms",sorted[sorted.length/2]/1e6,"p95_wall_ms",sorted[(int)((sorted.length-1)*.95)]/1e6,"max_wall_ms",s.max/1e6));}Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(report));}
}
