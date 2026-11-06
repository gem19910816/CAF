package com.caf.bt7274.client;

import com.caf.bt7274.TitanRocket;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;

/** Small authored box projectile with fins and luminous motor; intentionally independent of titan mesh. */
public final class TitanRocketRenderer extends EntityRenderer<TitanRocket> {
    public TitanRocketRenderer(EntityRendererProvider.Context context){super(context);shadowRadius=.1F;}
    @Override public ResourceLocation getTextureLocation(TitanRocket entity){return TitanEffects.WHITE;}
    @Override public void render(TitanRocket rocket,float yaw,float partial,PoseStack stack,MultiBufferSource buffers,int light){
        var motion=rocket.getDeltaMovement();
        stack.pushPose();
        if(motion.lengthSqr()>1.0e-5){
            stack.mulPose(Axis.YP.rotation((float)Math.atan2(motion.x,motion.z)));
            stack.mulPose(Axis.XP.rotation((float)-Math.atan2(motion.y,Math.sqrt(motion.x*motion.x+motion.z*motion.z))));
        }
        VertexConsumer body=buffers.getBuffer(RenderType.entityCutoutNoCull(TitanEffects.WHITE));
        box(body,stack,-.075F,-.075F,-.3F,.075F,.075F,.27F,100,112,104);
        box(body,stack,-.055F,-.055F,.27F,.055F,.055F,.39F,204,191,147);
        box(body,stack,-.21F,-.022F,-.29F,.21F,.022F,-.05F,60,69,66);
        box(body,stack,-.022F,-.21F,-.29F,.022F,.21F,-.05F,60,69,66);
        VertexConsumer glow=buffers.getBuffer(TitanEffectRenderTypes.additive(TitanEffects.WHITE));
        float length=.6F+.13F*(float)Math.sin((rocket.tickCount+partial)*4);
        for(int i=0;i<2;i++){
            stack.pushPose();stack.mulPose(Axis.ZP.rotationDegrees(i*90));
            TitanEffects.quad(glow,stack,new Vector3f(-.07F,0,-.3F),new Vector3f(.07F,0,-.3F),new Vector3f(.012F,0,-.3F-length),new Vector3f(-.012F,0,-.3F-length),255,177,66,255);
            stack.popPose();
        }
        stack.popPose();TitanEffects.count("rocket_render");super.render(rocket,yaw,partial,stack,buffers,light);
    }
    private static void box(VertexConsumer out,PoseStack stack,float x,float y,float z,float X,float Y,float Z,int r,int g,int b){
        var a=new Vector3f(x,y,z);var c=new Vector3f(X,Y,Z);
        TitanEffects.quad(out,stack,a,new Vector3f(X,y,z),new Vector3f(X,Y,z),new Vector3f(x,Y,z),r,g,b,255);
        TitanEffects.quad(out,stack,new Vector3f(x,y,Z),new Vector3f(x,Y,Z),c,new Vector3f(X,y,Z),r,g,b,255);
        TitanEffects.quad(out,stack,a,new Vector3f(x,Y,z),new Vector3f(x,Y,Z),new Vector3f(x,y,Z),r,g,b,255);
        TitanEffects.quad(out,stack,new Vector3f(X,y,z),new Vector3f(X,y,Z),c,new Vector3f(X,Y,z),r,g,b,255);
        TitanEffects.quad(out,stack,new Vector3f(x,Y,z),new Vector3f(X,Y,z),c,new Vector3f(x,Y,Z),r,g,b,255);
        TitanEffects.quad(out,stack,a,new Vector3f(x,y,Z),new Vector3f(X,y,Z),new Vector3f(X,y,z),r,g,b,255);
    }
}
