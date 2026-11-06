package com.caf.bt7274.client;

import com.caf.bt7274.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.Map;
import net.minecraft.client.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid="bt7274",value=Dist.CLIENT)
public final class BbRuntimeQa {
    private static int ticks;private static UUID titanId,arrowId;private static final Path OUT=Path.of(System.getProperty("bt.qaOut","F:/自制武器源文件/08_BT7274/previews/bb_runtime"));
    /** Expand the historical scenario around actual approved BB clip clocks. */
    private static int scheduled(int legacy){
        int t=legacy;
        if(legacy>=140)t+=BtAnimationTimings.EMBARK_TICKS-52;
        if(legacy>=195)t+=120; // backwards, left, right locomotion cases.
        if(legacy>=315)t+=BtAnimationTimings.RELOAD_TICKS-52;
        if(legacy>=550)t+=BtAnimationTimings.CROUCH_DOWN_TICKS;
        if(legacy>=560)t+=BtAnimationTimings.CROUCH_UP_TICKS;
        if(legacy>=680)t+=BtAnimationTimings.DISEMBARK_TICKS+BtAnimationTimings.EMBARK_TICKS+15;
        if(legacy>=690)t+=BtAnimationTimings.MELEE_TICKS+4;
        if(legacy>=695)t+=BtAnimationTimings.DOOMED_ENTER_TICKS;
        if(legacy>=713)t+=BtAnimationTimings.EJECT_RELEASE_TICKS-15;
        if(legacy>=802&&legacy<839)t+=BtAnimationTimings.LAND_IMPACT_TICKS;
        if(legacy>=839)t+=BtAnimationTimings.LAND_TICKS-30;
        return t;
    }
    private static void input(float forward,float strafe){System.setProperty("bt.qaButtons","0");System.setProperty("bt.qaForward",Float.toString(forward));System.setProperty("bt.qaStrafe",Float.toString(strafe));}
    private static net.minecraft.world.entity.decoration.ArmorStand bodyCamera;
    /** Apply immediately before rendering, after TitanClient has handled mount camera changes. */
    @SubscribeEvent public static void camera(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event){
        if(!Boolean.getBoolean("bt.bbQa")||Boolean.getBoolean("bt.bbInteractionQa")||Boolean.getBoolean("bt.bbCoverageQa"))return;var mc=Minecraft.getInstance();if(mc.player==null||mc.level==null)return;
        TitanEntity t=watchedTitan();boolean mounted=t!=null&&mc.player.getVehicle()==t;boolean recovery=t!=null&&(t.getDisembarkTicks()>0||t.getEjectTicks()>0||t.getAction().equals("death"));
        if(Boolean.getBoolean("bt.qaBodyView")&&t!=null&&(mounted||recovery)){
            if(bodyCamera==null||bodyCamera.level()!=mc.level)bodyCamera=new net.minecraft.world.entity.decoration.ArmorStand(mc.level,0,0,0);
            // A QA-only unspawned camera anchor gives a full-body view at about 11.5m.
            // Pilot, actual inputs, entity and renderer remain the ones under test.
            double yaw=Math.toRadians(t.getYRot()),frontX=-Math.sin(yaw),frontZ=Math.cos(yaw);var at=t.getPosition(event.getPartialTick().getGameTimeDeltaPartialTick(false));
            double distance=mounted?7.5:11.5;bodyCamera.moveTo(at.x+frontX*distance,at.y+3-bodyCamera.getEyeHeight(),at.z+frontZ*distance,t.getYRot()+(mounted?0:180),0);
            if(mc.getCameraEntity()!=bodyCamera)mc.setCameraEntity(bodyCamera);mc.options.setCameraType(mounted?CameraType.THIRD_PERSON_FRONT:CameraType.FIRST_PERSON);
        }else{if(mc.getCameraEntity()!=mc.player)mc.setCameraEntity(mc.player);mc.options.setCameraType(CameraType.FIRST_PERSON);}
    }
    private static TitanEntity watchedTitan(){var mc=Minecraft.getInstance();if(mc.player==null||mc.level==null)return null;if(mc.player.getVehicle() instanceof TitanEntity mounted)return mounted;if(titanId!=null)for(var entity:mc.level.entitiesForRendering())if(entity instanceof TitanEntity found&&found.getUUID().equals(titanId))return found;return null;}
    public static Map<String,Object> playbackContext(){
        var mc=Minecraft.getInstance();Map<String,Object> values=new java.util.LinkedHashMap<>();values.put("client_tick",ticks);values.put("wall_time_unix_ms",System.currentTimeMillis());if(mc.level==null||mc.player==null)return values;values.put("game_tick",mc.level.getGameTime());values.put("camera",mc.options.getCameraType().name());values.put("qa_camera_anchor",mc.getCameraEntity()==bodyCamera);values.put("pilot_pitch",mc.player.getXRot());values.put("pilot_position_y",mc.player.getY());values.put("pilot_eye_y",mc.player.getEyePosition().y);
        TitanEntity t=watchedTitan();
        if(t!=null){values.put("titan_uuid",t.getUUID().toString());values.put("titan_y",t.getY());values.put("titan_crouching",t.isTitanCrouching());values.put("pilot_eye_above_titan",mc.player.getEyePosition().y-t.getY());values.put("body_elapsed_seconds",(mc.level.getGameTime()-t.getActionStartTick())/20.0);values.put("body_action",t.getAction());values.put("body_start_tick",t.getActionStartTick());values.put("weapon_action",t.getWeaponAction());values.put("weapon_start_tick",t.getWeaponActionStartTick());values.put("reload_remaining",t.getReloadTicks());values.put("body_animation_id",TitanRenderer.bodyAnimationId(t));values.put("body_cycle",TitanRenderer.bodyCycle(t,0));var renderer=mc.getEntityRenderDispatcher().getRenderer(t);if(renderer instanceof TitanRenderer r){var actual=r.playback(t);if(actual!=null){values.put("rendered_clip",actual.clipId());values.put("rendered_seconds",actual.seconds());values.put("render_transition",actual.transition());values.put("render_layers",actual.layers());}}}
        return values;
    }
    private static void playbackLog()throws Exception{Files.writeString(OUT.resolve("actual_playback.jsonl"),new com.google.gson.Gson().toJson(playbackContext())+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
    private static void server(java.util.function.BiConsumer<net.minecraft.server.level.ServerPlayer,TitanEntity> action){var mc=Minecraft.getInstance();UUID id=mc.player.getUUID();var s=mc.getSingleplayerServer();s.execute(()->{var p=s.getPlayerList().getPlayer(id);var entity=titanId==null?null:p.serverLevel().getEntity(titanId);action.accept(p,entity instanceof TitanEntity t?t:null);});}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);try{Files.writeString(OUT.resolve("assertions.log"),"PASS "+message+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception e){throw new RuntimeException(e);}}
    private static void shot(String file){if(Boolean.getBoolean("bt.qaProfileOnly"))return;var mc=Minecraft.getInstance();Screenshot.grab(OUT.toFile(),file,mc.getMainRenderTarget(),m->System.out.println("NATIVE_RUNTIME_SCREENSHOT "+file));}
    @SubscribeEvent public static void beforeTick(ClientTickEvent.Pre event){
        if(!Boolean.getBoolean("bt.bbQa")||Boolean.getBoolean("bt.bbInteractionQa")||Boolean.getBoolean("bt.bbCoverageQa"))return;var mc=Minecraft.getInstance();if(mc.player==null)return;
        // The QA payload drives the pilot; real desktop key presses must not
        // silently dismount it or alter the direction of an incoming test arrow.
        for(var key:java.util.List.of(mc.options.keyShift,mc.options.keyJump,mc.options.keyUp,mc.options.keyDown,mc.options.keyLeft,mc.options.keyRight,mc.options.keyAttack,mc.options.keyUse))key.setDown(false);
        if(mc.player.getVehicle() instanceof TitanEntity){mc.player.setShiftKeyDown(false);mc.player.setYRot(0);mc.player.setXRot(Float.parseFloat(System.getProperty("bt.qaPitch","0")));}
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
        if(!Boolean.getBoolean("bt.bbQa")||Boolean.getBoolean("bt.bbInteractionQa")||Boolean.getBoolean("bt.bbCoverageQa"))return;var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null)return;ticks++;
        try{
            
            if(ticks==scheduled(2))recordResources();
            if(ticks==scheduled(2))com.caf.btmesh.NativeRenderProfiler.describe("capture_conditions",java.util.Map.of("png_sequence_20hz",Boolean.getBoolean("bt.qaRecord"),"short_profile_only",Boolean.getBoolean("bt.qaProfileOnly"),"window_width",mc.getWindow().getWidth(),"window_height",mc.getWindow().getHeight()));
            if(Boolean.getBoolean("bt.qaRecord")&&ticks>=40&&ticks<=scheduled(860))shotFrame(ticks);
            if(ticks==scheduled(1)){Files.createDirectories(OUT);Files.writeString(OUT.resolve("assertions.log"),"");Files.deleteIfExists(OUT.resolve("complete.txt"));Files.deleteIfExists(OUT.resolve("actual_playback.jsonl"));Files.deleteIfExists(OUT.resolve("frame_submissions.jsonl"));Files.deleteIfExists(OUT.resolve("actual_sound_events.jsonl"));mc.options.pauseOnLostFocus=false;mc.options.hideGui=true;mc.options.fov().set(55);mc.options.setCameraType(CameraType.FIRST_PERSON);server((p,t)->{p.stopRiding();for(var e:p.serverLevel().getAllEntities())if(e instanceof TitanEntity)e.discard();var n=BT7274Mod.TITAN.get().create(p.serverLevel());n.setOwner(p.getUUID());n.setPos(.5,80,.5);n.setNoAi(true);p.serverLevel().addFreshEntity(n);titanId=n.getUUID();p.setGameMode(GameType.CREATIVE);p.connection.teleport(8.5,80,9.5,140,-7);check(n.getMagazine()==30,"native starts with 30-round XO16 magazine");});}
            if(ticks>scheduled(10)&&ticks<scheduled(90)){Vec3 d=new Vec3(.5,83,.5).subtract(mc.player.getEyePosition());mc.player.setYRot((float)(java.lang.Math.atan2(d.z,d.x)*180/java.lang.Math.PI)-90);mc.player.setXRot((float)(-java.lang.Math.atan2(d.y,d.horizontalDistance())*180/java.lang.Math.PI));}
            if(ticks==scheduled(60))shot("01_original_body_front.png");
            if(ticks==scheduled(65))server((p,t)->p.connection.teleport(-8.5,80,2.5,-100,-8));if(ticks==scheduled(80))shot("02_original_body_side.png");
            if(ticks==scheduled(82))server((p,t)->p.connection.teleport(8.5,80,2.5,100,-8));if(ticks==scheduled(90))shot("02b_original_body_opposite_side.png");
            if(ticks==scheduled(95))server((p,t)->{p.connection.teleport(.5,80,4.5,180,0);t.interact(p,net.minecraft.world.InteractionHand.MAIN_HAND);check(p.getVehicle()==t&&t.getAction().equals("embark"),"normal entity interaction enters titan and starts embark action");});
            if(ticks==scheduled(120)){mc.options.hideGui=false;mc.options.setCameraType(CameraType.FIRST_PERSON);mc.player.setYRot(0);mc.player.setXRot(Float.parseFloat(System.getProperty("bt.qaPitch","0")));}
            if(ticks==scheduled(122))server((p,t)->{check(t.getTitanHull()==9000&&t.getTitanMaxHull()==9000,"Titan hull preserves full 9000 points below vanilla attribute cap");p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,3));p.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT,5));});
            if(ticks==scheduled(125)){net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.getKey(org.lwjgl.glfw.GLFW.GLFW_KEY_Q,0));net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.getKey(org.lwjgl.glfw.GLFW.GLFW_KEY_F,0));}
            if(ticks==scheduled(132))server((p,t)->check(p.getMainHandItem().is(net.minecraft.world.item.Items.DIAMOND)&&p.getMainHandItem().getCount()==3&&p.getOffhandItem().is(net.minecraft.world.item.Items.IRON_INGOT),"pilot Q and F do not drop items or swap offhand"));
            if(ticks==scheduled(149))shot("03_native_cockpit_idle.png");
            if(ticks==scheduled(150)){System.setProperty("bt.qaButtons","0");System.setProperty("bt.qaForward","1");}if(ticks==scheduled(170))System.setProperty("bt.qaButtons","128");if(ticks==scheduled(190)){System.setProperty("bt.qaButtons","0");System.setProperty("bt.qaForward","0");}
            if(ticks==scheduled(164))server((p,t)->check(t.getLocomotion()==1&&t.getGroundSpeed()>0,"normal pilot travel selects walking source gait"));
            if(ticks==scheduled(186))server((p,t)->check(t.getLocomotion()==2&&t.getGroundSpeed()>0,"sprint pilot travel selects fast source gait"));
            if(ticks==scheduled(180))shot("04_native_fp_sprint.png");
            if(ticks==scheduled(195))System.setProperty("bt.qaButtons","2");if(ticks==scheduled(220)){shot("05_native_fp_ads.png");server((p,t)->check(t.isAiming(),"ADS pilot intent reaches synchronized server aiming state"));}
            if(ticks==scheduled(225)){System.setProperty("bt.qaButtons","128");System.setProperty("bt.qaForward","1");}
            if(ticks==scheduled(230))System.setProperty("bt.qaButtons","129");
            if(ticks==scheduled(245))server((p,t)->check(t.getMagazine()<30&&t.getLocomotion()==1,"holding sprint while firing returns to an armed moving pose"));
            if(ticks==scheduled(250)&&Boolean.getBoolean("bt.qaProfileOnly")){com.caf.btmesh.NativeRenderProfiler.write(OUT.resolve("render_profile.json"),mc.getFps());Files.writeString(OUT.resolve("profile_complete.txt"),"250-tick actual isolated Minecraft profile; final assets, real body and FP states, PNG sequence disabled. No full gameplay completion asserted.");mc.stop();return;}
            if(ticks==scheduled(255)){System.setProperty("bt.qaButtons","0");System.setProperty("bt.qaForward","0");}
            if(ticks==scheduled(256))server((p,t)->{check(t.getMagazine()<30&&t.getMagazine()>0,"native server uses magazine on fire");t.reload();check(t.getReloadTicks()==BtAnimationTimings.RELOAD_TICKS,"native reload commits approved BB reload duration");});
            if(ticks==scheduled(280))shot("06_native_reload.png");
            if(ticks==scheduled(315))server((p,t)->{check(t.getMagazine()==30&&t.getReloadTicks()==0,"native reload completes and replenishes magazine");t.acceptInput(p,0,1,4);float before=t.getEnergy();check(t.dash(),"native dash accepts ready driver");check(java.lang.Math.abs(t.getEnergy()-(before-50))<.01,"native dash consumes independent 50 power");});
            if(ticks==scheduled(320))System.setProperty("bt.qaButtons","32");if(ticks==scheduled(345))System.setProperty("bt.qaButtons","0");if(ticks==scheduled(340))shot("07_native_vortex.png");
            if(ticks==scheduled(350))server((p,t)->{t.acceptInput(p,0,0,0);t.releaseVortex();check(!t.isVortexActive(),"native vortex releases instead of blanket timed shield");t.addCore(100);check(t.activateCore(),"native burst core starts");check(t.getCoreChargeTicks()==BtAnimationTimings.CORE_CHARGE_TICKS,"native core chargeup is 1.85 seconds");});
            if(ticks==scheduled(405))shot("08_native_burst_core.png");
            if(ticks==scheduled(520)){System.setProperty("bt.qaButtons","32");server((p,t)->{diagnostic(p,t,"vortex_begin");check(!t.isCoreActive(),"native burst core ends after charge and 5.5 seconds");});}
            if(ticks==scheduled(530))server((p,t)->{diagnostic(p,t,"vortex_incoming_arrow");check(p.getVehicle()==t&&t.isVortexActive(),"Vortex capture scenario retains seated pilot and active shield");var arrow=net.minecraft.world.entity.EntityType.ARROW.create(p.serverLevel());Vec3 look=p.getLookAngle(),eye=t.getEyePosition(),pos=eye.add(look.scale(4)),center=eye.add(look.scale(2.8));check(pos.subtract(eye).normalize().dot(look)>.99&&pos.distanceTo(center)<3.81,"Vortex test arrow approaches inside the forward capture volume");arrow.setPos(pos);arrow.setDeltaMovement(look.scale(-.2));p.serverLevel().addFreshEntity(arrow);arrowId=arrow.getUUID();});
            if(ticks==scheduled(536))server((p,t)->{diagnostic(p,t,"vortex_capture");var arrow=p.serverLevel().getEntity(arrowId);check(arrow!=null&&arrow.isNoGravity()&&arrow.getDeltaMovement().lengthSqr()<.001,"Vortex captures an actual server arrow in its forward volume");});
            if(ticks==scheduled(537))System.setProperty("bt.qaButtons","0");
            if(ticks==scheduled(539))server((p,t)->{var arrow=p.serverLevel().getEntity(arrowId);check(arrow instanceof net.minecraft.world.entity.projectile.Projectile projectile&&projectile.getOwner()==t&&arrow.getDeltaMovement().dot(p.getLookAngle())>1,"Vortex releases captured projectile with reflected owner and forward velocity");});
            if(ticks==scheduled(540))System.setProperty("bt.qaButtons","512");
            if(ticks==scheduled(548))server((p,t)->check(t.isTitanCrouching()&&Math.abs(t.getBbHeight()-3.556)<.01,"Titan crouch changes actual collision height"));
            if(ticks==scheduled(550))System.setProperty("bt.qaButtons","0");
            if(ticks==scheduled(558))server((p,t)->check(!t.isTitanCrouching()&&Math.abs(t.getBbHeight()-5.969)<.01,"Titan stands when overhead volume is free"));
            if(ticks==scheduled(560))server((p,t)->{check(t.deploySmoke(),"electric smoke deploys independently");check(t.getSmokeTicks()==100&&!t.deploySmoke(),"electric smoke has five-second volume and rejects cooldown repeat");});
            if(ticks==scheduled(582))server((p,t)->check(t.getSmokeTicks()>0&&t.getSmokeTicks()<80,"electric smoke ticks in the server world"));
            if(ticks==scheduled(590)){System.setProperty("bt.qaButtons","8");server((p,t)->{var enemy=net.minecraft.world.entity.EntityType.HUSK.create(p.serverLevel());Vec3 pos=t.position().add(p.getLookAngle().multiply(1,0,1).normalize().scale(20));enemy.setPos(pos);enemy.setNoAi(true);enemy.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(5000);enemy.setHealth(5000);p.serverLevel().addFreshEntity(enemy);});}
            if(ticks==scheduled(630))server((p,t)->check(t.getRocketLocks()>=5&&t.getRocketLocks()<=12,"holding ordnance acquires successive target locks"));
            if(ticks==scheduled(662))System.setProperty("bt.qaButtons","0");
            if(ticks==scheduled(664))server((p,t)->{check(t.getRocketLocks()==0,"releasing ordnance commits acquired locks");check(!p.serverLevel().getEntitiesOfClass(TitanRocket.class,t.getBoundingBox().inflate(50)).isEmpty(),"lock release creates actual guided rocket entities");check(t.getSmokeTicks()==0,"electric smoke ends at five seconds");});
            if(ticks==scheduled(680))server((p,t)->{var probe=BT7274Mod.TITAN.get().create(p.serverLevel());probe.setPos(100.5,80,100.5);p.serverLevel().addFreshEntity(probe);var registry=p.serverLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE);var cannon=new net.minecraft.world.damagesource.DamageSource(registry.getHolderOrThrow(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE,BT7274Mod.id("titan_cannon"))),t,p);var ordnance=new net.minecraft.world.damagesource.DamageSource(registry.getHolderOrThrow(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE,BT7274Mod.id("titan_ordnance"))),t,p);probe.hurt(cannon,1100);check(probe.getShield()==0&&Math.abs(probe.getTitanHull()-8900)<.1,"independent shield absorbs first 1000 and hull takes remaining source damage");probe.hurt(ordnance,150);probe.hurt(ordnance,150);check(Math.abs(probe.getTitanHull()-8600)<.1,"two same-tick ordnance impacts both damage hull through MC cooldown");probe.discard();});
            if(ticks==scheduled(690))server((p,t)->{check(t.hurt(t.damageSources().generic(),20000),"lethal hit enters doomed phase");check(t.isDoomed()&&t.isAlive()&&t.getTitanHull()==2500&&p.getVehicle()==t,"doomed phase preserves pilot with 2500 separate health");t.requestEject();check(t.getEjectConfirmTicks()==60&&t.getEjectTicks()==0,"first eject press asks for confirmation");});
            if(ticks==scheduled(695))server((p,t)->{t.requestEject();check(t.getEjectTicks()==BtAnimationTimings.EJECT_TICKS,"second eject press starts timed sequence");});
            if(ticks==scheduled(705))shot("09_doomed_ejection_warning.png");
            if(ticks==scheduled(713))server((p,t)->check(p.getVehicle()==null&&p.getY()>t.getY()+5,"ejection releases and launches pilot above chassis"));
            if(ticks==scheduled(733))server((p,t)->check(!t.isAlive(),"timed ejection destroys abandoned chassis"));
            if(ticks==scheduled(740))server((p,t)->{var registry=TitanRegistry.get(p.server);registry.bind(p.getUUID(),titanId,0);registry.release(p.getUUID(),titanId);var n=TitanDeployment.deploy(p,new net.minecraft.core.BlockPos(0,80,0));check(n!=null,"beacon deployment service validates floor and summons owned titan");n.setNoAi(true);titanId=n.getUUID();p.connection.teleport(12.5,80,14.5,140,-18);check(n.isDeploying()&&n.getY()>140,"Titanfall starts high above requested landing point");check(!p.startRiding(n),"cannot enter airborne deployment");});
            if(ticks>scheduled(745)&&ticks<scheduled(845)){Vec3 d=new Vec3(.5,83,.5).subtract(mc.player.getEyePosition());mc.player.setYRot((float)(java.lang.Math.atan2(d.z,d.x)*180/java.lang.Math.PI)-90);mc.player.setXRot((float)(-java.lang.Math.atan2(d.y,d.horizontalDistance())*180/java.lang.Math.PI));}
            if(ticks==scheduled(785))shot("10_titanfall_descending.png");
            if(ticks==scheduled(802))server((p,t)->check(t.isDeploying()&&t.getY()<80.1&&t.getAction().equals("land"),"Titanfall physical contact starts landing recovery"));
            if(ticks==scheduled(805))shot("11_titanfall_impact.png");
            if(ticks==scheduled(821))shot("12_titanfall_recovery.png");
            if(ticks==scheduled(839))server((p,t)->check(!t.isDeploying()&&t.onGround()&&Math.abs(t.getY()-80)<.1,"Titanfall becomes ready after grounded recovery"));
            if(ticks==scheduled(850))shot("13_titanfall_ready.png");
            if(ticks==scheduled(855))server((p,t)->{check(TitanDeployment.deploy(p,new net.minecraft.core.BlockPos(0,80,0))==null,"active ownership rejects a duplicate Titanfall");var registry=TitanRegistry.get(p.server);registry.recall(p.getUUID());t.discard();check(TitanDeployment.deploy(p,new net.minecraft.core.BlockPos(0,80,0))==null,"recalling titan preserves summon cooldown");});
            if(ticks==scheduled(190)+2)input(-1,0);
            if(ticks==scheduled(190)+17){shot("04b_body_backward_walk.png");server((p,t)->check(t.getMoveForward()<-.5&&t.getLocomotion()==1,"backward pilot input reaches body direction state"));}
            if(ticks==scheduled(190)+26)input(0,1);
            if(ticks==scheduled(190)+41){shot("04c_body_left_walk.png");server((p,t)->check(t.getMoveStrafe()>.5&&t.getLocomotion()==1,"left pilot input reaches body direction state"));}
            if(ticks==scheduled(190)+50)input(0,-1);
            if(ticks==scheduled(190)+65){shot("04d_body_right_walk.png");server((p,t)->check(t.getMoveStrafe()<-.5&&t.getLocomotion()==1,"right pilot input reaches body direction state"));}
            if(ticks==scheduled(190)+72)input(0,0);
            if(ticks==scheduled(666))server((p,t)->{check(t.requestDisembark(),"normal disembark starts its own approved body clip");check(t.getDisembarkTicks()==BtAnimationTimings.DISEMBARK_TICKS&&p.getVehicle()==t,"pilot remains mounted at start of normal disembark");BbExitBoundaryQa.watch(p,t,OUT);});
            if(ticks==scheduled(666)+BtAnimationTimings.DISEMBARK_TICKS-8)server((p,t)->check(p.getVehicle()==null&&t.getDisembarkTicks()>0&&t.getAction().equals("disembark"),"normal disembark keeps empty-cockpit recovery through late body animation"));
            if(ticks==scheduled(666)+BtAnimationTimings.DISEMBARK_TICKS+2)server((p,t)->check(p.getVehicle()==null&&t.getDisembarkTicks()==0,"normal disembark releases pilot after approved clip duration"));
            if(ticks==scheduled(666)+BtAnimationTimings.DISEMBARK_TICKS+4)server((p,t)->{p.connection.teleport(t.getX(),t.getY(),t.getZ()+4,180,0);t.interact(p,net.minecraft.world.InteractionHand.MAIN_HAND);check(p.getVehicle()==t&&t.getAction().equals("embark"),"normal interaction can embark again after completed exit");});
            if(ticks==scheduled(685))server((p,t)->{check(t.meleeStrike(),"standing pilot starts approved elbow melee action");check(t.getAction().equals("melee"),"melee action owns body animation state");});
            if(ticks==scheduled(685)+BtAnimationTimings.MELEE_HIT_TICKS+3){shot("08b_body_elbow_melee.png");server((p,t)->check(t.getAction().equals("melee"),"body melee continues through its impact event"));}
            if(ticks==scheduled(685)+BtAnimationTimings.MELEE_TICKS+2)server((p,t)->check(!t.getAction().equals("melee"),"body melee exits only after its approved duration"));
            if(ticks==scheduled(190)+74)System.setProperty("bt.qaPitch","20");
            if(ticks==scheduled(190)+89){shot("04e_aim_pitch_down_20.png");server((p,t)->check(p.getXRot()>19,"positive MC pilot pitch reaches downward aim case"));}
            if(ticks==scheduled(190)+98)System.setProperty("bt.qaPitch","-20");
            if(ticks==scheduled(190)+113){shot("04f_aim_pitch_up_20.png");server((p,t)->check(p.getXRot()<-19,"negative MC pilot pitch reaches upward aim case"));}
            if(ticks==scheduled(190)+120)System.setProperty("bt.qaPitch","0");
            if(ticks>1)playbackLog();
            if(ticks==scheduled(540)-2)shot("07b_crouch_before.png");
            if(ticks==scheduled(540)+BtAnimationTimings.CROUCH_DOWN_TICKS/2)shot("07c_crouch_mid_down.png");
            if(ticks==scheduled(540)+BtAnimationTimings.CROUCH_DOWN_TICKS+2)shot("07d_crouch_full_down.png");
            if(ticks==scheduled(550)+BtAnimationTimings.CROUCH_UP_TICKS/2)shot("07e_crouch_mid_up.png");
            if(ticks==scheduled(550)+BtAnimationTimings.CROUCH_UP_TICKS+2)shot("07f_crouch_full_up.png");
            if(ticks==scheduled(870)){com.caf.btmesh.NativeRenderProfiler.write(OUT.resolve("render_profile.json"),mc.getFps());Files.writeString(OUT.resolve("complete.txt"),"Actual isolated NeoForge client BB cube model/controls candidate with approved BB timings; checks, hashes and screenshots recorded. Screenshots do not prove audible verification or visual acceptance.");mc.stop();}
        }catch(Exception e){throw new RuntimeException(e);}
    }
    private static void shotFrame(int tick)throws Exception{Path dir=OUT.resolve("frames");Files.createDirectories(dir);var mc=Minecraft.getInstance();String name=String.format(java.util.Locale.ROOT,"frame_%06d.png",tick-40);Map<String,Object> row=playbackContext();row.put("frame_filename",name);row.put("capture_submitted_unix_ms",System.currentTimeMillis());Files.writeString(OUT.resolve("frame_submissions.jsonl"),new com.google.gson.Gson().toJson(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);Screenshot.grab(dir.toFile(),name,mc.getMainRenderTarget(),message->{});}
    private static void recordResources()throws Exception{
        var resources=Minecraft.getInstance().getResourceManager();var hashes=new java.util.LinkedHashMap<String,Object>();
        for(String name:java.util.List.of("models/native/body.glb","models/native/fp.glb","models/native/cube-provenance.json","motion/body_bb.bin","motion/bb_action_events.json","motion/bb_locomotion.json","motion/body.json","motion/fp.json","textures/entity/bt7274.png","textures/entity/bt7274_glowmask.png","sounds.json")){
            var resource=resources.getResource(BT7274Mod.id(name)).orElseThrow();byte[] bytes;try(var in=resource.open()){bytes=in.readAllBytes();}
            hashes.put(name,java.util.Map.of("sha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)),"bytes",bytes.length,"source_pack",resource.sourcePackId()));
        }
        Files.writeString(OUT.resolve("loaded_resource_hashes.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(hashes));
    }
}
