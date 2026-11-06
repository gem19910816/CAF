package com.caf.bt7274.client;

import com.caf.bt7274.BT7274Mod;
import com.caf.bt7274.TitanEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** MC cockpit targeting: only server-confirmed locks get target brackets.
 * Projection uses the exact current world view, including ADS, camera rotation
 * and GUI scale. This is a Minecraft adaptation, not a copy of native RUI. */
@EventBusSubscriber(modid=BT7274Mod.ID,value=Dist.CLIENT)
public final class TitanTargetHud {
    private static final Matrix4f viewProjection=new Matrix4f();
    private static Vec3 camera=Vec3.ZERO;
    private static Object capturedLevel;
    private static long capturedTick=Long.MIN_VALUE;
    private static float partial;
    private TitanTargetHud(){}
    @SubscribeEvent public static void capture(RenderLevelStageEvent event){
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_LEVEL)return;
        var mc=Minecraft.getInstance();
        capturedLevel=mc.level;
        if(mc.level==null||mc.player==null||!(mc.player.getVehicle() instanceof TitanEntity)){capturedTick=Long.MIN_VALUE;return;}
        viewProjection.set(event.getProjectionMatrix()).mul(event.getModelViewMatrix());
        camera=event.getCamera().getPosition();
        partial=event.getPartialTick().getGameTimeDeltaPartialTick(false);
        capturedTick=mc.level.getGameTime();
    }
    public static void draw(GuiGraphics graphics,TitanEntity titan,int width,int height){
        var mc=Minecraft.getInstance();
        if(mc.level==null||capturedLevel!=mc.level||Math.abs(mc.level.getGameTime()-capturedTick)>1)return;
        if(!mc.options.getCameraType().isFirstPerson())return;
        var locks=titan.getRocketTargetLocks();
        for(var entry:locks.entrySet()){
            var entity=mc.level.getEntity(entry.getKey());
            if(!(entity instanceof LivingEntity target)||!target.isAlive()||target.isInvisible()||!titan.hasLineOfSight(target))continue;
            Vec3 position=target.getPosition(partial);
            AABB box=target.getBoundingBox().move(position.subtract(target.position())).inflate(.15);
            int[] bounds=project(box,width,height);
            if(bounds==null)continue;
            int left=bounds[0],top=bounds[1],right=bounds[2],bottom=bounds[3],color=0xFFFFBD61;
            int arm=Math.min(12,Math.max(4,Math.min(right-left,bottom-top)/3));
            bracket(graphics,left,top,right,bottom,arm,color);
            String text="LOCK x"+entry.getValue();
            graphics.drawString(mc.font,text,Mth.clamp((left+right-mc.font.width(text))/2,6,Math.max(6,width-mc.font.width(text)-6)),Math.max(8,top-11),color,false);
        }
        if(TitanClient.ROCKET.isDown()&&titan.getRocketCooldownTicks()==0){
            String text=locks.isEmpty()?"ACQUIRING TARGET":"RELEASE TO FIRE";
            graphics.drawCenteredString(mc.font,text,width/2,height/2+31,locks.isEmpty()?0xFF8BDDE2:0xFFFFBD61);
        }
    }
    private static int[] project(AABB box,int width,int height){
        float minX=Float.POSITIVE_INFINITY,minY=Float.POSITIVE_INFINITY,maxX=Float.NEGATIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY;
        for(int corner=0;corner<8;corner++){
            double x=(corner&1)==0?box.minX:box.maxX,y=(corner&2)==0?box.minY:box.maxY,z=(corner&4)==0?box.minZ:box.maxZ;
            Vector4f clip=new Vector4f((float)(x-camera.x),(float)(y-camera.y),(float)(z-camera.z),1);
            viewProjection.transform(clip);
            // A box intersecting the near plane is omitted: dividing its rear
            // corners would mirror an enormous lock rectangle over the screen.
            if(!Float.isFinite(clip.w)||clip.w<=.03F)return null;
            float screenX=(clip.x/clip.w*.5F+.5F)*width,screenY=(.5F-clip.y/clip.w*.5F)*height;
            if(!Float.isFinite(screenX)||!Float.isFinite(screenY))return null;
            minX=Math.min(minX,screenX);maxX=Math.max(maxX,screenX);minY=Math.min(minY,screenY);maxY=Math.max(maxY,screenY);
        }
        if(maxX<6||minX>width-6||maxY<8||minY>height-14)return null;
        int left=Mth.clamp((int)Math.floor(minX),6,width-7),right=Mth.clamp((int)Math.ceil(maxX),7,width-6);
        int top=Mth.clamp((int)Math.floor(minY),8,height-15),bottom=Mth.clamp((int)Math.ceil(maxY),9,height-14);
        return right-left<4||bottom-top<4?null:new int[]{left,top,right,bottom};
    }
    private static void bracket(GuiGraphics g,int l,int t,int r,int b,int arm,int color){
        g.fill(l,t,l+arm,t+1,color);g.fill(l,t,l+1,t+arm,color);g.fill(r-arm,t,r+1,t+1,color);g.fill(r,t,r+1,t+arm,color);
        g.fill(l,b,l+arm,b+1,color);g.fill(l,b-arm,l+1,b+1,color);g.fill(r-arm,b,r+1,b+1,color);g.fill(r,b-arm,r+1,b+1,color);
    }
}
