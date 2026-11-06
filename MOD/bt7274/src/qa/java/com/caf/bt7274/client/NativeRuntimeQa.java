package com.caf.bt7274.client;

import com.caf.bt7274.*;
import java.nio.file.*;
import java.util.UUID;
import net.minecraft.client.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid="bt7274",value=Dist.CLIENT)
public final class NativeRuntimeQa {
    private static int ticks;private static UUID titanId,arrowId;private static final Path OUT=Path.of(System.getProperty("bt.qaOut","F:/自制武器源文件/08_BT7274/previews/native_runtime"));
    private static void server(java.util.function.BiConsumer<net.minecraft.server.level.ServerPlayer,TitanEntity> action){var mc=Minecraft.getInstance();UUID id=mc.player.getUUID();var s=mc.getSingleplayerServer();s.execute(()->{var p=s.getPlayerList().getPlayer(id);var entity=titanId==null?null:p.serverLevel().getEntity(titanId);action.accept(p,entity instanceof TitanEntity t?t:null);});}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);try{Files.writeString(OUT.resolve("assertions.log"),"PASS "+message+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception e){throw new RuntimeException(e);}}
    private static void shot(String file){if(Boolean.getBoolean("bt.qaProfileOnly"))return;var mc=Minecraft.getInstance();Screenshot.grab(OUT.toFile(),file,mc.getMainRenderTarget(),m->System.out.println("NATIVE_RUNTIME_SCREENSHOT "+file));}
    @SubscribeEvent public static void beforeTick(ClientTickEvent.Pre event){
        if(!Boolean.getBoolean("bt.nativeQa")||Boolean.getBoolean("bt.bbQa")||Boolean.getBoolean("bt.bb28Qa")||Boolean.getBoolean("bt.bb28BoardingQa")||Boolean.getBoolean("bt.bb29TerrainQa")||Boolean.getBoolean("bt.bb30CockpitQa"))return;var mc=Minecraft.getInstance();if(mc.player==null)return;
        // The QA payload drives the pilot; real desktop key presses must not
        // silently dismount it or alter the direction of an incoming test arrow.
        for(var key:java.util.List.of(mc.options.keyShift,mc.options.keyJump,mc.options.keyUp,mc.options.keyDown,mc.options.keyLeft,mc.options.keyRight,mc.options.keyAttack,mc.options.keyUse))key.setDown(false);
        if(mc.player.getVehicle() instanceof TitanEntity){mc.player.setShiftKeyDown(false);mc.player.setYRot(0);mc.player.setXRot(0);}
    }
    private static void diagnostic(net.minecraft.server.level.ServerPlayer p,TitanEntity titan,String stage){
        var values=new java.util.LinkedHashMap<String,Object>();values.put("stage",stage);values.put("client_tick",ticks);values.put("server_tick",p.server.getTickCount());
        values.put("riding_expected_titan",p.getVehicle()==titan);values.put("pilot_position",p.position().toString());values.put("pilot_yaw",p.getYRot());values.put("pilot_pitch",p.getXRot());
        if(titan!=null){values.put("titan_tick",titan.tickCount);values.put("titan_position",titan.position().toString());values.put("vortex_active",titan.isVortexActive());values.put("vortex_energy",titan.getVortexEnergy());values.put("reload_ticks",titan.getReloadTicks());}
        var arrow=arrowId==null?null:p.serverLevel().getEntity(arrowId);
        if(arrow!=null){values.put("arrow_position",arrow.position().toString());values.put("arrow_velocity",arrow.getDeltaMovement().toString());values.put("arrow_no_gravity",arrow.isNoGravity());if(titan!=null){values.put("arrow_capture_center_distance",arrow.position().distanceTo(titan.getEyePosition().add(p.getLookAngle().scale(2.8))));values.put("arrow_forward_dot",arrow.position().subtract(titan.getEyePosition()).normalize().dot(p.getLookAngle()));}}
        try{Files.writeString(OUT.resolve("mechanism_diagnostics.jsonl"),new com.google.gson.Gson().toJson(values)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception error){throw new RuntimeException(error);}
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!Boolean.getBoolean("bt.nativeQa")||Boolean.getBoolean("bt.bbQa")||Boolean.getBoolean("bt.bb28Qa")||Boolean.getBoolean("bt.bb28BoardingQa")||Boolean.getBoolean("bt.bb29TerrainQa")||Boolean.getBoolean("bt.bb30CockpitQa"))return;var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null)return;ticks++;
        try{
            if(Boolean.getBoolean("bt.qaReloadVortex")){tickReloadVortex();return;}
            if(ticks==2)recordResources();
            if(ticks==2)com.caf.btmesh.NativeRenderProfiler.describe("capture_conditions",java.util.Map.of("png_sequence_20hz",Boolean.getBoolean("bt.qaRecord"),"short_profile_only",Boolean.getBoolean("bt.qaProfileOnly"),"window_width",mc.getWindow().getWidth(),"window_height",mc.getWindow().getHeight()));
            if(Boolean.getBoolean("bt.qaRecord")&&ticks>=40&&ticks<=860)shotFrame(ticks);
            if(ticks==1){Files.createDirectories(OUT);Files.writeString(OUT.resolve("assertions.log"),"");Files.deleteIfExists(OUT.resolve("complete.txt"));mc.options.pauseOnLostFocus=false;mc.options.hideGui=true;mc.options.fov().set(55);mc.options.setCameraType(CameraType.FIRST_PERSON);server((p,t)->{p.stopRiding();for(var e:p.serverLevel().getAllEntities())if(e instanceof TitanEntity)e.discard();var n=BT7274Mod.TITAN.get().create(p.serverLevel());n.setOwner(p.getUUID());n.setPos(.5,80,.5);n.setNoAi(true);p.serverLevel().addFreshEntity(n);titanId=n.getUUID();p.setGameMode(GameType.CREATIVE);p.connection.teleport(8.5,80,9.5,140,-7);check(n.getMagazine()==30,"native starts with 30-round XO16 magazine");});}
            if(ticks>10&&ticks<90){Vec3 d=new Vec3(.5,83,.5).subtract(mc.player.getEyePosition());mc.player.setYRot((float)(java.lang.Math.atan2(d.z,d.x)*180/java.lang.Math.PI)-90);mc.player.setXRot((float)(-java.lang.Math.atan2(d.y,d.horizontalDistance())*180/java.lang.Math.PI));}
            if(ticks==60)shot("01_original_body_front.png");
            if(ticks==65)server((p,t)->p.connection.teleport(-8.5,80,2.5,-100,-8));if(ticks==80)shot("02_original_body_side.png");
            if(ticks==82)server((p,t)->p.connection.teleport(8.5,80,2.5,100,-8));if(ticks==90)shot("02b_original_body_opposite_side.png");
            if(ticks==95)server((p,t)->{p.connection.teleport(.5,80,4.5,180,0);t.interact(p,net.minecraft.world.InteractionHand.MAIN_HAND);check(p.getVehicle()==t&&t.getAction().equals("embark"),"normal entity interaction enters titan and starts embark action");});
            if(ticks==120){mc.options.hideGui=false;mc.options.setCameraType(CameraType.FIRST_PERSON);mc.player.setYRot(0);mc.player.setXRot(0);}
            if(ticks==122)server((p,t)->{check(t.getTitanHull()==9000&&t.getTitanMaxHull()==9000,"Titan hull preserves full 9000 points below vanilla attribute cap");p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,3));p.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT,5));});
            if(ticks==125){net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.getKey(org.lwjgl.glfw.GLFW.GLFW_KEY_Q,0));net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.getKey(org.lwjgl.glfw.GLFW.GLFW_KEY_F,0));}
            if(ticks==132)server((p,t)->check(p.getMainHandItem().is(net.minecraft.world.item.Items.DIAMOND)&&p.getMainHandItem().getCount()==3&&p.getOffhandItem().is(net.minecraft.world.item.Items.IRON_INGOT),"pilot Q and F do not drop items or swap offhand"));
            if(ticks==140)shot("03_native_cockpit_idle.png");
            if(ticks==150){System.setProperty("bt.qaButtons","0");System.setProperty("bt.qaForward","1");}if(ticks==170)System.setProperty("bt.qaButtons","128");if(ticks==190){System.setProperty("bt.qaButtons","0");System.setProperty("bt.qaForward","0");}
            if(ticks==164)server((p,t)->check(t.getLocomotion()==1&&t.getGroundSpeed()>0,"normal pilot travel selects walking source gait"));
            if(ticks==186)server((p,t)->check(t.getLocomotion()==2&&t.getGroundSpeed()>0,"sprint pilot travel selects fast source gait"));
            if(ticks==180)shot("04_native_fp_sprint.png");
            if(ticks==195)System.setProperty("bt.qaButtons","2");if(ticks==220){shot("05_native_fp_ads.png");server((p,t)->check(t.isAiming(),"ADS pilot intent reaches synchronized server aiming state"));}
            if(ticks==225){System.setProperty("bt.qaButtons","128");System.setProperty("bt.qaForward","1");}
            if(ticks==230)System.setProperty("bt.qaButtons","129");
            if(ticks==245)server((p,t)->check(t.getMagazine()<30&&t.getLocomotion()==1,"holding sprint while firing returns to an armed moving pose"));
            if(ticks==250&&Boolean.getBoolean("bt.qaProfileOnly")){com.caf.btmesh.NativeRenderProfiler.write(OUT.resolve("render_profile.json"),mc.getFps());Files.writeString(OUT.resolve("profile_complete.txt"),"250-tick actual isolated Minecraft profile; final assets, real body and FP states, PNG sequence disabled. No full gameplay completion asserted.");mc.stop();return;}
            if(ticks==255){System.setProperty("bt.qaButtons","0");System.setProperty("bt.qaForward","0");}
            if(ticks==256)server((p,t)->{check(t.getMagazine()<30&&t.getMagazine()>0,"native server uses magazine on fire");t.reload();check(t.getReloadTicks()==52,"native reload commits 52 tick duration");});
            if(ticks==280)shot("06_native_reload.png");
            if(ticks==315)server((p,t)->{check(t.getMagazine()==30&&t.getReloadTicks()==0,"native reload completes and replenishes magazine");t.acceptInput(p,0,1,4);float before=t.getEnergy();check(t.dash(),"native dash accepts ready driver");check(java.lang.Math.abs(t.getEnergy()-(before-50))<.01,"native dash consumes independent 50 power");});
            if(ticks==320)System.setProperty("bt.qaButtons","32");if(ticks==345)System.setProperty("bt.qaButtons","0");if(ticks==340)shot("07_native_vortex.png");
            if(ticks==350)server((p,t)->{t.acceptInput(p,0,0,0);t.releaseVortex();check(!t.isVortexActive(),"native vortex releases instead of blanket timed shield");t.addCore(100);check(t.activateCore(),"native burst core starts");check(t.getCoreChargeTicks()==37,"native core chargeup is 1.85 seconds");});
            if(ticks==405)shot("08_native_burst_core.png");
            if(ticks==520){System.setProperty("bt.qaButtons","32");server((p,t)->{diagnostic(p,t,"vortex_begin");check(!t.isCoreActive(),"native burst core ends after charge and 5.5 seconds");});}
            if(ticks==530)server((p,t)->{diagnostic(p,t,"vortex_incoming_arrow");check(p.getVehicle()==t&&t.isVortexActive(),"Vortex capture scenario retains seated pilot and active shield");var arrow=net.minecraft.world.entity.EntityType.ARROW.create(p.serverLevel());Vec3 look=p.getLookAngle(),eye=t.getEyePosition(),pos=eye.add(look.scale(4)),center=eye.add(look.scale(2.8));check(pos.subtract(eye).normalize().dot(look)>.99&&pos.distanceTo(center)<3.81,"Vortex test arrow approaches inside the forward capture volume");arrow.setPos(pos);arrow.setDeltaMovement(look.scale(-.2));p.serverLevel().addFreshEntity(arrow);arrowId=arrow.getUUID();});
            if(ticks==536)server((p,t)->{diagnostic(p,t,"vortex_capture");var arrow=p.serverLevel().getEntity(arrowId);check(arrow!=null&&arrow.isNoGravity()&&arrow.getDeltaMovement().lengthSqr()<.001,"Vortex captures an actual server arrow in its forward volume");});
            if(ticks==537)System.setProperty("bt.qaButtons","0");
            if(ticks==539)server((p,t)->{var arrow=p.serverLevel().getEntity(arrowId);check(arrow instanceof net.minecraft.world.entity.projectile.Projectile projectile&&projectile.getOwner()==t&&arrow.getDeltaMovement().dot(p.getLookAngle())>1,"Vortex releases captured projectile with reflected owner and forward velocity");});
            if(ticks==540)System.setProperty("bt.qaButtons","512");
            if(ticks==548)server((p,t)->check(t.isTitanCrouching()&&Math.abs(t.getBbHeight()-3.556)<.01,"Titan crouch changes actual collision height"));
            if(ticks==550)System.setProperty("bt.qaButtons","0");
            if(ticks==558)server((p,t)->check(!t.isTitanCrouching()&&Math.abs(t.getBbHeight()-5.969)<.01,"Titan stands when overhead volume is free"));
            if(ticks==560)server((p,t)->{check(t.deploySmoke(),"electric smoke deploys independently");check(t.getSmokeTicks()==100&&!t.deploySmoke(),"electric smoke has five-second volume and rejects cooldown repeat");});
            if(ticks==582)server((p,t)->check(t.getSmokeTicks()>0&&t.getSmokeTicks()<80,"electric smoke ticks in the server world"));
            if(ticks==590){System.setProperty("bt.qaButtons","8");server((p,t)->{var enemy=net.minecraft.world.entity.EntityType.HUSK.create(p.serverLevel());Vec3 pos=t.position().add(p.getLookAngle().multiply(1,0,1).normalize().scale(20));enemy.setPos(pos);enemy.setNoAi(true);enemy.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(5000);enemy.setHealth(5000);p.serverLevel().addFreshEntity(enemy);});}
            if(ticks==630)server((p,t)->check(t.getRocketLocks()>=5&&t.getRocketLocks()<=12,"holding ordnance acquires successive target locks"));
            if(ticks==662)System.setProperty("bt.qaButtons","0");
            if(ticks==664)server((p,t)->{check(t.getRocketLocks()==0,"releasing ordnance commits acquired locks");check(!p.serverLevel().getEntitiesOfClass(TitanRocket.class,t.getBoundingBox().inflate(50)).isEmpty(),"lock release creates actual guided rocket entities");check(t.getSmokeTicks()==0,"electric smoke ends at five seconds");});
            if(ticks==680)server((p,t)->{var probe=BT7274Mod.TITAN.get().create(p.serverLevel());probe.setPos(100.5,80,100.5);p.serverLevel().addFreshEntity(probe);var registry=p.serverLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE);var cannon=new net.minecraft.world.damagesource.DamageSource(registry.getHolderOrThrow(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE,BT7274Mod.id("titan_cannon"))),t,p);var ordnance=new net.minecraft.world.damagesource.DamageSource(registry.getHolderOrThrow(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE,BT7274Mod.id("titan_ordnance"))),t,p);probe.hurt(cannon,1100);check(probe.getShield()==0&&Math.abs(probe.getTitanHull()-8900)<.1,"independent shield absorbs first 1000 and hull takes remaining source damage");probe.hurt(ordnance,150);probe.hurt(ordnance,150);check(Math.abs(probe.getTitanHull()-8600)<.1,"two same-tick ordnance impacts both damage hull through MC cooldown");probe.discard();});
            if(ticks==690)server((p,t)->{check(t.hurt(t.damageSources().generic(),20000),"lethal hit enters doomed phase");check(t.isDoomed()&&t.isAlive()&&t.getTitanHull()==2500&&p.getVehicle()==t,"doomed phase preserves pilot with 2500 separate health");t.requestEject();check(t.getEjectConfirmTicks()==60&&t.getEjectTicks()==0,"first eject press asks for confirmation");});
            if(ticks==695)server((p,t)->{t.requestEject();check(t.getEjectTicks()==30,"second eject press starts timed sequence");});
            if(ticks==705)shot("09_doomed_ejection_warning.png");
            if(ticks==713)server((p,t)->check(p.getVehicle()==null&&p.getY()>t.getY()+5,"ejection releases and launches pilot above chassis"));
            if(ticks==733)server((p,t)->check(!t.isAlive(),"timed ejection destroys abandoned chassis"));
            if(ticks==740)server((p,t)->{var registry=TitanRegistry.get(p.server);registry.bind(p.getUUID(),titanId,0);registry.release(p.getUUID(),titanId);var n=TitanDeployment.deploy(p,new net.minecraft.core.BlockPos(0,80,0));check(n!=null,"beacon deployment service validates floor and summons owned titan");n.setNoAi(true);titanId=n.getUUID();p.connection.teleport(12.5,80,14.5,140,-18);check(n.isDeploying()&&n.getY()>140,"Titanfall starts high above requested landing point");check(!p.startRiding(n),"cannot enter airborne deployment");});
            if(ticks>745&&ticks<845){Vec3 d=new Vec3(.5,83,.5).subtract(mc.player.getEyePosition());mc.player.setYRot((float)(java.lang.Math.atan2(d.z,d.x)*180/java.lang.Math.PI)-90);mc.player.setXRot((float)(-java.lang.Math.atan2(d.y,d.horizontalDistance())*180/java.lang.Math.PI));}
            if(ticks==785)shot("10_titanfall_descending.png");
            if(ticks==802)server((p,t)->check(t.isDeploying()&&t.getY()<80.1&&t.getAction().equals("land"),"Titanfall physical contact starts landing recovery"));
            if(ticks==805)shot("11_titanfall_impact.png");
            if(ticks==821)shot("12_titanfall_recovery.png");
            if(ticks==839)server((p,t)->check(!t.isDeploying()&&t.onGround()&&Math.abs(t.getY()-80)<.1,"Titanfall becomes ready after grounded recovery"));
            if(ticks==850)shot("13_titanfall_ready.png");
            if(ticks==855)server((p,t)->{check(TitanDeployment.deploy(p,new net.minecraft.core.BlockPos(0,80,0))==null,"active ownership rejects a duplicate Titanfall");var registry=TitanRegistry.get(p.server);registry.recall(p.getUUID());t.discard();check(TitanDeployment.deploy(p,new net.minecraft.core.BlockPos(0,80,0))==null,"recalling titan preserves summon cooldown");});
            if(ticks==870){com.caf.btmesh.NativeRenderProfiler.write(OUT.resolve("render_profile.json"),mc.getFps());Files.writeString(OUT.resolve("complete.txt"),"Actual isolated NeoForge client cube model/controls/audio candidate; checks and screenshots recorded. Not an installed-user-world test.");mc.stop();}
        }catch(Exception e){throw new RuntimeException(e);}
    }
    private static void tickReloadVortex()throws Exception{
        var mc=Minecraft.getInstance();
        if(ticks==1){Files.createDirectories(OUT);Files.writeString(OUT.resolve("assertions.log"),"");Files.deleteIfExists(OUT.resolve("complete.txt"));mc.options.pauseOnLostFocus=false;mc.options.hideGui=false;mc.options.setCameraType(CameraType.FIRST_PERSON);System.setProperty("bt.qaButtons","0");System.setProperty("bt.qaForward","0");server((p,t)->{p.stopRiding();for(var entity:p.serverLevel().getAllEntities())if(entity instanceof TitanEntity)entity.discard();var titan=BT7274Mod.TITAN.get().create(p.serverLevel());titan.setOwner(p.getUUID());titan.setNoAi(true);titan.setPos(.5,80,.5);p.serverLevel().addFreshEntity(titan);titanId=titan.getUUID();p.setGameMode(GameType.CREATIVE);p.connection.teleport(.5,80,4.5,0,0);check(p.startRiding(titan),"reload/Vortex probe has the real owned pilot seated");});}
        if(ticks==2)recordResources();
        if(ticks==25)System.setProperty("bt.qaButtons","1");
        if(ticks==31)System.setProperty("bt.qaButtons","0");
        if(ticks==36)server((p,t)->check(t.getMagazine()<30&&t.getMagazine()>0,"probe spends real magazine rounds before reload"));
        if(ticks==40)System.setProperty("bt.qaButtons","32");
        if(ticks==50)shot("01_vortex_before_reload.png");
        if(ticks==52)server((p,t)->{diagnostic(p,t,"before_reload");check(p.getVehicle()==t&&t.isVortexActive(),"probe starts reload with active Vortex and seated driver");check(t.reload(),"partly used primary accepts reload while Vortex is active");check(!t.isVortexActive()&&t.getReloadTicks()==52,"primary reload immediately releases Vortex and owns the left arm");diagnostic(p,t,"after_reload");});
        if(ticks==58)shot("02_vortex_holster_into_reload.png");
        if(ticks==65)server((p,t)->{check(t.getReloadTicks()>0&&!t.isVortexActive(),"held Vortex input cannot reacquire the arm during reload");check(!t.activateShield(),"direct shield activation also rejects an active primary reload");diagnostic(p,t,"held_q_during_reload");});
        if(ticks==80)shot("03_reload_left_arm.png");
        if(ticks==95)server((p,t)->check(t.getReloadTicks()>0&&!t.isVortexActive(),"Vortex stays released through the late reload phase"));
        if(ticks==115)server((p,t)->{check(t.getReloadTicks()==0&&t.getMagazine()==30,"interlocked primary reload still commits the magazine");check(t.isVortexActive(),"held Vortex input resumes only after primary reload ends");diagnostic(p,t,"held_q_after_reload");});
        if(ticks==116)shot("04_vortex_resumes_after_reload.png");
        if(ticks==118)System.setProperty("bt.qaButtons","0");
        if(ticks==124)server((p,t)->check(!t.isVortexActive(),"releasing Q still stops the resumed Vortex"));
        if(ticks==130){Files.writeString(OUT.resolve("complete.txt"),"Actual isolated NeoForge client targeted reload/Vortex arm-ownership verification; final cube model/motion/audio resources unchanged from v3. Not a repeat of the full gameplay sequence.");mc.stop();}
    }
    private static void shotFrame(int tick)throws Exception{Path dir=OUT.resolve("frames");Files.createDirectories(dir);var mc=Minecraft.getInstance();Screenshot.grab(dir.toFile(),String.format(java.util.Locale.ROOT,"frame_%06d.png",tick-40),mc.getMainRenderTarget(),message->{});}
    private static void recordResources()throws Exception{
        var resources=Minecraft.getInstance().getResourceManager();var hashes=new java.util.LinkedHashMap<String,Object>();
        for(String name:java.util.List.of("models/native/body.glb","models/native/fp.glb","motion/body.json","motion/fp.json","textures/entity/bt7274.png","textures/entity/bt7274_glowmask.png","sounds.json")){
            var resource=resources.getResource(BT7274Mod.id(name)).orElseThrow();byte[] bytes;try(var in=resource.open()){bytes=in.readAllBytes();}
            hashes.put(name,java.util.Map.of("sha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)),"bytes",bytes.length,"source_pack",resource.sourcePackId()));
        }
        Files.writeString(OUT.resolve("loaded_resource_hashes.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(hashes));
    }
}
