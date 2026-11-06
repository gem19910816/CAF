package com.caf.bt7274.client;

import com.caf.bt7274.*;
import java.nio.file.*;
import java.util.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Checks precise exit boundaries on actual server ticks, independent of render/client timing. */
@EventBusSubscriber(modid="bt7274",value=Dist.CLIENT)
public final class BbExitBoundaryQa {
    private static UUID pilotId,titanId;private static int start;private static Path out;private static boolean active;
    private static final Set<Integer> seen=new HashSet<>();
    public static void watch(net.minecraft.server.level.ServerPlayer pilot,TitanEntity titan,Path output){pilotId=pilot.getUUID();titanId=titan.getUUID();start=titan.getActionStartTick();out=output;seen.clear();active=true;}
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);try{Files.writeString(out.resolve("assertions.log"),"PASS "+text+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception error){throw new RuntimeException(error);}}
    @SubscribeEvent public static void serverTick(ServerTickEvent.Post event){
        if(!Boolean.getBoolean("bt.bbQa")||!active)return;var p=event.getServer().getPlayerList().getPlayer(pilotId);if(p==null)return;var entity=p.serverLevel().getEntity(titanId);if(!(entity instanceof TitanEntity t))throw new AssertionError("watched exit titan vanished");int elapsed=(int)(p.serverLevel().getGameTime()-start);
        if(!Set.of(25,26,33,66,67).contains(elapsed)||!seen.add(elapsed))return;
        int remaining=Math.max(0,BtAnimationTimings.DISEMBARK_TICKS-elapsed);check(t.getDisembarkTicks()==remaining,"normal exit tick "+elapsed+" has exact recovery remaining "+remaining);
        if(elapsed<BtAnimationTimings.DISEMBARK_RELEASE_TICKS)check(p.getVehicle()==t,"normal exit tick "+elapsed+" retains pilot before open hatch release");
        else check(p.getVehicle()==null&&t.getPassengers().isEmpty(),"normal exit tick "+elapsed+" has empty cockpit after open hatch release");
        if(elapsed<BtAnimationTimings.DISEMBARK_TICKS){check(t.getAction().equals("disembark"),"normal exit tick "+elapsed+" keeps complete authored body action");check(!p.startRiding(t),"normal exit tick "+elapsed+" rejects re-entry during empty-cockpit recovery");check(!t.mainFire(),"normal exit tick "+elapsed+" blocks weapon/AI fire during recovery");}
        else{check(t.getAction().equals("idle")&&t.getDisembarkTicks()==0,"normal exit tick 67 returns to idle after full recovery");check(seen.containsAll(Set.of(25,26,33,66,67)),"all precise normal exit boundary ticks observed");active=false;}
        try{Map<String,Object> row=new LinkedHashMap<>();row.put("game_tick",p.serverLevel().getGameTime());row.put("wall_time_unix_ms",System.currentTimeMillis());row.put("elapsed",elapsed);row.put("remaining",t.getDisembarkTicks());row.put("mounted",p.getVehicle()==t);row.put("body_action",t.getAction());Files.writeString(out.resolve("exit_boundary_timeline.jsonl"),new com.google.gson.Gson().toJson(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception error){throw new RuntimeException(error);}
    }
}
