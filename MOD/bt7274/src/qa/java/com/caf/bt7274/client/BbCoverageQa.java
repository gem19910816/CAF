package com.caf.bt7274.client;

import com.caf.bt7274.*;
import com.caf.btmesh.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Isolated supplementary coverage through real server state and real render poses only. */
@EventBusSubscriber(modid="bt7274",value=Dist.CLIENT)
public final class BbCoverageQa {
    private static final Path OUT=Path.of(System.getProperty("bt.qaOut","F:/自制武器源文件/08_BT7274/previews/bb27_coverage_extra"));
    private static final com.google.gson.Gson JSON=new com.google.gson.Gson();
    private static int ticks,assertions;
    private static UUID titanId;
    private static net.minecraft.world.entity.decoration.ArmorStand camera;
    private static final Set<Integer> seen=new TreeSet<>();
    private static java.lang.reflect.Field rendererField;
    private static void input(float forward){System.setProperty("bt.qaButtons","0");System.setProperty("bt.qaForward",Float.toString(forward));System.setProperty("bt.qaStrafe","0");}
    private static void append(String file,Object row){try{Files.writeString(OUT.resolve(file),JSON.toJson(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception e){throw new RuntimeException(e);}}
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);assertions++;append("assertions.jsonl",Map.of("client_tick",ticks,"pass",text));}
    private static TitanEntity titan(){var mc=Minecraft.getInstance();if(mc.level==null)return null;for(var e:mc.level.entitiesForRendering())if(e instanceof TitanEntity t&&t.getUUID().equals(titanId))return t;return null;}
    private static void server(java.util.function.BiConsumer<net.minecraft.server.level.ServerPlayer,TitanEntity> action){var mc=Minecraft.getInstance();var id=mc.player.getUUID();var s=mc.getSingleplayerServer();s.execute(()->{var p=s.getPlayerList().getPlayer(id);var e=titanId==null?null:p.serverLevel().getEntity(titanId);action.accept(p,e instanceof TitanEntity t?t:null);});}
    private static int now(){int t=ticks;if(t>=170)t=Math.max(169,t-30);if(t>=240)t=Math.max(239,t-15);if(t>=300)t=Math.max(299,t-15);if(t>=360)t=Math.max(359,t-15);return t;}
    private static int phaseStart(){if(now()>=640)return 640;if(now()>=490)return 490;if(now()>=455)return 455;if(now()>=425)return 425;if(now()>=360)return 360;if(now()>=300)return 300;if(now()>=240)return 240;if(now()>=180)return 180;if(now()>=61)return 61;return 1;}
    private static int phaseLength(){return switch(phaseStart()){case 640->60;case 490->135;case 455->28;case 425->17;case 360->52;case 300->48;case 240->49;case 180->50;case 61->102;default->60;};}
    private static String phase(){return switch(phaseStart()){case 640->"death";case 490->"doomed_walk";case 455->"doomed_enter";case 425->"hurt";case 360->"dash_right";case 300->"dash_left";case 240->"dash_back";case 180->"dash_forward";case 61->"land";default->"skyfall";};}
    private static float cameraAngle(){double progress=(now()-phaseStart())/(double)phaseLength();return progress>=.4&&progress<.8?90:0;}
    @SubscribeEvent public static void camera(RenderFrameEvent.Pre event){
        if(!Boolean.getBoolean("bt.bbCoverageQa"))return;var mc=Minecraft.getInstance();var t=titan();if(t==null||mc.player==null)return;
        if(camera==null||camera.level()!=mc.level)camera=new net.minecraft.world.entity.decoration.ArmorStand(mc.level,0,0,0);
        boolean mounted=mc.player.getVehicle()==t;float yaw=t.getYRot()+cameraAngle();double rad=Math.toRadians(yaw),distance=mounted?7.5:11.5;var at=t.getPosition(event.getPartialTick().getGameTimeDeltaPartialTick(false));
        camera.moveTo(at.x-Math.sin(rad)*distance,at.y+3-camera.getEyeHeight(),at.z+Math.cos(rad)*distance,yaw+(mounted?0:180),0);
        camera.setYHeadRot(yaw+(mounted?0:180));camera.yHeadRotO=yaw+(mounted?0:180);camera.yRotO=yaw+(mounted?0:180);camera.xRotO=0;camera.xo=camera.getX();camera.yo=camera.getY();camera.zo=camera.getZ();
        if(mc.getCameraEntity()!=camera)mc.setCameraEntity(camera);mc.options.setCameraType(mounted?CameraType.THIRD_PERSON_FRONT:CameraType.FIRST_PERSON);
    }
    public static Map<String,Object> context(){
        var row=new LinkedHashMap<String,Object>();var mc=Minecraft.getInstance();var cam=mc.gameRenderer.getMainCamera();row.put("actual_camera_position",cam.getPosition().toString());row.put("actual_camera_yaw",cam.getYRot());row.put("actual_camera_pitch",cam.getXRot());row.put("client_tick",ticks);row.put("wall_time_unix_ms",System.currentTimeMillis());row.put("phase",phase());row.put("camera_angle",cameraAngle());
        var t=titan();if(mc.level!=null)row.put("game_tick",mc.level.getGameTime());if(t==null)return row;
        row.put("titan_position",List.of(t.getX(),t.getY(),t.getZ()));row.put("body_action",t.getAction());row.put("body_start_tick",t.getActionStartTick());row.put("body_elapsed_seconds",(mc.level.getGameTime()-t.getActionStartTick())/20.0);row.put("weapon_action",t.getWeaponAction());row.put("shield",t.getShield());row.put("hull",t.getTitanHull());row.put("energy",t.getEnergy());row.put("doomed",t.isDoomed());row.put("alive",t.isAlive());row.put("ground_speed",t.getGroundSpeed());row.put("deployment_remaining",t.getDeploymentTicks());row.put("on_ground",t.onGround());row.put("mounted",mc.player.getVehicle()==t);
        var er=mc.getEntityRenderDispatcher().getRenderer(t);if(er instanceof TitanRenderer r){var p=r.playback(t);if(p!=null){row.put("rendered_clip",p.clipId());row.put("rendered_seconds",p.seconds());row.put("render_transition",p.transition());row.put("render_layers",p.layers());seen.add(p.clipId());}}
        return row;
    }
    /** Read final existing skin matrices; never calls a sampler or changes the pose. */
    private static Object actualPartBounds(TitanRenderer renderer)throws Exception{
        if(rendererField==null){rendererField=TitanRenderer.class.getDeclaredField("renderer");rendererField.setAccessible(true);}var mesh=(SkinnedMeshRenderer)rendererField.get(renderer);
        String[] names={"cube_foot_left","cube_foot_right","cube_hand_left","cube_hand_right","cube_weapon"};int[] part=new int[mesh.model.nodes.length];Arrays.fill(part,-1);
        for(int n=0;n<part.length;n++)for(int a=n;a>=0&&part[n]<0;a=mesh.model.nodes[a].parent)for(int k=0;k<names.length;k++)if(mesh.model.nodes[a].name.equals(names[k])){part[n]=k;break;}
        double[][] bounds=new double[names.length][6];int[] counts=new int[names.length];for(var b:bounds){Arrays.fill(b,0,3,Double.POSITIVE_INFINITY);Arrays.fill(b,3,6,Double.NEGATIVE_INFINITY);}
        for(var p:mesh.model.primitives){if(p.skin()<0||p.joints()==null)continue;var skin=mesh.model.skins[p.skin()];for(int v=0;v<p.positions().length/3;v++){
            int owner=part[skin.joints()[p.joints()[v*4]]];if(owner<0)continue;double x=p.positions()[v*3],y=p.positions()[v*3+1],z=p.positions()[v*3+2],px=0,py=0,pz=0;
            for(int j=0;j<4;j++){float w=p.weights()[v*4+j];if(w==0)continue;var m=mesh.pose.joints[p.skin()][p.joints()[v*4+j]];px+=(m.m00()*x+m.m10()*y+m.m20()*z+m.m30())*w;py+=(m.m01()*x+m.m11()*y+m.m21()*z+m.m31())*w;pz+=(m.m02()*x+m.m12()*y+m.m22()*z+m.m32())*w;}
            if(!Double.isFinite(px+py+pz))throw new AssertionError("nonfinite actual rendered part "+names[owner]);var b=bounds[owner];b[0]=Math.min(b[0],px);b[1]=Math.min(b[1],py);b[2]=Math.min(b[2],pz);b[3]=Math.max(b[3],px);b[4]=Math.max(b[4],py);b[5]=Math.max(b[5],pz);counts[owner]++;
        }}var result=new LinkedHashMap<String,Object>();for(int k=0;k<names.length;k++){if(counts[k]==0)throw new AssertionError("missing actual rendered part "+names[k]);double extent=0;for(int i=0;i<3;i++)extent+=bounds[k][i+3]-bounds[k][i];if(extent<.01)throw new AssertionError("collapsed actual rendered part "+names[k]);result.put(names[k],Map.of("vertices",counts[k],"bounds_m",bounds[k]));}return result;
    }
    private static void shot(String name){var mc=Minecraft.getInstance();Screenshot.grab(OUT.toFile(),name,mc.getMainRenderTarget(),ignored->{});}
    private static void dash(float strafe,float forward,String expected){server((p,t)->{t.acceptInput(p,strafe,forward,0);float before=t.getEnergy();check(t.dash(),expected+" accepts real driver using natural recovered energy="+before);check(t.getAction().equals(expected),expected+" owns body action");});}
    @SubscribeEvent public static void before(ClientTickEvent.Pre event){if(!Boolean.getBoolean("bt.bbCoverageQa"))return;var mc=Minecraft.getInstance();if(mc.player==null)return;for(var key:List.of(mc.options.keyShift,mc.options.keyJump,mc.options.keyUp,mc.options.keyDown,mc.options.keyLeft,mc.options.keyRight,mc.options.keyAttack,mc.options.keyUse))key.setDown(false);mc.player.setShiftKeyDown(false);mc.player.setYRot(0);mc.player.setXRot(0);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!Boolean.getBoolean("bt.bbCoverageQa"))return;var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null)return;ticks++;
        try{
            if(now()==1){Files.createDirectories(OUT);for(String f:List.of("assertions.jsonl","actual_playback.jsonl","frame_submissions.jsonl","actual_sound_events.jsonl","complete.txt"))Files.deleteIfExists(OUT.resolve(f));input(0);System.setProperty("bt.qaPitch","0");mc.options.pauseOnLostFocus=false;mc.options.hideGui=true;mc.options.fov().set(55);server((p,t)->{p.stopRiding();var stale=new ArrayList<net.minecraft.world.entity.Entity>();for(var e:p.serverLevel().getAllEntities())if(e instanceof TitanEntity||e instanceof TitanRocket||e instanceof net.minecraft.world.entity.monster.Monster&&e.distanceToSqr(p)<10000)stale.add(e);stale.forEach(net.minecraft.world.entity.Entity::discard);for(int x=-16;x<=16;x++)for(int z=-24;z<=160;z++)p.serverLevel().setBlock(new BlockPos(x,79,z),net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState(),2);p.setGameMode(GameType.CREATIVE);p.connection.teleport(8.5,80,9.5,0,0);var n=BT7274Mod.TITAN.get().create(p.serverLevel());n.setOwner(p.getUUID());n.setPos(.5,80,.5);n.setNoAi(true);n.setMode(0);p.serverLevel().addFreshEntity(n);titanId=n.getUUID();n.beginDeployment(new BlockPos(0,80,0));check(n.isDeploying()&&n.getY()>140,"real deployment begins above floor");});}
            if(now()==170)server((p,t)->check(t!=null&&!t.isDeploying()&&t.onGround(),"full skyfall and landing recovery completed"));
            if(now()==175)server((p,t)->{p.connection.teleport(t.getX(),t.getY(),t.getZ()+4,0,0);check(p.startRiding(t),"normal owner rides ready Titan for direction coverage");});
            if(now()==180)dash(0,1,"dash_forward");if(now()==240)dash(0,-1,"dash_back");if(now()==300)dash(1,0,"dash_left");if(now()==360)dash(-1,0,"dash_right");
            if(now()==235||now()==295||now()==355||now()==420)server((p,t)->check(!t.getAction().startsWith("dash_"),"dash complete and idle recovery reached before next event"));
            if(now()==425)server((p,t)->{float hp=t.getTitanHull();check(t.hurt(t.damageSources().generic(),1)&&t.getTitanHull()==hp&&t.getAction().equals("hurt"),"ordinary shield damage triggers actual hurt clip without changing hull");});
            if(now()==449)server((p,t)->check(!t.getAction().equals("hurt"),"full hurt clip returns to idle"));
            if(now()==455)server((p,t)->{check(t.hurt(t.damageSources().generic(),t.getShield()+t.getTitanHull()),"ordinary exact lethal damage accepted");check(t.isDoomed()&&t.isAlive()&&t.getTitanHull()==2500,"normal damage enters real doomed phase");});
            if(now()==490)input(1);if(now()==625)input(0);
            if(now()==510||now()==610)server((p,t)->check(t.isDoomed()&&p.getVehicle()==t&&t.getGroundSpeed()>.025,"real doomed pilot locomotion is moving"));
            if(now()==635)server((p,t)->check(t.onGround()&&Math.abs(t.getY()-80)<.05,"doomed walk stays on actual runway before grounded death"));
            if(now()==640)server((p,t)->{check(t.hurt(t.damageSources().generic(),t.getShield()+t.getTitanHull()+1),"normal doomed lethal damage accepted");check(!t.isAlive()&&t.getAction().equals("death"),"actual death owns body clock and releases pilot");});
            if(now()>2&&now()<702){var row=context();var t=titan();if(t!=null&&mc.getEntityRenderDispatcher().getRenderer(t) instanceof TitanRenderer r&&r.playback(t)!=null)row.put("actual_part_bounds",actualPartBounds(r));append("actual_playback.jsonl",row);if(Boolean.getBoolean("bt.qaRecord")){Files.createDirectories(OUT.resolve("frames"));String name=String.format(Locale.ROOT,"frame_%06d.png",ticks);row.put("frame_filename",name);row.put("capture_submitted_unix_ms",System.currentTimeMillis());append("frame_submissions.jsonl",row);Screenshot.grab(OUT.resolve("frames").toFile(),name,mc.getMainRenderTarget(),ignored->{});}}
            int age=now()-phaseStart(),length=phaseLength();if(age==8||age==(int)(length*.6)||age==length-3)shot(String.format(Locale.ROOT,"%03d_%s_%s.png",ticks,phase(),cameraAngle()==0?"front":"side"));
            if(now()==720){check(seen.containsAll(Set.of(12,13,14,15,16,17,18,23)),"actual full-body renderer observed all supplemental clips "+seen);Files.writeString(OUT.resolve("complete.txt"),"Actual supplementary server-driven coverage finished. Assertions="+assertions+"; observed="+seen+". Complete source clocks and visual acceptance require frame/log review; bounds alone do not prove no occlusion.");mc.stop();}
        }catch(Exception e){throw new RuntimeException(e);}
    }
}
