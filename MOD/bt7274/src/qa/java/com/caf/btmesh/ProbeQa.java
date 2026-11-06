package com.caf.btmesh;

import java.nio.file.*;
import net.minecraft.client.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Opt-in isolated client fixture; never attached to the installed game or a normal launch. */
@EventBusSubscriber(modid="bt7274",value=Dist.CLIENT)
public final class ProbeQa {
    private static int ticks;private static final Path OUT=Path.of("F:/自制武器源文件/08_BT7274/previews/native_mc_probe");
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!Boolean.getBoolean("btmesh.qa"))return;var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null)return;ticks++;
        try{
            if(ticks==1){Files.createDirectories(OUT);mc.options.pauseOnLostFocus=false;mc.options.hideGui=true;mc.options.setCameraType(CameraType.FIRST_PERSON);mc.options.fov().set(55);var id=mc.player.getUUID();var server=mc.getSingleplayerServer();if(server==null)throw new IllegalStateException("QA must use isolated integrated world");server.execute(()->{var p=server.getPlayerList().getPlayer(id);p.stopRiding();p.setGameMode(GameType.SPECTATOR);p.serverLevel().setDayTime(6000);p.connection.teleport(11.5,81.5,15.5,145,0);});}
            if(ticks>10){Vec3 d=new Vec3(.5,83,.5).subtract(mc.player.getEyePosition());mc.player.setYRot((float)(java.lang.Math.atan2(d.z,d.x)*180/java.lang.Math.PI)-90);mc.player.setXRot((float)(-java.lang.Math.atan2(d.y,d.horizontalDistance())*180/java.lang.Math.PI));}
            if(ticks==75)System.setProperty("btmesh.time","0");if(ticks==85)shot(mc,"01_walk_t0.png");
            if(ticks==95)System.setProperty("btmesh.time","0.833333333");if(ticks==105)shot(mc,"02_walk_t25.png");
            if(ticks==115)System.setProperty("btmesh.time","1.666666667");if(ticks==125)shot(mc,"03_walk_t50.png");
            if(ticks==135)System.setProperty("btmesh.time","2.5");if(ticks==145)shot(mc,"04_walk_t75.png");
            if(ticks==165){Files.writeString(OUT.resolve("complete.txt"),"Actual isolated NeoForge 21.1.252 client native GLB rendering, casual_walk fixed source sample times; placeholder materials. User game untouched.");mc.stop();}
        }catch(Exception failure){throw new RuntimeException("Native mesh isolated QA failed",failure);}
    }
    private static void shot(Minecraft mc,String name){Screenshot.grab(OUT.toFile(),name,mc.getMainRenderTarget(),message->System.out.println("BTMESH_QA_SCREENSHOT "+name+" "+message.getString()));}
}
