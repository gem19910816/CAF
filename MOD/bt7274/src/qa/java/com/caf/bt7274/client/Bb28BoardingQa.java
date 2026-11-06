package com.caf.bt7274.client;

import com.caf.bt7274.*;
import com.caf.btmesh.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Real right-click packet -> mobInteract, then pilot 4096 packet; no startRiding shortcut. */
@EventBusSubscriber(modid="bt7274",value=Dist.CLIENT)
public final class Bb28BoardingQa {
    private static final Path OUT=Path.of(System.getProperty("bt.qaOut","build/bb28-boarding"));
    private static final Gson JSON=new Gson();
    private static final List<Map<String,Object>> checks=new CopyOnWriteArrayList<>();
    private static final Set<String> boundarySeen=Collections.synchronizedSet(new HashSet<>());
    private static final Set<Integer> clips=Collections.synchronizedSet(new HashSet<>());
    private static volatile UUID titanId,pilotId;
    private static volatile int ticks,boardStart=-1,exitStart=-1,round;
    private static boolean firstPerson,respawnRequested;
    private static volatile boolean serverPlayerReady;
    private static int preflightTicks,readyTicks;
    private static ArmorStand camera;
    private static final Map<Integer,net.minecraft.world.phys.Vec3> previousPilot=new HashMap<>();
    private static final Map<Integer,Double> maxPilotStep=new HashMap<>(),maxPilotY=new HashMap<>(),maxExitY=new HashMap<>();
    private static final Set<Integer> airborne=new HashSet<>(),landed=new HashSet<>();
    private static boolean enabled(){return Boolean.getBoolean("bt.bb28BoardingQa");}
    private static synchronized void append(String file,Object row){try{Files.createDirectories(OUT);Files.writeString(OUT.resolve(file),JSON.toJson(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception e){throw new RuntimeException(e);}}
    private static void check(boolean pass,String text){var row=Map.<String,Object>of("client_tick",ticks,"round",round,"pass",pass,"assertion",text);checks.add(row);append("assertions.jsonl",row);}
    private static void input(int buttons,float forward){System.setProperty("bt.qaButtons",Integer.toString(buttons));System.setProperty("bt.qaForward",Float.toString(forward));System.setProperty("bt.qaStrafe","0");}
    private static TitanEntity titan(){var mc=Minecraft.getInstance();if(mc.level==null)return null;for(var e:mc.level.entitiesForRendering())if(e instanceof TitanEntity t&&t.getUUID().equals(titanId))return t;return null;}
    private static void server(java.util.function.BiConsumer<ServerPlayer,TitanEntity> action){var mc=Minecraft.getInstance();UUID id=mc.player.getUUID();var s=mc.getSingleplayerServer();if(s==null)return;s.execute(()->{var p=s.getPlayerList().getPlayer(id);if(p==null)return;var entity=titanId==null?null:p.serverLevel().getEntity(titanId);try{action.accept(p,entity instanceof TitanEntity t?t:null);}catch(Throwable ex){check(false,"server scenario exception "+ex);}});}
    private static void prepareApproach(ServerPlayer p,TitanEntity titan,String label){
        var source=BtBoardingMotion.world(titan,BtBoardingMotion.sample("embark",0).actor());
        double feetY=Math.max(80.05,source.y);BlockPos floor=BlockPos.containing(source.x,79,source.z);
        p.setNoGravity(false);p.noPhysics=false;p.getAbilities().flying=false;p.getAbilities().mayfly=false;p.onUpdateAbilities();
        p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);p.resetFallDistance();p.clearFire();p.setHealth(p.getMaxHealth());
        p.connection.teleport(source.x,feetY,source.z,180,0);
        boolean solid=p.serverLevel().getBlockState(floor).is(Blocks.SMOOTH_STONE);
        boolean clear=p.serverLevel().noCollision(p,p.getBoundingBox());
        append("approach_setup.jsonl",Map.of("label",label,"client_tick",ticks,"source_position",source.toString(),"spawn_feet",p.position().toString(),"floor_position",floor.toShortString(),"floor_block",p.serverLevel().getBlockState(floor).toString(),"spawn_clear",clear,"no_physics",p.noPhysics));
        check(solid&&clear&&p.getY()>=80,""+label+" spawn feet start above real solid floor with clear player collision");
        check(!p.noPhysics&&!p.isNoGravity()&&!p.getAbilities().flying,""+label+" starts with normal collision/gravity and flight disabled");
    }
    private static String phase(){return ticks<20?"setup":ticks<130?"world_embark":ticks<215?"world_disembark":ticks<230?"reposition":ticks<340?"fp_embark":"fp_disembark";}
    private static void rightClick(){var mc=Minecraft.getInstance();var t=titan();check(t!=null&&mc.gameMode!=null,"right-click has loaded actual Titan entity");if(t!=null&&mc.gameMode!=null){var result=mc.gameMode.interact(mc.player,t,InteractionHand.MAIN_HAND);append("interactions.jsonl",Map.of("tick",ticks,"operation","actual MultiPlayerGameMode.interact sends right-click packet","client_result",result.toString()));}}
    private static Map<String,Object> clientContext(){var mc=Minecraft.getInstance();var row=new LinkedHashMap<String,Object>();row.put("tick",ticks);row.put("phase",phase());row.put("round",round);row.put("client_alive",mc.player.isAlive());row.put("client_dead_or_dying",mc.player.isDeadOrDying());row.put("client_health",mc.player.getHealth());row.put("client_player_position",mc.player.position().toString());row.put("client_screen",mc.screen==null?"none":mc.screen.getClass().getName());row.put("mounted",mc.player.getVehicle() instanceof TitanEntity);row.put("camera_type",mc.options.getCameraType().name());row.put("actual_camera_position",mc.gameRenderer.getMainCamera().getPosition().toString());var t=titan();if(t!=null){row.put("action",t.getAction());row.put("action_start",t.getActionStartTick());row.put("exit_remaining",t.getDisembarkTicks());row.put("position",t.position().toString());var er=mc.getEntityRenderDispatcher().getRenderer(t);if(er instanceof TitanRenderer r){var p=r.playback(t);if(p!=null){row.put("rendered_clip",p.clipId());row.put("rendered_seconds",p.seconds());row.put("rendered_layers",p.layers());clips.add(p.clipId());}}}try{var f=NativeFirstPersonRenderer.class.getDeclaredField("displayedState");f.setAccessible(true);row.put("fp_state",String.valueOf(f.get(null)));}catch(Exception e){row.put("fp_diagnostic",e.toString());}return row;}
    @SubscribeEvent public static void before(ClientTickEvent.Pre event){if(!enabled())return;var mc=Minecraft.getInstance();if(mc.player==null)return;for(var key:List.of(mc.options.keyShift,mc.options.keyJump,mc.options.keyUp,mc.options.keyDown,mc.options.keyLeft,mc.options.keyRight,mc.options.keyAttack,mc.options.keyUse))key.setDown(false);mc.player.noPhysics=false;mc.player.setNoGravity(false);mc.player.getAbilities().flying=false;mc.player.getAbilities().mayfly=false;mc.player.setShiftKeyDown(false);mc.player.setYRot(180);mc.player.setXRot(0);}
    @SubscribeEvent public static void camera(RenderFrameEvent.Pre event){if(!enabled())return;var mc=Minecraft.getInstance();var t=titan();if(t==null||mc.player==null)return;System.setProperty("bt.qaBoardingExternal",Boolean.toString(!firstPerson));if(firstPerson){if(!BtBoardingView.active()){mc.setCameraEntity(mc.player);mc.options.setCameraType(CameraType.FIRST_PERSON);}return;}if(camera==null||camera.level()!=mc.level)camera=new ArmorStand(mc.level,0,0,0);var at=t.getPosition(event.getPartialTick().getGameTimeDeltaPartialTick(false));double x=at.x-8,z=at.z-8,y=at.y+4.4;float yaw=-45,pitch=5;camera.moveTo(x,y-camera.getEyeHeight(),z,yaw,pitch);camera.setYHeadRot(yaw);camera.yHeadRotO=yaw;camera.yRotO=yaw;camera.xRotO=pitch;camera.xo=x;camera.yo=camera.getY();camera.zo=z;mc.setCameraEntity(camera);mc.options.setCameraType(mc.player.getVehicle()==t?CameraType.THIRD_PERSON_BACK:CameraType.FIRST_PERSON);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){if(!enabled())return;var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null)return;
        if(ticks==0){
            preflightTicks++;
            if(mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen||mc.player.isDeadOrDying()){
                readyTicks=0;serverPlayerReady=false;
                if(!respawnRequested){respawnRequested=true;mc.player.respawn();mc.setScreen(null);append("preflight.jsonl",Map.of("operation","requested actual player respawn","wall_time",System.currentTimeMillis(),"health",mc.player.getHealth()));}
                return;
            }
            if(mc.player.isAlive()&&mc.player.getHealth()>0){
                var id=mc.player.getUUID();var integrated=mc.getSingleplayerServer();
                if(integrated!=null)integrated.execute(()->{var p=integrated.getPlayerList().getPlayer(id);serverPlayerReady=p!=null&&p.isAlive()&&!p.isDeadOrDying()&&p.getHealth()>0;});
                if(serverPlayerReady)readyTicks++;else readyTicks=0;
            }else readyTicks=0;
            if(readyTicks<5)return;
            append("preflight.jsonl",Map.of("operation","live client and server respawn synchronized","wait_ticks",preflightTicks,"client_health",mc.player.getHealth(),"client_position",mc.player.position().toString()));
        }
        ticks++;
        try{
            if(ticks==1){Files.createDirectories(OUT);for(String name:List.of("assertions.jsonl","server_timeline.jsonl","client_timeline.jsonl","interactions.jsonl","approach_setup.jsonl","complete.json"))Files.deleteIfExists(OUT.resolve(name));System.setProperty("bt.qaBoardingExternal","true");input(0,0);mc.options.pauseOnLostFocus=false;mc.options.hideGui=false;mc.options.fov().set(75);server((p,t)->{pilotId=p.getUUID();p.setGameMode(GameType.SURVIVAL);p.setNoGravity(false);p.getAbilities().mayfly=false;p.getAbilities().flying=false;p.onUpdateAbilities();p.getInventory().clearContent();p.stopRiding();var stale=new ArrayList<Entity>();for(var e:p.serverLevel().getAllEntities())if(e instanceof TitanEntity||e instanceof TitanRocket||e instanceof net.minecraft.world.entity.monster.Monster)stale.add(e);stale.forEach(Entity::discard);for(int x=-10;x<=10;x++)for(int z=-10;z<=12;z++){p.serverLevel().setBlock(new BlockPos(x,79,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);for(int y=80;y<90;y++)p.serverLevel().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);}var n=BT7274Mod.TITAN.get().create(p.serverLevel());n.setOwner(p.getUUID());n.setPos(.5,80,.5);n.setYRot(180);n.yBodyRot=180;n.setNoAi(true);p.serverLevel().addFreshEntity(n);titanId=n.getUUID();prepareApproach(p,n,"round1");});}
            if(ticks==12){check(mc.player.isAlive()&&!mc.player.isDeadOrDying()&&mc.player.getHealth()>0&&!(mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen),"setup has a real live client player after respawn");server((p,t)->check(p.isAlive()&&p.getHealth()>0,"setup has corresponding real live server player"));}
            if(ticks==14)server((p,t)->check(p.onGround()&&!p.isNoGravity()&&!p.getAbilities().flying,"round1 grounded survival approach settles before interaction"));
            if(ticks==20){round=1;rightClick();}
            if(ticks==30||ticks==45||ticks==65)input(129,1);
            if(ticks==75)input(0,0);
            if(ticks==115)server((p,t)->check(p.getVehicle()==t&&t.getAction().equals("idle"),"world embark completes full 80 tick clip and retains pilot"));
            if(ticks==130)input(4096,0);
            if(ticks==131)input(0,0);
            if(ticks==215)server((p,t)->{check(p.getVehicle()==null&&t.getDisembarkTicks()==0&&t.getAction().equals("idle"),"world exit completed recovery and closed-cockpit idle");t.setYRot(180);t.yBodyRot=180;prepareApproach(p,t,"round2");});
            if(ticks==225){firstPerson=true;System.setProperty("bt.qaBoardingExternal","false");mc.setCameraEntity(mc.player);mc.options.setCameraType(CameraType.FIRST_PERSON);}
            if(ticks==224)server((p,t)->check(p.onGround()&&!p.isNoGravity()&&!p.getAbilities().flying,"round2 grounded survival approach settles before interaction"));
            if(ticks==230){round=2;rightClick();}
            if(ticks==245||ticks==265||ticks==285)input(129,1);
            if(ticks==290)input(0,0);
            if(ticks==325)server((p,t)->check(p.getVehicle()==t&&t.getAction().equals("idle"),"first-person embark completes original full clock"));
            if(ticks==340)input(4096,0);
            if(ticks==341)input(0,0);
            if(ticks==420)server((p,t)->check(p.getVehicle()==null&&t.getDisembarkTicks()==0&&t.getAction().equals("idle"),"first-person exit releases pilot and completes empty cockpit recovery"));
            // Vanilla only sends player positions when its camera entity is the player.
            // External QA camera must still transmit genuine simulated LocalPlayer physics.
            if(!firstPerson&&mc.player.getVehicle()==null&&mc.getCameraEntity()!=mc.player)mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot(mc.player.getX(),mc.player.getY(),mc.player.getZ(),mc.player.getYRot(),mc.player.getXRot(),mc.player.onGround()));
            if(ticks>2&&ticks<430){var row=clientContext();append("client_timeline.jsonl",row);if(Boolean.getBoolean("bt.qaRecord")){Files.createDirectories(OUT.resolve("frames"));Screenshot.grab(OUT.resolve("frames").toFile(),String.format(Locale.ROOT,"%04d_sequence.png",ticks),mc.getMainRenderTarget(),ignored->{});}if(Set.of(26,40,65,95,135,150,160,180,199,236,250,275,305,345,360,370,395,410).contains(ticks))Screenshot.grab(OUT.toFile(),String.format(Locale.ROOT,"%04d_%s.png",ticks,phase()),mc.getMainRenderTarget(),ignored->{});}
            if(ticks==430){for(int r=1;r<=2;r++)check(airborne.contains(r)&&landed.contains(r),"round "+r+" release completes physical airborne-to-ground transition");check(clips.contains(19)&&clips.contains(20),"actual body renderer submitted approved BB19 embark and BB20 exit="+clips);for(int r=1;r<=2;r++)for(String key:List.of("embark:1","embark:40","embark:79","embark:80","exit:"+(BtAnimationTimings.DISEMBARK_RELEASE_TICKS-1),"exit:"+BtAnimationTimings.DISEMBARK_RELEASE_TICKS,"exit:"+(BtAnimationTimings.DISEMBARK_TICKS-1),"exit:"+BtAnimationTimings.DISEMBARK_TICKS))check(boundarySeen.contains(r+":"+key),"round "+r+" exact server boundary observed "+key);var report=new LinkedHashMap<String,Object>();report.put("scenario","actual right-click packets plus actual pilot exit packets, world and first-person");report.put("checks",checks);report.put("failures",checks.stream().filter(c->!Boolean.TRUE.equals(c.get("pass"))).count());report.put("rendered_body_clips",clips);report.put("limitations","Closed hatch visual acceptance requires final frame review; action idle alone is not a geometry assertion.");Files.writeString(OUT.resolve("complete.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));mc.stop();}
        }catch(Exception e){throw new RuntimeException(e);}}
    @SubscribeEvent public static void serverTick(ServerTickEvent.Post event){if(!enabled()||pilotId==null||titanId==null)return;var p=event.getServer().getPlayerList().getPlayer(pilotId);if(p==null)return;var entity=p.serverLevel().getEntity(titanId);if(!(entity instanceof TitanEntity t))return;String action=t.getAction();int now=(int)p.serverLevel().getGameTime();
        if(action.equals("embark")){if(boardStart!=t.getActionStartTick()){previousPilot.remove(round);maxPilotStep.put(round,0D);maxPilotY.put(round,p.getY());}boardStart=t.getActionStartTick();}if(action.equals("disembark"))exitStart=t.getActionStartTick();
        int a=boardStart<0?-1:now-boardStart,b=exitStart<0?-1:now-exitStart;var row=new LinkedHashMap<String,Object>();row.put("server_tick",now);row.put("client_tick",ticks);row.put("round",round);row.put("action",action);row.put("embark_elapsed",a);row.put("exit_elapsed",b);row.put("exit_remaining",t.getDisembarkTicks());row.put("mounted",p.getVehicle()==t);row.put("position",t.position().toString());row.put("pilot_position",p.position().toString());row.put("pilot_velocity",p.getDeltaMovement().toString());row.put("pilot_ground",p.onGround());row.put("pilot_no_gravity",p.isNoGravity());row.put("pilot_no_physics",p.noPhysics);row.put("floor_at_y79",p.serverLevel().getBlockState(BlockPos.containing(p.getX(),79,p.getZ())).toString());row.put("pilot_flying",p.getAbilities().flying);row.put("pilot_game_mode",p.gameMode.getGameModeForPlayer().getName());row.put("magazine",t.getMagazine());row.put("energy",t.getEnergy());append("server_timeline.jsonl",row);
        if(action.equals("embark")){var previous=previousPilot.put(round,p.position());if(previous!=null)maxPilotStep.merge(round,p.position().distanceTo(previous),Math::max);maxPilotY.merge(round,p.getY(),Math::max);}
        if(a==1&&boundarySeen.add(round+":approach"))check(Math.abs(p.position().subtract(t.position()).horizontalDistance()-4.02)<.35&&Math.abs(p.getY()-80)<.1,"pilot begins actual source approach four metres in front on ground");
        if(a==80&&boundarySeen.add(round+":physical_boarding")){check(maxPilotY.getOrDefault(round,0D)>83.5,"physical pilot climbs to cockpit rather than teleporting to seat");check(maxPilotStep.getOrDefault(round,99D)<1.25,"source boarding has no per-tick teleport; max step="+maxPilotStep.get(round));}
        if(exitStart>=0&&b>=BtAnimationTimings.DISEMBARK_RELEASE_TICKS&&b<100&&p.getVehicle()==null){maxExitY.merge(round,p.getY(),Math::max);if(!p.onGround()&&Math.abs(p.getDeltaMovement().y)>.02)airborne.add(round);if(p.onGround()&&b>BtAnimationTimings.DISEMBARK_RELEASE_TICKS+8)landed.add(round);}
        if(b==80&&boundarySeen.add(round+":exit_physics")){check(airborne.contains(round),"released pilot has real gravity-driven flight");check(landed.contains(round)&&p.onGround()&&p.getY()<80.2,"released pilot lands on ground without hovering");}
        if(Set.of(1,40,79,80).contains(a)&&boundarySeen.add(round+":embark:"+a)){if(a<80){check(p.getVehicle()==t&&action.equals("embark"),"embark tick "+a+" preserves mounted original body action");check(!t.mainFire(),"embark tick "+a+" rejects weapon fire");check(t.getMagazine()==30&&Math.abs(t.getZ()-.5)<.02,"embark tick "+a+" rejects held movement/fire input");}else check(action.equals("idle")&&p.getVehicle()==t,"embark tick80 returns to cockpit idle at full source duration");}
        if(Set.of(BtAnimationTimings.DISEMBARK_RELEASE_TICKS-1,BtAnimationTimings.DISEMBARK_RELEASE_TICKS,BtAnimationTimings.DISEMBARK_TICKS-1,BtAnimationTimings.DISEMBARK_TICKS).contains(b)&&boundarySeen.add(round+":exit:"+b)){check(t.getDisembarkTicks()==Math.max(0,BtAnimationTimings.DISEMBARK_TICKS-b),"exit tick "+b+" has exact remaining clock");if(b<BtAnimationTimings.DISEMBARK_RELEASE_TICKS)check(p.getVehicle()==t,"pre-release boundary retains pilot");else check(p.getVehicle()==null&&t.getPassengers().isEmpty(),"exit tick "+b+" has released pilot at approved event");if(b<BtAnimationTimings.DISEMBARK_TICKS){check(action.equals("disembark")&&!t.mainFire(),"exit tick "+b+" keeps authored recovery and blocks weapon");var result=t.interact(p,InteractionHand.MAIN_HAND);check(p.getVehicle()==null||b<BtAnimationTimings.DISEMBARK_RELEASE_TICKS,"exit recovery right-click cannot re-enter cockpit; result="+result);}else check(action.equals("idle"),"full exit clock restores closed cockpit idle");}
    }
}
