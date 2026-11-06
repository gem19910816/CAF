package com.caf.bt7274.client;

import com.caf.bt7274.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Second isolated real-game interaction scenario. Never enabled in a release or a normal world. */
@EventBusSubscriber(modid="bt7274",value=Dist.CLIENT)
public final class BbInteractionQa {
    private static final Path OUT=Path.of(System.getProperty("bt.qaOut","F:/自制武器源文件/08_BT7274/previews/bb_interactions"));
    private static int ticks,assertions,magazine;
    private static UUID titanId,foreignId,rocketId,targetId;
    private static float targetHealth;
    private static final Map<BlockPos,BlockState> wall=new LinkedHashMap<>();
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);assertions++;try{Files.writeString(OUT.resolve("assertions.log"),"PASS "+message+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception e){throw new RuntimeException(e);}}
    private static void input(int buttons){System.setProperty("bt.qaButtons",Integer.toString(buttons));System.setProperty("bt.qaForward","0");System.setProperty("bt.qaStrafe","0");}
    private static void server(java.util.function.BiConsumer<net.minecraft.server.level.ServerPlayer,TitanEntity> action){var mc=Minecraft.getInstance();UUID id=mc.player.getUUID();mc.getSingleplayerServer().execute(()->{var p=mc.getSingleplayerServer().getPlayerList().getPlayer(id);var e=titanId==null?null:p.serverLevel().getEntity(titanId);action.accept(p,e instanceof TitanEntity t?t:null);});}
    private static Vec3 forward(net.minecraft.server.level.ServerPlayer p){return p.getLookAngle().multiply(1,0,1).normalize();}
    private static void target(net.minecraft.server.level.ServerPlayer p,TitanEntity titan){var target=EntityType.HUSK.create(p.serverLevel());Vec3 at=titan.getEyePosition().add(forward(p).scale(12));target.setPos(at.x,at.y-target.getEyeHeight(),at.z);target.setNoAi(true);target.setNoGravity(true);target.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(1000);target.setHealth(500);p.serverLevel().addFreshEntity(target);targetId=target.getUUID();targetHealth=target.getHealth();}
    private static void captureHitscan(net.minecraft.server.level.ServerPlayer p,TitanEntity titan){var arrow=EntityType.ARROW.create(p.serverLevel());arrow.setPos(titan.getEyePosition().add(forward(p).scale(4)));var foreign=(TitanEntity)p.serverLevel().getEntity(foreignId);check(!titan.hurt(p.serverLevel().damageSources().arrow(arrow,foreign),100),"directional projectile damage is stored by active Vortex");check(titan.getVortexCapturedCount()>0,"stored hitscan increments captured counter");arrow.discard();}
    private static void buildWall(net.minecraft.server.level.ServerPlayer p,TitanEntity titan){BlockPos center=BlockPos.containing(titan.getEyePosition().add(forward(p).scale(6)));for(int x=-2;x<=2;x++)for(int y=-2;y<=2;y++){var pos=center.offset(x,y,0);wall.put(pos,p.serverLevel().getBlockState(pos));p.serverLevel().setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());}}
    private static void clearWall(net.minecraft.server.level.ServerPlayer p){wall.forEach((pos,state)->p.serverLevel().setBlockAndUpdate(pos,state));wall.clear();}
    private static void remove(net.minecraft.server.level.ServerPlayer p,UUID id){if(id!=null){var e=p.serverLevel().getEntity(id);if(e!=null)e.discard();}}
    private static void shot(String name){var mc=Minecraft.getInstance();Screenshot.grab(OUT.toFile(),name,mc.getMainRenderTarget(),ignored->{});}
    @SubscribeEvent public static void before(ClientTickEvent.Pre event){
        if(!Boolean.getBoolean("bt.bbInteractionQa"))return;var mc=Minecraft.getInstance();if(mc.player==null)return;
        for(var key:List.of(mc.options.keyShift,mc.options.keyJump,mc.options.keyUp,mc.options.keyDown,mc.options.keyLeft,mc.options.keyRight,mc.options.keyAttack,mc.options.keyUse))key.setDown(false);
        mc.player.setShiftKeyDown(false);mc.player.setYRot(0);mc.player.setXRot(0);
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!Boolean.getBoolean("bt.bbInteractionQa"))return;var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null)return;ticks++;
        try{
            if(ticks==1){Files.createDirectories(OUT);Files.writeString(OUT.resolve("assertions.log"),"");Files.deleteIfExists(OUT.resolve("complete.txt"));input(0);mc.options.pauseOnLostFocus=false;mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);server((p,t)->{p.stopRiding();for(var e:p.serverLevel().getAllEntities())if(e instanceof TitanEntity||e instanceof TitanRocket)e.discard();var titan=BT7274Mod.TITAN.get().create(p.serverLevel());titan.setOwner(p.getUUID());titan.setPos(.5,80,.5);titan.setNoAi(true);p.serverLevel().addFreshEntity(titan);titanId=titan.getUUID();var foreign=BT7274Mod.TITAN.get().create(p.serverLevel());foreign.setOwner(UUID.randomUUID());foreign.setPos(20.5,80,.5);foreign.setNoAi(true);p.serverLevel().addFreshEntity(foreign);foreignId=foreign.getUUID();p.setGameMode(GameType.CREATIVE);p.connection.teleport(.5,80,4.5,0,0);check(p.startRiding(titan),"interaction probe has its actual owner mounted");});}
            if(ticks==2)resources();
            if(ticks==10)input(32);
            if(ticks==14)server((p,t)->{check(t.isVortexActive(),"real Vortex active before custom rocket approach");var rocket=BT7274Mod.ROCKET.get().create(p.serverLevel());var foreign=(TitanEntity)p.serverLevel().getEntity(foreignId);rocket.launch(foreign,t.getEyePosition().add(forward(p).scale(4)),forward(p).scale(-.2));p.serverLevel().addFreshEntity(rocket);rocketId=rocket.getUUID();});
            if(ticks==20){shot("01_captured_titan_rocket.png");server((p,t)->{var e=p.serverLevel().getEntity(rocketId);check(e instanceof TitanRocket r&&r.isAlive()&&r.getDeltaMovement().lengthSqr()<1e-8,"Vortex captures and holds actual TitanRocket entity");check(t.getVortexCapturedCount()>0,"custom rocket increments real captured count");});}
            if(ticks==21)input(0);
            if(ticks==24)server((p,t)->{var e=p.serverLevel().getEntity(rocketId);check(e instanceof TitanRocket r&&t.getUUID().equals(r.getTitanOwnerUUID())&&r.getDeltaMovement().dot(forward(p))>1,"Vortex reflects TitanRocket with new owner and forward velocity");remove(p,rocketId);});
            if(ticks==40)input(32);
            if(ticks==44)server((p,t)->{check(t.isVortexActive(),"wall probe begins with active Vortex");target(p,t);buildWall(p,t);captureHitscan(p,t);});
            if(ticks==45)input(0);
            if(ticks==49){shot("02_wall_blocks_vortex_return.png");server((p,t)->{var target=(LivingEntity)p.serverLevel().getEntity(targetId);check(target!=null&&target.getHealth()==targetHealth,"solid wall blocks reflected hitscan damage to enemy behind it");check(wall.keySet().stream().allMatch(pos->p.serverLevel().getBlockState(pos).is(Blocks.STONE)),"wall remains intact after hitscan return");clearWall(p);remove(p,targetId);});}
            if(ticks==60)input(32);
            if(ticks==64)server((p,t)->{check(t.isVortexActive(),"unblocked positive control begins with active Vortex");target(p,t);captureHitscan(p,t);});
            if(ticks==65)input(0);
            if(ticks==69)server((p,t)->{var target=(LivingEntity)p.serverLevel().getEntity(targetId);check(target!=null&&target.getHealth()<targetHealth,"unblocked Vortex return actually damages aligned enemy");remove(p,targetId);});
            if(ticks==90)server((p,t)->{check(t.meleeStrike(),"second probe starts full body melee");magazine=t.getMagazine();});
            if(ticks==94)server((p,t)->{check(!t.mainFire()&&t.getMagazine()==magazine,"main fire rejects during body melee without spending round");check(t.getAction().equals("melee"),"weapon fire cannot replace active melee body action");});
            if(ticks==90+BtAnimationTimings.MELEE_HIT_TICKS+3)server((p,t)->check(t.getAction().equals("melee"),"melee retains its body clip beyond damage event"));
            if(ticks==90+BtAnimationTimings.MELEE_TICKS+3)server((p,t)->check(!t.getAction().equals("melee"),"melee returns after its complete body clock"));
            if(ticks==170)server((p,t)->{p.setShiftKeyDown(true);p.stopRiding();p.setShiftKeyDown(false);check(p.getVehicle()==t&&t.getDisembarkTicks()==BtAnimationTimings.DISEMBARK_TICKS,"normal Shift dismount event retains pilot and starts approved exit");BbExitBoundaryQa.watch(p,t,OUT);});
            if(ticks==180)server((p,t)->{check(!t.mainFire(),"main fire rejects during normal disembark");check(t.getAction().equals("disembark")&&p.getVehicle()==t,"blocked weapon fire cannot replace normal exit body action");});
            if(ticks==170+BtAnimationTimings.DISEMBARK_TICKS-7)server((p,t)->check(p.getVehicle()==null&&t.getDisembarkTicks()>0&&t.getAction().equals("disembark"),"Shift exit keeps empty-cockpit recovery until full action completes"));
            if(ticks==170+BtAnimationTimings.DISEMBARK_TICKS+3)server((p,t)->check(p.getVehicle()==null&&t.getDisembarkTicks()==0,"Shift dismount releases pilot after complete exit clip"));
            if(ticks==170+BtAnimationTimings.DISEMBARK_TICKS+5)server((p,t)->{p.connection.teleport(t.getX(),t.getY(),t.getZ()+4,0,0);t.interact(p,net.minecraft.world.InteractionHand.MAIN_HAND);check(p.getVehicle()==t&&t.getAction().equals("embark"),"normal interaction re-enters after Shift exit");});
            if(ticks==340)server((p,t)->{t.requestEject();check(t.getEjectConfirmTicks()>0&&t.getEjectTicks()==0,"first eject press only confirms intent");});
            if(ticks==342)server((p,t)->{t.requestEject();magazine=t.getMagazine();check(t.getEjectTicks()==BtAnimationTimings.EJECT_TICKS&&t.getAction().equals("eject"),"second eject press starts approved body clock");check(!t.mainFire(),"main fire rejects immediately on ejection");});
            if(ticks==350){shot("03_body_ejection_priority.png");server((p,t)->{t.acceptInput(p,0,0,1);check(!t.mainFire()&&t.getMagazine()==magazine,"held fire cannot spend rounds during ejection");check(t.getAction().equals("eject"),"held fire cannot replace ejection body action");});}
            if(ticks==342+BtAnimationTimings.EJECT_RELEASE_TICKS-4)server((p,t)->check(p.getVehicle()==t,"ejection retains pilot before approved launch event"));
            if(ticks==342+BtAnimationTimings.EJECT_RELEASE_TICKS+4)server((p,t)->check(p.getVehicle()==null&&p.getY()>t.getY()+5,"ejection launches pilot at approved event clock"));
            if(ticks==342+BtAnimationTimings.EJECT_TICKS+4)server((p,t)->check(!t.isAlive(),"abandoned chassis expires after complete ejection body clip"));
            if(ticks==405){Files.writeString(OUT.resolve("complete.txt"),"Actual isolated custom-rocket capture/return, hitscan wall occlusion and positive control, Shift EntityMountEvent exit, melee/ejection weapon priority. Assertions="+assertions+". No visual or audible acceptance asserted.");mc.stop();}
        }catch(Exception error){throw new RuntimeException(error);}
    }
    private static void resources()throws Exception{var resources=Minecraft.getInstance().getResourceManager();Map<String,Object> hashes=new LinkedHashMap<>();for(String name:List.of("models/native/body.glb","models/native/fp.glb","models/native/cube-provenance.json","motion/body_bb.bin","motion/bb_action_events.json","motion/bb_locomotion.json","motion/fp.json","sounds.json")){var r=resources.getResource(BT7274Mod.id(name)).orElseThrow();byte[] b;try(var in=r.open()){b=in.readAllBytes();}hashes.put(name,Map.of("bytes",b.length,"sha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b)),"source_pack",r.sourcePackId()));}Files.writeString(OUT.resolve("loaded_resource_hashes.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(hashes));}
}
