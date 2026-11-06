package com.caf.bt7274.client;

import com.caf.bt7274.BT7274Mod;
import com.caf.bt7274.BtAnimationTimings;
import com.caf.bt7274.TitanEntity;
import com.caf.btmesh.GltfSkin;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Vector3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/** Attachment-space effects, driven by synchronized shot/ability clocks. No geometry or animation replacement. */
@EventBusSubscriber(modid=BT7274Mod.ID,value=Dist.CLIENT)
public final class TitanEffects {
    static final ResourceLocation WHITE=BT7274Mod.id("textures/effect/white.png");
    private static final ResourceLocation FLASH=BT7274Mod.id("textures/effect/muzzle.png");
    private static final Map<TitanEntity,State> STATES=new WeakHashMap<>();
    private static final Map<String,Long> COUNTERS=new LinkedHashMap<>();
    static {for(String name:new String[]{"muzzle_world","muzzle_fp","shield_world","shield_fp","rocket_render","rocket_client_tick","landing_particles"})COUNTERS.put(name,0L);}
    private static final class State {int shots;long flash=-100;long landing=-1;int landingElapsed=-1;boolean initialized;}
    private TitanEffects(){}
    public static Map<String,Long> diagnostics(){return Map.copyOf(COUNTERS);}
    static void count(String name){COUNTERS.put(name,COUNTERS.getOrDefault(name,0L)+1);}
    private static State observe(TitanEntity titan){
        State state=STATES.computeIfAbsent(titan,t->new State());
        if(state.initialized&&state.shots!=titan.getShotCount())state.flash=titan.level().getGameTime();
        state.initialized=true;state.shots=titan.getShotCount();return state;
    }
    private static boolean flashing(TitanEntity titan,float partial){return titan.level().getGameTime()+partial-observe(titan).flash<1.7;}
    private static int node(GltfSkin model,String name){for(int i=0;i<model.nodes.length;i++)if(model.nodes[i].name.equals(name))return i;return -1;}
    public static void body(TitanEntity titan,PoseStack stack,MultiBufferSource buffers,GltfSkin model,GltfSkin.Pose pose,float partial){
        if(!flashing(titan,partial))return;int muzzle=node(model,"cube_barrel");if(muzzle<0)return;
        stack.pushPose();stack.mulPose(pose.world[muzzle]);
        // Geometry audit: barrel-local bore tip z=-11.385, not the barrel-group pivot.
        // Cube units are 0.0540634 metres, so the flash starts just beyond that tip.
        stack.translate(0,-3,-11.5);stack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180));
        flash(stack,buffers,5.4F,17F,titan.getShotCount());stack.popPose();count("muzzle_world");
    }
    public static void firstPerson(TitanEntity titan,PoseStack stack,MultiBufferSource buffers,GltfSkin model,GltfSkin.Pose pose,float partial){
        int muzzle=node(model,"muzzle_flash");
        if(muzzle>=0&&flashing(titan,partial)){
            // Express the evaluated attachment in view metres before drawing.
            // This avoids inheriting a Source-inch wrapper as an effect size.
            Matrix4f attachment=new Matrix4f(stack.last().pose()).mul(pose.world[muzzle]);
            Vector3f center=attachment.getTranslation(new Vector3f());
            Vector3f bore=attachment.transformDirection(new Vector3f(1,0,0)).normalize();
            stack.pushPose();
            stack.last().pose().identity().translate(center).rotate(new Quaternionf().rotationTo(new Vector3f(0,0,1),bore));
            flash(stack,buffers,.38F,.95F,titan.getShotCount());stack.popPose();count("muzzle_fp");
        }
        if(titan.isVortexActive()){
            int hand=node(model,"l_hand_ik");if(hand<0||muzzle<0)return;
            Vector3f center=pose.world[hand].transformPosition(new Vector3f());
            Vector3f forward=pose.world[muzzle].transformDirection(new Vector3f(1,0,0)).normalize();
            // pose.world already includes SourceCubeFPToMetres. These are
            // world metres here, unlike the raw attachment's Source-inch TRS.
            center.fma(23*.0254F,forward);
            shield(stack,buffers,center,forward,37*.0254F,(titan.tickCount+partial)*.08F);count("shield_fp");
        }
    }
    public static void worldShield(TitanEntity titan,PoseStack stack,MultiBufferSource buffers,float partial){
        if(!titan.isVortexActive())return;
        Vec3 look=titan.getControllingPassenger()!=null?titan.getControllingPassenger().getLookAngle():titan.getLookAngle();
        Vec3 center=titan.getEyePosition().subtract(titan.position()).add(look.scale(2.8));
        shield(stack,buffers,new Vector3f((float)center.x,(float)center.y,(float)center.z),new Vector3f((float)look.x,(float)look.y,(float)look.z),1.65F,(titan.tickCount+partial)*.08F);count("shield_world");
    }
    private static void flash(PoseStack stack,MultiBufferSource buffers,float radius,float length,int shot){
        stack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees((shot*137)%360));
        // Eyes shader has no directional light multiplication. The custom state
        // also keeps the rear of each additive flash card visible to the pilot.
        VertexConsumer out=buffers.getBuffer(TitanEffectRenderTypes.additive(FLASH));
        // Three intersecting tapered textured cards remain visible from cockpit and external views.
        for(int i=0;i<3;i++){
            stack.pushPose();stack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(i*60));
            quad(out,stack,new Vector3f(-radius,0,0),new Vector3f(radius,0,0),new Vector3f(radius*.55F,0,length),new Vector3f(-radius*.55F,0,length),255,221,139,255);
            stack.popPose();
        }
        quad(out,stack,new Vector3f(-radius,-radius,.02F),new Vector3f(radius,-radius,.02F),new Vector3f(radius,radius,.02F),new Vector3f(-radius,radius,.02F),255,244,201,255);
    }
    private static void shield(PoseStack stack,MultiBufferSource buffers,Vector3f center,Vector3f normal,float radius,float time){
        Vector3f up=Math.abs(normal.y)>.95F?new Vector3f(1,0,0):new Vector3f(0,1,0);
        Vector3f right=new Vector3f(up).cross(normal).normalize();up.set(normal).cross(right).normalize();
        VertexConsumer out=buffers.getBuffer(RenderType.entityTranslucentEmissive(WHITE));
        for(int i=0;i<48;i++){
            float a=(float)(i*Math.PI*2/48),b=(float)((i+1)*Math.PI*2/48);
            Vector3f p=radial(center,right,up,radius,a),q=radial(center,right,up,radius,b);
            quad(out,stack,center,p,q,q,72,207,255,22);
            float pulse=.91F+.025F*(float)Math.sin(time*3+i*.6);
            quad(out,stack,radial(center,right,up,radius*pulse,a),p,q,radial(center,right,up,radius*pulse,b),91,225,255,195);
            // Rotating broken inner arcs communicate an active capture field without hiding targets.
            if((i+(int)(time*8))%12<7)quad(out,stack,radial(center,right,up,radius*.58F,a),radial(center,right,up,radius*.60F,a),radial(center,right,up,radius*.60F,b),radial(center,right,up,radius*.58F,b),178,244,255,128);
        }
    }
    private static Vector3f radial(Vector3f center,Vector3f right,Vector3f up,float radius,float angle){return new Vector3f(center).fma((float)Math.cos(angle)*radius,right).fma((float)Math.sin(angle)*radius,up);}
    static void quad(VertexConsumer out,PoseStack stack,Vector3f a,Vector3f b,Vector3f c,Vector3f d,int r,int g,int blue,int alpha){
        vertex(out,stack,a,0,1,r,g,blue,alpha);vertex(out,stack,b,1,1,r,g,blue,alpha);vertex(out,stack,c,1,0,r,g,blue,alpha);vertex(out,stack,d,0,0,r,g,blue,alpha);
    }
    private static void vertex(VertexConsumer out,PoseStack stack,Vector3f p,float u,float v,int r,int g,int b,int a){out.addVertex(stack.last().pose(),p.x,p.y,p.z).setColor(r,g,b,a).setUv(u,v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(15728880).setNormal(stack.last(),0,1,0);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null){STATES.clear();return;}
        for(var entity:mc.level.entitiesForRendering()){
          if(entity instanceof com.caf.bt7274.TitanRocket){count("rocket_client_tick");continue;}
          if(entity instanceof TitanEntity titan){
            State state=observe(titan);if(titan.distanceToSqr(mc.player)>128*128)continue;
            if(titan.getAction().equals("skyfall")&&titan.isDeploying()){
                for(int i=0;i<6;i++){double x=titan.getX()+(i%2==0?-.85:.85),z=titan.getZ();mc.level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE,x,titan.getY()+.5,z,0,-.16,0);}
            }
            if(!titan.getAction().equals("land")){state.landingElapsed=-1;continue;}
            int elapsed=(int)(mc.level.getGameTime()-titan.getActionStartTick());
            if(state.landing!=titan.getActionStartTick()){state.landing=titan.getActionStartTick();state.landingElapsed=elapsed-1;}
            int impact=BtAnimationTimings.LAND_IMPACT_TICKS;
            if(state.landingElapsed<impact&&elapsed>=impact&&elapsed<impact+3)landing(titan);
            if(elapsed>=impact&&elapsed<impact+23&&elapsed%2==0){
                for(int i=0;i<8;i++){double a=i*Math.PI/4+elapsed*.17,r=1.3+(elapsed-impact)*.12;mc.level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE,titan.getX()+Math.cos(a)*r,titan.getY()+.15,titan.getZ()+Math.sin(a)*r,Math.cos(a)*.05,.065,Math.sin(a)*.05);count("landing_particles");}
            }
            state.landingElapsed=elapsed;
          }
        }
    }
    private static void landing(TitanEntity titan){
        var level=titan.level();var random=titan.getRandom();var block=level.getBlockState(BlockPos.containing(titan.getX(),titan.getY()-.2,titan.getZ()));
        for(int ring=0;ring<3;ring++)for(int i=0;i<64;i++){
            double a=i*Math.PI*2/64+random.nextDouble()*.04,r=1.25+ring*.85,v=.15+ring*.11;
            double x=titan.getX()+Math.cos(a)*r,z=titan.getZ()+Math.sin(a)*r;
            level.addParticle(ParticleTypes.POOF,x,titan.getY()+.12,z,Math.cos(a)*v,.04+random.nextDouble()*.11,Math.sin(a)*v);count("landing_particles");
            if(i%3==0&&!block.isAir()){level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK,block),x,titan.getY()+.2,z,Math.cos(a)*v,.2+random.nextDouble()*.25,Math.sin(a)*v);count("landing_particles");}
        }
        for(int i=0;i<24;i++){double a=random.nextDouble()*Math.PI*2,r=random.nextDouble()*1.4;level.addParticle(ParticleTypes.LARGE_SMOKE,titan.getX()+Math.cos(a)*r,titan.getY()+.2,titan.getZ()+Math.sin(a)*r,Math.cos(a)*.12,.17+random.nextDouble()*.1,Math.sin(a)*.12);count("landing_particles");}
    }
}
