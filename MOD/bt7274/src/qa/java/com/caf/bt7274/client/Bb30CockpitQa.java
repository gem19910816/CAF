package com.caf.bt7274.client;

import com.caf.bt7274.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.event.sound.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Opt-in real cockpit regression. Production render/audio never writes this scenario. */
@EventBusSubscriber(modid="bt7274",value=Dist.CLIENT)
public final class Bb30CockpitQa {
    private static final Path OUT=Path.of(System.getProperty("bt.qaOut","build/bb30-cockpit"));
    private static final Gson JSON=new Gson();
    private static final List<Map<String,Object>> CHECKS=new CopyOnWriteArrayList<>(),SOUNDS=new CopyOnWriteArrayList<>();
    private static volatile UUID pilotId,titanId;private static int ticks,readyTicks;private static boolean respawn;private static volatile boolean serverReady;
    private static int pureReads,pureFailures,airFrames,airImpactFailures,phaseRegressions,cameraChecks,cameraFailures;
    private static Vec3 previousFramePosition;private static long frames;
    private static final Set<String> gaitStates=new HashSet<>();private static JsonObject previousGait;private static double previousClock=-1;
    private static float pitch,yaw;private static final Set<String> diagnosticErrors=new HashSet<>();
    private static boolean enabled(){return Boolean.getBoolean("bt.bb30CockpitQa");}
    private static synchronized void append(String file,Object row){try{Files.createDirectories(OUT);Files.writeString(OUT.resolve(file),JSON.toJson(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception e){throw new RuntimeException(e);}}
    private static void check(boolean pass,String text){var row=Map.<String,Object>of("tick",ticks,"pass",pass,"assertion",text);CHECKS.add(row);append("assertions.jsonl",row);}
    private static void input(int buttons,float forward){System.setProperty("bt.qaButtons",Integer.toString(buttons));System.setProperty("bt.qaForward",Float.toString(forward));System.setProperty("bt.qaStrafe","0");}
    private static void server(java.util.function.BiConsumer<ServerPlayer,TitanEntity> task){var mc=Minecraft.getInstance();UUID id=mc.player.getUUID();mc.getSingleplayerServer().execute(()->{var p=mc.getSingleplayerServer().getPlayerList().getPlayer(id);if(p==null)return;var e=titanId==null?null:p.serverLevel().getEntity(titanId);try{task.accept(p,e instanceof TitanEntity t?t:null);}catch(Throwable ex){check(false,"server scenario exception "+ex);}});}
    private static TitanEntity titan(){var mc=Minecraft.getInstance();if(mc.level==null)return null;for(var e:mc.level.entitiesForRendering())if(e instanceof TitanEntity t&&t.getUUID().equals(titanId))return t;return null;}
    private static String phase(){return ticks<100?"idle_lookdown":ticks<220?"walk_look_turn":ticks<360?"sprint_acceleration":ticks<400?"stop":ticks<520?"wall_sprint_stop":ticks<600?"airborne_landing":ticks<680?"view_switch":ticks<780?"resumed_sprint":"final_idle";}
    private static JsonObject gait(TitanEntity t,float partial){try{var c=Class.forName("com.caf.bt7274.client.BtGaitFeedback");var method=c.getMethod("frame",TitanEntity.class,float.class);Object a=method.invoke(null,t,partial),b=method.invoke(null,t,partial);pureReads++;if(!Objects.equals(a,b))pureFailures++;return JSON.toJsonTree(a).getAsJsonObject();}catch(Exception e){if(diagnosticErrors.add(e.toString()))append("diagnostics.jsonl",Map.of("error",e.toString()));return new JsonObject();}}
    private static Object audioDiagnostics(){try{return NativeAudioClient.class.getMethod("gaitDiagnostics").invoke(null);}catch(Exception e){return List.of();}}
    private static void reposition(ServerPlayer p,TitanEntity t,double y,double z){t.clearInput();t.setPos(.5,y,z);t.setDeltaMovement(Vec3.ZERO);t.setOnGround(y==80);p.setYRot(0);p.setXRot(pitch);}
    @SubscribeEvent public static void before(ClientTickEvent.Pre event){if(!enabled())return;var mc=Minecraft.getInstance();if(mc.player==null)return;for(var key:List.of(mc.options.keyShift,mc.options.keyJump,mc.options.keyUp,mc.options.keyDown,mc.options.keyLeft,mc.options.keyRight,mc.options.keyAttack,mc.options.keyUse))key.setDown(false);mc.player.setShiftKeyDown(false);mc.player.setYRot(yaw);mc.player.setXRot(pitch);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){if(!enabled())return;var mc=Minecraft.getInstance();if(mc.player==null||mc.level==null)return;
        if(ticks==0){if(mc.player.isDeadOrDying()||mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen){if(!respawn){respawn=true;mc.player.respawn();mc.setScreen(null);}return;}var id=mc.player.getUUID();mc.getSingleplayerServer().execute(()->{var p=mc.getSingleplayerServer().getPlayerList().getPlayer(id);serverReady=p!=null&&p.isAlive();});if(!serverReady||++readyTicks<5)return;}ticks++;
        try{
            if(ticks==1){Files.createDirectories(OUT);for(String name:List.of("assertions.jsonl","frames.jsonl","sounds.jsonl","server.jsonl","complete.json","diagnostics.jsonl","camera_angles.jsonl","badframes.jsonl"))Files.deleteIfExists(OUT.resolve(name));input(0,0);System.setProperty("bt.qaBoardingExternal","true");mc.options.pauseOnLostFocus=false;mc.options.hideGui=false;mc.options.fov().set(75);mc.options.setCameraType(CameraType.FIRST_PERSON);server((p,t)->{pilotId=p.getUUID();p.setGameMode(GameType.CREATIVE);p.getAbilities().flying=false;p.onUpdateAbilities();p.setNoGravity(false);p.noPhysics=false;p.stopRiding();var stale=new ArrayList<Entity>();for(var e:p.serverLevel().getAllEntities())if(e instanceof TitanEntity||e instanceof TitanRocket)stale.add(e);stale.forEach(Entity::discard);for(int x=-15;x<=15;x++)for(int z=-10;z<=520;z++){p.serverLevel().setBlock(new BlockPos(x,79,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);for(int y=80;y<=88;y++)p.serverLevel().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);}for(int x=-12;x<=12;x++)for(int y=80;y<=83;y++)p.serverLevel().setBlock(new BlockPos(x,y,290),Blocks.STONE.defaultBlockState(),2);var n=BT7274Mod.TITAN.get().create(p.serverLevel());n.setOwner(p.getUUID());n.setNoAi(true);n.setPos(.5,80,.5);p.serverLevel().addFreshEntity(n);titanId=n.getUUID();p.connection.teleport(.5,80,4.5,0,0);check(p.startRiding(n),"real owner mounted cockpit regression");});}
            if(ticks==20)pitch=0;if(ticks==40)pitch=35;if(ticks==60)pitch=60;if(ticks==80)pitch=80;
            if(ticks==100){pitch=60;input(0,1);}if(ticks==135)yaw=-25;if(ticks==165)yaw=25;if(ticks==195)yaw=0;
            if(ticks==220){pitch=0;input(128,1);}if(ticks==270)pitch=60;if(ticks==310)pitch=80;
            if(ticks==360){pitch=0;input(0,0);}
            if(ticks==400){yaw=0;pitch=60;server((p,t)->reposition(p,t,80,268.5));input(128,1);}
            if(ticks==510)server((p,t)->check(t.getZ()<289&&t.getGroundSpeed()<.025&&t.getLocomotion()==0,"held sprint against real wall settles stationary idle"));
            if(ticks==520){input(0,0);server((p,t)->{reposition(p,t,87,320.5);t.setOnGround(false);});}
            if(ticks==590)server((p,t)->check(t.onGround()&&Math.abs(t.getY()-80)<.05,"airborne probe naturally lands on real floor"));
            if(ticks==600){input(0,1);mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);}if(ticks==620)mc.options.setCameraType(CameraType.FIRST_PERSON);if(ticks==640)mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);if(ticks==660)mc.options.setCameraType(CameraType.FIRST_PERSON);
            if(ticks==680){pitch=0;input(128,1);}if(ticks==780){pitch=0;input(0,0);}
            if(ticks>2&&ticks<820&&Boolean.getBoolean("bt.qaRecord")){Files.createDirectories(OUT.resolve("frames"));Screenshot.grab(OUT.resolve("frames").toFile(),String.format(Locale.ROOT,"%04d_sequence.png",ticks),mc.getMainRenderTarget(),ignored->{});}
            if(Set.of(30,50,70,90,125,150,180,250,290,330,380,475,535,555,585,610,630,650,670,730,805).contains(ticks))Screenshot.grab(OUT.toFile(),String.format(Locale.ROOT,"%04d_%s.png",ticks,phase()),mc.getMainRenderTarget(),ignored->{});
            if(ticks==820){
                var cues=JSON.toJsonTree(audioDiagnostics()).getAsJsonArray();int steps=0,invalidSteps=0;double maxFraction=0;Map<String,Integer> cueTicks=new HashMap<>();
                for(var element:cues){var cue=element.getAsJsonObject();if(!cue.get("cue").getAsString().equals("step"))continue;steps++;double phase=cue.get("step_phase").getAsDouble();double fraction=phase-Math.floor(phase);maxFraction=Math.max(maxFraction,fraction);if(!cue.get("grounded").getAsBoolean()||cue.get("impact").getAsFloat()<=0||cue.get("weight").getAsFloat()<=.15F)invalidSteps++;String key=cue.get("titan")+":"+cue.get("game_tick");cueTicks.merge(key,1,Integer::sum);}
                check(steps>5&&invalidSteps==0,"actual audio cue diagnostics are grounded, weighted contact impacts; steps="+steps+" invalid="+invalidSteps+" maxPostContactFraction="+maxFraction);
                int repeatedFoot=0;JsonObject lastStep=null;for(var element:cues){var cue=element.getAsJsonObject();if(!cue.get("cue").getAsString().equals("step"))continue;if(lastStep!=null&&lastStep.get("titan").equals(cue.get("titan"))&&lastStep.get("clip").getAsInt()!=cue.get("clip").getAsInt()&&lastStep.get("left").getAsBoolean()==cue.get("left").getAsBoolean()){long gap=cue.get("game_tick").getAsLong()-lastStep.get("game_tick").getAsLong();if(gap>=0&&gap<=6){repeatedFoot++;append("badframes.jsonl",Map.of("type","same-foot contact repeated across clip within six ticks","before",lastStep,"after",cue));}}lastStep=cue;}
                check(repeatedFoot==0,"different locomotion clips do not repeat same foot within six ticks; invalid="+repeatedFoot);
                check(phaseRegressions==0,"grounded locomotion phase never retreats across clip/generation switch; invalid="+phaseRegressions);
                check(cameraChecks>80&&cameraFailures==0,"stable walking final camera pitch/yaw/roll match contact sign and amplitude; samples="+cameraChecks+" invalid="+cameraFailures);
                check(maxFraction<.35,"actual footstep requests stay near integer contact phase; maximum fraction="+maxFraction);
                check(cueTicks.values().stream().noneMatch(n->n>1),"no backlog or duplicate foot contacts emitted on a single simulation tick");
                check(pureReads>500&&pureFailures==0,"same-frame gait reads are pure/idempotent; reads="+pureReads+" failures="+pureFailures);check(airFrames>5&&airImpactFailures==0,"airborne samples contain no gait impact; frames="+airFrames+" invalid="+airImpactFailures);Files.writeString(OUT.resolve("audio_diagnostics.json"),new GsonBuilder().setPrettyPrinting().create().toJson(audioDiagnostics()));check(!diagnosticErrors.isEmpty()?false:true,"all read-only gait diagnostics available");check(SOUNDS.stream().anyMatch(s->Boolean.TRUE.equals(s.get("footstep"))),"actual client requested original footstep audio");check(SOUNDS.stream().noneMatch(s->Boolean.TRUE.equals(s.get("footstep"))&&Boolean.TRUE.equals(s.get("airborne"))),"no original footstep requests while truly airborne");var report=new LinkedHashMap<String,Object>();report.put("checks",CHECKS);report.put("failures",CHECKS.stream().filter(c->!Boolean.TRUE.equals(c.get("pass"))).count());report.put("render_frames",frames);report.put("gait_states",gaitStates);report.put("limits","Image submissions do not prove cockpit aesthetics; sound requests do not certify audible mix. Frame and sound phase logs require correlation review, especially servo tails and perspective switching.");Files.writeString(OUT.resolve("complete.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));mc.stop();}
        }catch(Exception ex){throw new RuntimeException(ex);}
    }
    @SubscribeEvent public static void serverTick(ServerTickEvent.Post e){if(!enabled()||pilotId==null)return;var p=e.getServer().getPlayerList().getPlayer(pilotId);if(p==null)return;var entity=p.serverLevel().getEntity(titanId);if(entity instanceof TitanEntity t)append("server.jsonl",Map.of("tick",ticks,"game_tick",p.serverLevel().getGameTime(),"position",t.position().toString(),"velocity",t.getDeltaMovement().toString(),"ground",t.onGround(),"speed",t.getGroundSpeed(),"locomotion",t.getLocomotion(),"collision",t.horizontalCollision));}
    @SubscribeEvent public static void frame(RenderFrameEvent.Post e){if(!enabled()||ticks<3)return;var mc=Minecraft.getInstance();var t=titan();if(t==null)return;float partial=e.getPartialTick().getGameTimeDeltaPartialTick(false);var sample=gait(t,partial);var row=new LinkedHashMap<String,Object>();row.put("frame",++frames);row.put("tick",ticks);row.put("phase",phase());row.put("game_time",mc.level.getGameTime());row.put("partial",partial);row.put("gait",sample);row.put("actual_ground",t.onGround());row.put("camera",mc.options.getCameraType().name());row.put("pitch",mc.gameRenderer.getMainCamera().getXRot());row.put("position",t.position().toString());row.put("wall_time_nanos",System.nanoTime());if(sample.has("clip"))gaitStates.add(sample.get("clip").getAsString());if(!t.onGround()&&ticks>523&&ticks<580){airFrames++;if(sample.has("impact")&&sample.get("impact").getAsDouble()>.0001)airImpactFailures++;}double clock=mc.level.getGameTime()+partial;if(previousGait!=null&&sample.has("stepPhase")&&previousGait.has("stepPhase")){row.put("delta_clock",clock-previousClock);double delta=sample.get("stepPhase").getAsDouble()-previousGait.get("stepPhase").getAsDouble();row.put("delta_step_phase",delta);
        boolean clipChange=sample.get("clip").getAsInt()!=previousGait.get("clip").getAsInt()||sample.get("generation").getAsLong()!=previousGait.get("generation").getAsLong();
        if(clipChange&&clock>=previousClock&&clock-previousClock<=2&&sample.get("grounded").getAsBoolean()&&previousGait.get("grounded").getAsBoolean()&&sample.get("moving").getAsBoolean()&&previousGait.get("moving").getAsBoolean()&&previousFramePosition!=null&&previousFramePosition.distanceTo(t.position())<4&&delta<-.001){phaseRegressions++;append("badframes.jsonl",Map.of("type","phase reverses across moving clip/generation","tick",ticks,"before",previousGait,"after",sample,"delta_clock",clock-previousClock));}}previousGait=sample;previousClock=clock;previousFramePosition=t.position();append("frames.jsonl",row);}
    @SubscribeEvent(priority=EventPriority.LOWEST)public static void cameraAngles(ViewportEvent.ComputeCameraAngles e){
        if(!enabled())return;var mc=Minecraft.getInstance();var t=titan();if(t==null||e.getCamera().getEntity()!=mc.player||!mc.options.getCameraType().isFirstPerson())return;
        var sample=gait(t,(float)e.getPartialTick());var row=new LinkedHashMap<String,Object>();row.put("tick",ticks);row.put("game_time",mc.level.getGameTime());row.put("partial",e.getPartialTick());row.put("pitch",e.getPitch());row.put("yaw",e.getYaw());row.put("roll",e.getRoll());row.put("player_pitch",mc.player.getXRot());row.put("player_yaw",mc.player.getYRot());row.put("gait",sample);
        if((ticks>=137&&ticks<=163||ticks>=167&&ticks<=193)&&sample.has("clip")&&sample.get("clip").getAsInt()==2&&sample.get("moving").getAsBoolean()){
            cameraChecks++;double impact=sample.get("impact").getAsDouble();double expectedPitch=60+.35*impact,expectedRoll=(sample.get("left").getAsBoolean()?1:-1)*.18*impact;
            double yawError=net.minecraft.util.Mth.wrapDegrees(e.getYaw()-mc.player.getYRot());
            boolean ok=Math.abs(e.getPitch()-expectedPitch)<.002&&Math.abs(e.getRoll()-expectedRoll)<.002&&Math.abs(yawError)<.002;
            row.put("expected_pitch",expectedPitch);row.put("expected_roll",expectedRoll);row.put("pass",ok);if(!ok){cameraFailures++;append("badframes.jsonl",row);}
        }
        append("camera_angles.jsonl",row);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST)public static void sound(PlaySoundEvent e){if(!enabled()||e.getSound()==null)return;var s=e.getSound();if(!s.getLocation().getNamespace().equals("bt7274"))return;var t=titan();var row=new LinkedHashMap<String,Object>();String id=s.getLocation().getPath();row.put("tick",ticks);row.put("id",s.getLocation().toString());row.put("phase",phase());row.put("footstep",id.contains("footstep")||id.startsWith("step"));row.put("airborne",t!=null&&!t.onGround()&&ticks>523&&ticks<580);if(t!=null)row.put("gait",gait(t,Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false)));if(s.getSound()!=null){row.put("volume",s.getVolume());row.put("pitch",s.getPitch());}SOUNDS.add(row);append("sounds.jsonl",row);}
}
