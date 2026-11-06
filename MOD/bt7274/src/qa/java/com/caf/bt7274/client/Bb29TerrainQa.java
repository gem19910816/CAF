package com.caf.bt7274.client;

import com.caf.bt7274.*;
import com.caf.btmesh.BbMotionPlayer;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaternionf;

/** Disposable real pilot-input terrain probe; never enabled in a release. */
@EventBusSubscriber(modid="bt7274",value=Dist.CLIENT)
public final class Bb29TerrainQa {
    private static final Path OUT=Path.of(System.getProperty("bt.qaOut","build/bb29-terrain"));
    private static final Gson JSON=new Gson();
    private static final String[] NAMES={"continuous_slabs_forward","stairs_backward","carpet_snow_left","fence_right","two_block_forward","diagonal_edges","three_block_wall","four_block_cliff_three_block_wall"};
    private static final float[] YAW={0,180,90,-90,0,45,0,0},FORWARD={1,-1,0,0,1,1,1,1},STRAFE={0,0,1,-1,0,1,0,0};
    private static final List<Map<String,Object>> CHECKS=new CopyOnWriteArrayList<>();
    private static volatile UUID titanId,pilotId;
    private static volatile int ticks,caseId=-1,caseStartServer,stall,maxStall,idleFlips,lastLoco=-1,idleSamples;
    private static volatile double progress,maxFeetY,wallMaxZ;
    private static int readyTicks;private static boolean respawnSent;private static volatile boolean serverReady;
    private static ArmorStand camera;private static Vec3 previousServer;
    private static Quaternionf[] previousPose;private static int previousPoseCase=-1;
    private static long frames;private static float maxQuaternionStep,wallStopMaxAngle;
    private static boolean wallBlendStart,wallBlendComplete,wallSawRun,cliffFalling,cliffLanded;
    private static int cliffAirTicks;private static double cliffMinY=80,wallBlendStartClock=-1,wallBlendEndClock=-1;
    private static final Set<Integer> wallBlendMiddle=new HashSet<>();
    private static boolean enabled(){return Boolean.getBoolean("bt.bb29TerrainQa");}
    private static synchronized void append(String file,Object value){try{Files.createDirectories(OUT);Files.writeString(OUT.resolve(file),JSON.toJson(value)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception e){throw new RuntimeException(e);}}
    private static void check(boolean pass,String text){var row=Map.<String,Object>of("tick",ticks,"case",caseId,"pass",pass,"assertion",text);CHECKS.add(row);append("assertions.jsonl",row);}
    private static void input(int buttons,float forward,float strafe){System.setProperty("bt.qaButtons",Integer.toString(buttons));System.setProperty("bt.qaForward",Float.toString(forward));System.setProperty("bt.qaStrafe",Float.toString(strafe));}
    private static void server(java.util.function.BiConsumer<ServerPlayer,TitanEntity> action){var mc=Minecraft.getInstance();UUID id=mc.player.getUUID();mc.getSingleplayerServer().execute(()->{var p=mc.getSingleplayerServer().getPlayerList().getPlayer(id);var e=titanId==null?null:p.serverLevel().getEntity(titanId);try{action.accept(p,e instanceof TitanEntity t?t:null);}catch(Throwable ex){check(false,"scenario exception "+ex);}});}
    private static TitanEntity titan(){var mc=Minecraft.getInstance();if(mc.level==null)return null;for(var e:mc.level.entitiesForRendering())if(e instanceof TitanEntity t&&t.getUUID().equals(titanId))return t;return null;}
    private static void beginCase(ServerPlayer p,TitanEntity t,int n){
        caseId=n;caseStartServer=(int)p.serverLevel().getGameTime();stall=maxStall=idleFlips=idleSamples=0;lastLoco=-1;progress=wallMaxZ=0;maxFeetY=80;previousServer=null;
        double x=n*32+.5;t.clearInput();t.setDeltaMovement(Vec3.ZERO);t.setPos(x,80,.5);t.setOnGround(true);t.setYRot(YAW[n]);t.yBodyRot=YAW[n];p.setYRot(YAW[n]);p.setXRot(0);
        check(p.getVehicle()==t,"case uses actual owner mounted pilot");
        append("cases.jsonl",Map.of("case",n,"name",NAMES[n],"server_start",caseStartServer,"input",List.of(FORWARD[n],STRAFE[n]),"yaw",YAW[n]));
    }
    private static void build(ServerPlayer p){
        var level=p.serverLevel();for(int n=0;n<NAMES.length;n++){int cx=n*32;
            for(int x=cx-10;x<=cx+10;x++)for(int z=-8;z<=85;z++){level.setBlock(new BlockPos(x,79,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);for(int y=80;y<88;y++)level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);}
            for(int x=cx-8;x<=cx+8;x++)for(int z=8;z<=26;z++){
                var at=new BlockPos(x,80,z);
                switch(n){
                    case 0 -> {if(z%4<2)level.setBlock(at,Blocks.SMOOTH_STONE_SLAB.defaultBlockState(),2);}
                    case 1 -> {if(z%6<3){level.setBlock(at,Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.SOUTH),2);if(z%6==2)level.setBlock(at.above(),Blocks.STONE_SLAB.defaultBlockState(),2);}}
                    case 2 -> level.setBlock(at,z%4<2?Blocks.GRAY_CARPET.defaultBlockState():Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS,1+z%7),2);
                    case 3 -> {if(z%6==2)level.setBlock(at,Blocks.OAK_FENCE.defaultBlockState(),2);}
                    case 4 -> {if(z%7<2){level.setBlock(at,Blocks.STONE.defaultBlockState(),2);level.setBlock(at.above(),Blocks.STONE.defaultBlockState(),2);}}
                    case 5 -> {if((x-cx+z)%5<2&&Math.abs(x-cx)<5)level.setBlock(at,Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,(z%3==0)?SlabType.TOP:SlabType.BOTTOM),2);}
                    case 6 -> {if(z==12)for(int y=80;y<=82;y++)level.setBlock(new BlockPos(x,y,z),Blocks.STONE.defaultBlockState(),2);}
                }
            }
            if(n==7)for(int x=cx-10;x<=cx+10;x++)for(int z=11;z<=85;z++){
                level.setBlock(new BlockPos(x,75,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);
                for(int y=76;y<=79;y++)level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);
                if(z==23)for(int y=76;y<=78;y++)level.setBlock(new BlockPos(x,y,z),Blocks.STONE.defaultBlockState(),2);
            }
        }
    }
    @SubscribeEvent public static void before(ClientTickEvent.Pre event){if(!enabled())return;var mc=Minecraft.getInstance();if(mc.player==null)return;for(var key:List.of(mc.options.keyShift,mc.options.keyJump,mc.options.keyUp,mc.options.keyDown,mc.options.keyLeft,mc.options.keyRight,mc.options.keyAttack,mc.options.keyUse))key.setDown(false);mc.player.setShiftKeyDown(false);mc.player.setYRot(caseId<0?0:YAW[caseId]);mc.player.setXRot(0);}
    @SubscribeEvent public static void camera(RenderFrameEvent.Pre event){if(!enabled())return;var mc=Minecraft.getInstance();var t=titan();if(t==null)return;if(camera==null||camera.level()!=mc.level)camera=new ArmorStand(mc.level,0,0,0);var at=t.getPosition(event.getPartialTick().getGameTimeDeltaPartialTick(false));double x=at.x+3,z=at.z,y=at.y+3.0;float yaw=90,pitch=4;camera.moveTo(x,y-camera.getEyeHeight(),z,yaw,pitch);camera.yRotO=yaw;camera.xRotO=pitch;camera.setYHeadRot(yaw);camera.yHeadRotO=yaw;camera.xo=x;camera.yo=camera.getY();camera.zo=z;mc.setCameraEntity(camera);mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){if(!enabled())return;var mc=Minecraft.getInstance();if(mc.player==null||mc.level==null)return;
        if(ticks==0){if(mc.player.isDeadOrDying()||mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen){if(!respawnSent){respawnSent=true;mc.player.respawn();mc.setScreen(null);}return;}var id=mc.player.getUUID();mc.getSingleplayerServer().execute(()->{var p=mc.getSingleplayerServer().getPlayerList().getPlayer(id);serverReady=p!=null&&p.isAlive();});if(!serverReady||++readyTicks<5)return;}
        ticks++;try{
            if(ticks==1){Files.createDirectories(OUT);for(String file:List.of("assertions.jsonl","server.jsonl","client.jsonl","render_frames.jsonl","cases.jsonl","complete.json"))Files.deleteIfExists(OUT.resolve(file));input(0,0,0);mc.options.pauseOnLostFocus=false;mc.options.fov().set(55);mc.options.hideGui=true;System.setProperty("bt.qaBoardingExternal","true");server((p,t)->{pilotId=p.getUUID();p.setGameMode(GameType.CREATIVE);p.getAbilities().flying=false;p.onUpdateAbilities();p.setNoGravity(false);p.noPhysics=false;p.setHealth(p.getMaxHealth());p.stopRiding();var stale=new ArrayList<Entity>();for(var e:p.serverLevel().getAllEntities())if(e instanceof TitanEntity||e instanceof TitanRocket)stale.add(e);stale.forEach(Entity::discard);build(p);var titan=BT7274Mod.TITAN.get().create(p.serverLevel());titan.setOwner(p.getUUID());titan.setNoAi(true);titan.setPos(.5,80,.5);p.serverLevel().addFreshEntity(titan);titanId=titan.getUUID();p.connection.teleport(.5,80,4.5,0,0);check(p.startRiding(titan),"real pilot mounts ready terrain probe");});}
            if(ticks>=20&&ticks<1140){int n=(ticks-20)/140,age=(ticks-20)%140;if(age==0){input(0,0,0);server((p,t)->beginCase(p,t,n));}if(age==5)input(128,FORWARD[n],STRAFE[n]);if(age==112&&n<6)input(0,0,0);if(age==130)server((p,t)->{if(n<6){check(progress>29,"small terrain traversed entirely: "+NAMES[n]+" progress="+progress);check(maxStall<=7,"small terrain has no >7 tick stop: "+NAMES[n]+" stalled="+maxStall);}else if(n==6){check(wallMaxZ<11,"three-block wall prevents passage: "+wallMaxZ);check(idleSamples>=12,"sustained wall input settles actual idle locomotion="+idleSamples);check(idleFlips<=2,"wall contact does not repeatedly toggle run/idle="+idleFlips);check(wallBlendStart&&wallBlendComplete&&wallBlendMiddle.size()>=4,"wall idle transition observed low/middle/full blend samples="+wallBlendMiddle.size());check(wallStopMaxAngle<20,"wall stop adjacent rendered bone angle remains below20deg="+wallStopMaxAngle);check(wallBlendEndClock-wallBlendStartClock>=6&&wallBlendEndClock-wallBlendStartClock<=10,"wall idle blend spans six-to-ten simulation ticks="+(wallBlendEndClock-wallBlendStartClock));}else{check(cliffFalling&&cliffAirTicks>=2,"four-metre cliff falls naturally without support snap, air ticks="+cliffAirTicks);check(cliffMinY<=76.1&&cliffLanded,"cliff reaches real lower ground rather than floating; minY="+cliffMinY);check(wallMaxZ<21.6,"cannot climb three-block wall after cliff/while airborne; maxZ="+wallMaxZ);}});}
            var t=titan();if(t!=null){var row=new LinkedHashMap<String,Object>();row.put("tick",ticks);row.put("case",caseId);row.put("position",t.position().toString());row.put("locomotion",t.getLocomotion());row.put("speed",t.getGroundSpeed());var r=mc.getEntityRenderDispatcher().getRenderer(t);if(r instanceof TitanRenderer renderer){var p=renderer.playback(t);if(p!=null)row.put("playback",p);}append("client.jsonl",row);}
            if(Boolean.getBoolean("bt.qaRecord")&&ticks>=20&&ticks<1140){Files.createDirectories(OUT.resolve("frames"));Screenshot.grab(OUT.resolve("frames").toFile(),String.format(Locale.ROOT,"%04d_sequence.png",ticks),mc.getMainRenderTarget(),ignored->{});}
            if(ticks==1150){append("warnings.jsonl",Map.of("max_adjacent_bone_degrees",maxQuaternionStep,"note","Global adjacent-frame angle is diagnostic, never visual acceptance; inspect per-frame elapsed time."));var report=Map.of("scenario","real pilot packets across eight terrain lanes","checks",CHECKS,"failures",CHECKS.stream().filter(c->!Boolean.TRUE.equals(c.get("pass"))).count(),"render_frames",frames,"max_adjacent_bone_degrees",maxQuaternionStep,"limits","Quaternion jump threshold detects severe discontinuity; recorded video still requires visual review.");Files.writeString(OUT.resolve("complete.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));mc.stop();}
        }catch(Exception e){throw new RuntimeException(e);}
    }
    @SubscribeEvent public static void serverTick(ServerTickEvent.Post event){if(!enabled()||pilotId==null||caseId<0)return;var p=event.getServer().getPlayerList().getPlayer(pilotId);if(p==null)return;var entity=p.serverLevel().getEntity(titanId);if(!(entity instanceof TitanEntity t))return;int age=(int)p.serverLevel().getGameTime()-caseStartServer;double moved=previousServer==null?0:t.position().subtract(previousServer).horizontalDistance();previousServer=t.position();progress=Math.max(progress,t.getZ()-.5);maxFeetY=Math.max(maxFeetY,t.getY());wallMaxZ=Math.max(wallMaxZ,t.getZ());if(age>=15&&age<=110&&caseId<6){if(moved<.025&&progress<29)stall++;else stall=0;maxStall=Math.max(maxStall,stall);}if(caseId==6&&age>=65&&age<=130){if(t.getLocomotion()==0)idleSamples++;if(lastLoco>=0&&lastLoco!=t.getLocomotion())idleFlips++;lastLoco=t.getLocomotion();}if(caseId==7&&age>10){cliffMinY=Math.min(cliffMinY,t.getY());if(!t.onGround()&&t.getZ()>10&&t.getDeltaMovement().y<-.08){cliffFalling=true;cliffAirTicks++;}if(t.onGround()&&Math.abs(t.getY()-76)<.1)cliffLanded=true;}var row=new LinkedHashMap<String,Object>();row.put("tick",ticks);row.put("server_tick",p.serverLevel().getGameTime());row.put("case",caseId);row.put("age",age);row.put("position",t.position().toString());row.put("velocity",t.getDeltaMovement().toString());row.put("on_ground",t.onGround());row.put("speed",t.getGroundSpeed());row.put("locomotion",t.getLocomotion());row.put("horizontal_collision",t.horizontalCollision);row.put("travelled_tick",moved);row.put("stall",stall);append("server.jsonl",row);}
    @SubscribeEvent public static void rendered(RenderFrameEvent.Post event){if(!enabled())return;var mc=Minecraft.getInstance();var t=titan();if(t==null||caseId<0)return;var er=mc.getEntityRenderDispatcher().getRenderer(t);if(!(er instanceof TitanRenderer renderer))return;try{var statesField=TitanRenderer.class.getDeclaredField("states");statesField.setAccessible(true);Object state=((Map<?,?>)statesField.get(renderer)).get(t);if(state==null)return;var field=state.getClass().getDeclaredField("displayed");field.setAccessible(true);var pose=(BbMotionPlayer.Frame)field.get(state);if(pose==null)return;if(previousPoseCase!=caseId){previousPose=null;previousPoseCase=caseId;}float max=0;int bone=-1;if(previousPose==null){previousPose=new Quaternionf[pose.rotation.length];for(int i=0;i<pose.rotation.length;i++)previousPose[i]=new Quaternionf(pose.rotation[i]);}else for(int i=0;i<pose.rotation.length;i++){float dot=Math.min(1,Math.abs(previousPose[i].dot(pose.rotation[i])));float degrees=(float)Math.toDegrees(2*Math.acos(dot));if(degrees>max){max=degrees;bone=i;}previousPose[i].set(pose.rotation[i]);}maxQuaternionStep=Math.max(maxQuaternionStep,max);frames++;var play=renderer.playback(t);double partial=event.getPartialTick().getGameTimeDeltaPartialTick(false),clock=mc.level.getGameTime()+partial;
        if(caseId==6&&play!=null&&play.clipId()!=1)wallSawRun=true;
        if(caseId==6&&wallSawRun&&play!=null&&play.clipId()==1){if(play.transition()<.15F&&!wallBlendStart){wallBlendStart=true;wallBlendStartClock=clock;}if(wallBlendStart){wallStopMaxAngle=Math.max(wallStopMaxAngle,max);if(play.transition()>=.15F&&play.transition()<.9F)wallBlendMiddle.add(Math.round(play.transition()*1000));if(play.transition()>=1&&!wallBlendComplete){wallBlendComplete=true;wallBlendEndClock=clock;}}}
        var row=new LinkedHashMap<String,Object>();row.put("frame",frames);row.put("tick",ticks);row.put("case",caseId);row.put("game_time",mc.level.getGameTime());row.put("partial",partial);row.put("render_clock_ticks",clock);row.put("wall_time_nanos",System.nanoTime());row.put("max_bone_degrees",max);row.put("bone",bone);row.put("playback",play==null?"unavailable":play);append("render_frames.jsonl",row);}catch(Exception e){append("render_diagnostics.jsonl",Map.of("error",e.toString()));}}
}
