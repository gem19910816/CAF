package com.caf.bt7274.client;

import com.caf.bt7274.*;
import com.caf.btmesh.*;
import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.sound.*;

/** Actual integrated-server regression; opt-in in a disposable BT_MESH_QA world only. */
@EventBusSubscriber(modid="bt7274",value=Dist.CLIENT)
public final class Bb28RegressionQa {
    private static final Path OUT=Path.of(System.getProperty("bt.qaOut","build/bb28-regression"));
    private static final Gson JSON=new Gson();
    private static final List<Map<String,Object>> checks=new CopyOnWriteArrayList<>(),sounds=new CopyOnWriteArrayList<>();
    private static volatile UUID titanId,targetId;
    private static volatile int ticks,shotsBefore,maxLocks,followSprintSamples;
    private static volatile double maxStepY,wallMaxZ,followStartZ,followEndZ,followMaxSpeed;
    private static volatile float shieldBeforeFire,shieldAfterFire;
    private static volatile int fireHurtTransitions,lastFireStart=-1;
    private static ArmorStand camera;
    private static boolean firstPerson;
    private static final Set<String> fpPoses=new HashSet<>();
    private static boolean enabled(){return Boolean.getBoolean("bt.bb28Qa");}
    private static String phase(){int t=ticks;return t<190?"deployment":t<300?"terrain":t<430?"environmental_damage":t<530?"fp_sprint":t<565?"world_fire":t<600?"fp_fire":t<720?"rockets":t<850?"shield_and_disembark":t<985?"follow":"finish";}
    private static synchronized void append(String file,Object row){try{Files.createDirectories(OUT);Files.writeString(OUT.resolve(file),JSON.toJson(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception e){throw new RuntimeException(e);}}
    private static void check(boolean ok,String text){var row=Map.<String,Object>of("client_tick",ticks,"pass",ok,"assertion",text);checks.add(row);append("assertions.jsonl",row);}
    private static void input(int buttons,float forward){System.setProperty("bt.qaButtons",Integer.toString(buttons));System.setProperty("bt.qaForward",Float.toString(forward));System.setProperty("bt.qaStrafe","0");}
    private static TitanEntity titan(){var mc=Minecraft.getInstance();if(mc.level==null)return null;for(var e:mc.level.entitiesForRendering())if(e instanceof TitanEntity t&&t.getUUID().equals(titanId))return t;return null;}
    private static void server(java.util.function.BiConsumer<ServerPlayer,TitanEntity> action){var mc=Minecraft.getInstance();UUID id=mc.player.getUUID();var s=mc.getSingleplayerServer();if(s==null)return;s.execute(()->{var p=s.getPlayerList().getPlayer(id);if(p==null)return;var e=titanId==null?null:p.serverLevel().getEntity(titanId);try{action.accept(p,e instanceof TitanEntity t?t:null);}catch(Throwable ex){check(false,"server scenario exception: "+ex);}});}
    private static void screenshot(String label){var mc=Minecraft.getInstance();Screenshot.grab(OUT.toFile(),String.format(Locale.ROOT,"%04d_%s.png",ticks,label),mc.getMainRenderTarget(),ignored->{});}
    private static void captureFrame(String label)throws java.io.IOException{var mc=Minecraft.getInstance();Path dir=OUT.resolve("frames");Files.createDirectories(dir);String file=String.format(Locale.ROOT,"%04d_%s.png",ticks,label);var row=new LinkedHashMap<String,Object>();row.put("tick",ticks);row.put("file",file);row.put("phase",phase());row.put("fx",effects());row.put("camera_type",mc.options.getCameraType().name());row.put("camera_position",mc.gameRenderer.getMainCamera().getPosition().toString());row.put("camera_yaw",mc.gameRenderer.getMainCamera().getYRot());var t=titan();if(t!=null)row.put("shot_count",t.getShotCount());append("capture_frames.jsonl",row);Screenshot.grab(dir.toFile(),file,mc.getMainRenderTarget(),ignored->{});}
    private static void reposition(ServerPlayer p,TitanEntity t,double z){t.setDeltaMovement(Vec3.ZERO);t.setPos(.5,80,z);t.setYRot(0);t.yBodyRot=0;t.setXRot(0);p.setYRot(0);p.setXRot(0);}
    private static Map<String,Long> effects(){try{Class<?> c=Class.forName("com.caf.bt7274.client.TitanEffects");return (Map<String,Long>)c.getMethod("diagnostics").invoke(null);}catch(Exception e){return Map.of();}}
    private static Object fpContext(){try{var c=NativeFirstPersonRenderer.class;var f=c.getDeclaredField("displayedState");f.setAccessible(true);var v=c.getDeclaredField("view");v.setAccessible(true);var m=(org.joml.Matrix4f)v.get(null);float[] a=new float[16];m.get(a);if(firstPerson&&phase().equals("fp_sprint"))fpPoses.add(Arrays.toString(a));return Map.of("state",String.valueOf(f.get(null)),"view",a);}catch(Exception e){return Map.of("diagnostic_error",e.toString());}}
    @SubscribeEvent public static void before(ClientTickEvent.Pre event){if(!enabled())return;var mc=Minecraft.getInstance();if(mc.player==null)return;for(var key:List.of(mc.options.keyShift,mc.options.keyJump,mc.options.keyUp,mc.options.keyDown,mc.options.keyLeft,mc.options.keyRight,mc.options.keyAttack,mc.options.keyUse))key.setDown(false);mc.player.setShiftKeyDown(false);mc.player.setYRot(0);mc.player.setXRot(0);}
    @SubscribeEvent public static void camera(RenderFrameEvent.Pre event){
        if(!enabled())return;var mc=Minecraft.getInstance();var t=titan();if(t==null||mc.player==null)return;
        if(firstPerson){mc.setCameraEntity(mc.player);mc.options.setCameraType(CameraType.FIRST_PERSON);return;}
        if(camera==null||camera.level()!=mc.level)camera=new ArmorStand(mc.level,0,0,0);
        var at=t.getPosition(event.getPartialTick().getGameTimeDeltaPartialTick(false));
        // Side-front overview sees gun, arm shield, and missile approach simultaneously.
        double x=at.x+11,z=at.z+10,y=at.y+4.5;
        float yaw=132,pitch=4;camera.moveTo(x,y-camera.getEyeHeight(),z,yaw,pitch);camera.setYHeadRot(yaw);camera.yHeadRotO=yaw;camera.yRotO=yaw;camera.xRotO=pitch;camera.xo=x;camera.yo=camera.getY();camera.zo=z;
        // The anchor already looks toward BT. FRONT reverses that view by 180°.
        // BACK keeps this aim while avoiding the pilot-only first-person body suppression.
        mc.setCameraEntity(camera);mc.options.setCameraType(mc.player.getVehicle()==t?CameraType.THIRD_PERSON_BACK:CameraType.FIRST_PERSON);
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!enabled())return;var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null)return;ticks++;
        try{
            if(ticks==1){
                Files.createDirectories(OUT);for(String f:List.of("assertions.jsonl","actual_state.jsonl","actual_sound_events.jsonl","capture_frames.jsonl","complete.json"))Files.deleteIfExists(OUT.resolve(f));
                input(0,0);mc.options.pauseOnLostFocus=false;mc.options.hideGui=false;mc.options.fov().set(75);
                server((p,t)->{p.stopRiding();var stale=new ArrayList<Entity>();for(var e:p.serverLevel().getAllEntities())if(e instanceof TitanEntity||e instanceof TitanRocket||e instanceof net.minecraft.world.entity.monster.Monster)stale.add(e);stale.forEach(Entity::discard);
                    for(int x=-8;x<=8;x++)for(int z=-8;z<=155;z++){p.serverLevel().setBlock(new BlockPos(x,79,z),Blocks.SMOOTH_STONE.defaultBlockState(),2);for(int y=80;y<=88;y++)p.serverLevel().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);}
                    p.setGameMode(GameType.CREATIVE);p.connection.teleport(8.5,80,9.5,0,0);var n=BT7274Mod.TITAN.get().create(p.serverLevel());n.setOwner(p.getUUID());n.setPos(.5,80,.5);n.setNoAi(true);n.setMode(0);p.serverLevel().addFreshEntity(n);titanId=n.getUUID();n.beginDeployment(new BlockPos(0,80,0));check(n.isDeploying()&&n.getY()>140,"deployment uses real airborne server entity");});
            }
            if(ticks==70||ticks==93||ticks==105||ticks==135)screenshot("titanfall");
            if(ticks==180)server((p,t)->{check(t!=null&&!t.isDeploying()&&t.onGround(),"deployment fully recovers on ground");check(p.startRiding(t),"actual owner mounted");for(int x=-6;x<=6;x++){p.serverLevel().setBlock(new BlockPos(x,80,8),Blocks.STONE.defaultBlockState(),2);for(int y=80;y<=81;y++)p.serverLevel().setBlock(new BlockPos(x,y,16),Blocks.STONE.defaultBlockState(),2);for(int y=80;y<=87;y++)p.serverLevel().setBlock(new BlockPos(x,y,29),Blocks.STONE.defaultBlockState(),2);}maxStepY=80;});
            if(ticks==190)input(128,1);
            if(ticks>=195&&ticks<=290&&ticks%2==0)server((p,t)->{maxStepY=Math.max(maxStepY,t.getY());wallMaxZ=Math.max(wallMaxZ,t.getZ());});
            if(ticks==225||ticks==260||ticks==290)screenshot("terrain");
            if(ticks==295){input(0,0);server((p,t)->{check(maxStepY>=81.9,"pilot naturally stepped over two-block terrain; max feet Y="+maxStepY);check(t.getZ()>20,"minor steps do not trap Titan feet; final Z="+t.getZ());check(wallMaxZ<28,"tall solid wall remains collidable; max Z="+wallMaxZ);reposition(p,t,42.5);shieldBeforeFire=t.getShield();for(int x=-1;x<=1;x++)for(int z=41;z<=43;z++){p.serverLevel().setBlock(new BlockPos(x,79,z),Blocks.NETHERRACK.defaultBlockState(),2);p.serverLevel().setBlock(new BlockPos(x,80,z),Blocks.FIRE.defaultBlockState(),2);}});}
            // Real fire blocks plus deliberately worst-case 20 Hz damage callbacks.
            if(ticks>=305&&ticks<405)server((p,t)->{t.hurt(t.damageSources().inFire(),1);if(t.getAction().equals("hurt")&&t.getActionStartTick()!=lastFireStart){lastFireStart=t.getActionStartTick();fireHurtTransitions++;}});
            if(ticks==410){server((p,t)->{shieldAfterFire=t.getShield();check(shieldAfterFire<shieldBeforeFire,"continuous fire still damages independent shield");check(fireHurtTransitions<=3,"environmental damage cannot constantly restart hurt body clip; transitions="+fireHurtTransitions);for(int x=-1;x<=1;x++)for(int z=41;z<=43;z++)p.serverLevel().setBlock(new BlockPos(x,80,z),Blocks.AIR.defaultBlockState(),2);t.clearFire();reposition(p,t,48.5);});long impacts=sounds.stream().filter(s->((Number)s.get("tick")).intValue()>=305&&((Number)s.get("tick")).intValue()<405&&s.get("stage").equals("requested")&&(s.get("id").toString().contains(":shield_hit")||s.get("id").toString().contains(":hurt"))).count();check(impacts<=8,"five seconds of continuous fire has bounded impact sound requests="+impacts);}
            if(ticks==416)server((p,t)->{check(t.hurt(t.damageSources().generic(),1),"non-environmental shield impact still applies");check(t.getAction().equals("hurt"),"non-environmental impact retains original hurt feedback");});
            if(ticks==428){long positive=sounds.stream().filter(s->((Number)s.get("tick")).intValue()>=416&&((Number)s.get("tick")).intValue()<428&&s.get("stage").equals("requested")&&(s.get("id").toString().contains(":shield_hit")||s.get("id").toString().contains(":hurt"))).count();check(positive>0,"combat positive control still requests real impact audio="+positive);}
            if(ticks==430){firstPerson=true;input(128,1);}
            if(ticks==450||ticks==466||ticks==482||ticks==498||ticks==515)screenshot("fp_original_sprint");
            if(ticks==520){input(0,0);check(fpPoses.size()>12,"actual original first-person sprint view changes over frames; poses="+fpPoses.size());server((p,t)->{check(t.getZ()>65,"FP sprint physically advances on runway; Z="+t.getZ());reposition(p,t,52.5);shotsBefore=t.getShotCount();});}
            if(ticks==535){firstPerson=false;input(1,0);}
            if(ticks==542||ticks==548||ticks==554)screenshot("world_muzzle_flash");
            if(ticks==560){input(0,0);server((p,t)->check(t.getShotCount()>shotsBefore,"world firing actually consumes rounds and increments shot count"));}
            if(ticks==568){firstPerson=true;input(1,0);}
            if(ticks==575||ticks==581||ticks==587)screenshot("fp_muzzle_flash");
            if(!Boolean.getBoolean("bt.qaRecord")&&ticks>=535&&ticks<=555)captureFrame("world_muzzle");
            if(!Boolean.getBoolean("bt.qaRecord")&&ticks>=568&&ticks<=590)captureFrame("fp_muzzle");
            if(ticks==595){input(0,0);firstPerson=false;server((p,t)->{var enemy=EntityType.HUSK.create(p.serverLevel());var at=t.getEyePosition().add(0,0,24);enemy.setPos(at.x,at.y-enemy.getEyeHeight(),at.z);enemy.setNoAi(true);enemy.setNoGravity(true);enemy.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(5000);enemy.setHealth(5000);p.serverLevel().addFreshEntity(enemy);targetId=enemy.getUUID();});}
            if(ticks==605)input(8,0);
            if(ticks>=615&&ticks<685&&ticks%5==0)server((p,t)->maxLocks=Math.max(maxLocks,t.getRocketLocks()));
            if(ticks==676)screenshot("rocket_lock_hud");
            if(ticks==685){input(0,0);check(maxLocks>0,"actual guided missile acquisition obtained target locks="+maxLocks);}
            if(ticks==692||ticks==699||ticks==706)screenshot("visible_rocket_launch");
            if(!Boolean.getBoolean("bt.qaRecord")&&ticks>=685&&ticks<=712)captureFrame("world_rocket");
            if(ticks==720)input(32,0);
            if(ticks==735)screenshot("world_vortex_surface");
            if(ticks==745){firstPerson=true;}
            if(ticks==760)screenshot("fp_vortex_surface");
            if(ticks==775){input(0,0);firstPerson=false;}
            if(ticks==780)server((p,t)->{if(targetId!=null){var enemy=p.serverLevel().getEntity(targetId);if(enemy!=null)enemy.discard();}check(t.requestDisembark(),"normal authored disembark requested before AI follow test");});
            if(ticks==850)server((p,t)->{check(p.getVehicle()==null,"normal complete disembark released real pilot");t.setNoAi(false);t.setMode(1);t.setTarget(null);reposition(p,t,90.5);p.connection.teleport(.5,80,131.5,0,0);followStartZ=t.getZ();});
            if(ticks>=865&&ticks<=970&&ticks%3==0)server((p,t)->{followEndZ=t.getZ();followMaxSpeed=Math.max(followMaxSpeed,t.getGroundSpeed());if(t.getLocomotion()==2)followSprintSamples++;});
            if(ticks==885||ticks==915||ticks==945)screenshot("actual_ai_follow");
            if(ticks==980)server((p,t)->{check(followEndZ-followStartZ>16,"unpiloted AI closes owner gap promptly; travelled="+(followEndZ-followStartZ));check(followSprintSamples>4,"AI follow selects running source locomotion; sprint samples="+followSprintSamples);check(followMaxSpeed>.3,"AI follow has real running ground speed="+followMaxSpeed);});
            if(ticks>2&&ticks%2==0){var t=titan();var row=new LinkedHashMap<String,Object>();row.put("tick",ticks);row.put("phase",phase());row.put("fx",effects());row.put("first_person",firstPerson);if(t!=null){row.put("position",List.of(t.getX(),t.getY(),t.getZ()));row.put("action",t.getAction());row.put("speed",t.getGroundSpeed());row.put("locomotion",t.getLocomotion());row.put("shot_count",t.getShotCount());row.put("locks",t.getRocketLocks());row.put("vortex",t.isVortexActive());var renderer=mc.getEntityRenderDispatcher().getRenderer(t);if(renderer instanceof TitanRenderer r){var p=r.playback(t);if(p!=null)row.put("body_playback",Map.of("clip",p.clipId(),"seconds",p.seconds(),"transition",p.transition()));}}row.put("fp",fpContext());append("actual_state.jsonl",row);}
            if(Boolean.getBoolean("bt.qaRecord")&&ticks>2&&ticks<1000)captureFrame("sequence");
            if(ticks==1000){var fx=effects();for(String key:List.of("muzzle_world","muzzle_fp","shield_world","shield_fp","rocket_render","landing_particles"))check(fx.getOrDefault(key,0L)>0,"actual client submits "+key+"="+fx.getOrDefault(key,0L));var report=new LinkedHashMap<String,Object>();report.put("scenario","actual isolated integrated-server BB28 regression");report.put("checks",checks);report.put("failed",checks.stream().filter(c->!Boolean.TRUE.equals(c.get("pass"))).count());report.put("effects",fx);report.put("sound_requests",sounds.size());report.put("limits","Submissions and invariants are automated evidence. Screenshot review required for aesthetics; requested/allocated sound logs do not certify audible mix.");Files.writeString(OUT.resolve("complete.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));mc.stop();}
        }catch(Throwable e){append("fatal.jsonl",Map.of("tick",ticks,"error",e.toString()));throw new RuntimeException(e);}
    }
    private static void sound(String stage,net.minecraft.client.resources.sounds.SoundInstance s){if(!enabled()||s==null||!s.getLocation().getNamespace().equals("bt7274"))return;var row=new LinkedHashMap<String,Object>();row.put("tick",ticks);row.put("phase",phase());row.put("stage",stage);row.put("id",s.getLocation().toString());row.put("loop",s.isLooping());if(s.getSound()!=null){row.put("volume",s.getVolume());row.put("pitch",s.getPitch());row.put("sample",s.getSound().getLocation().toString());row.put("resolution","resolved");}else row.put("resolution","pending; volume/pitch not yet evaluated");sounds.add(row);append("actual_sound_events.jsonl",row);}
    @SubscribeEvent(priority=EventPriority.LOWEST)public static void requested(PlaySoundEvent e){sound("requested",e.getSound());}
    @SubscribeEvent(priority=EventPriority.LOWEST)public static void allocated(PlaySoundSourceEvent e){sound("allocated",e.getSound());}
    @SubscribeEvent(priority=EventPriority.LOWEST)public static void streaming(PlayStreamingSourceEvent e){sound("streaming",e.getSound());}
}
