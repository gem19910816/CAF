package com.caf.bt7274.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.*;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.Direction;
import org.joml.*;

/**
 * Standard Minecraft skin boxes driven by supplied pilot joints. No source-game geometry.
 * All points are BT-local metres, X right/Y up/Z forward; caller owns BT-to-world transform.
 * Head point is the head centre. Chest is upper torso, pelvis lower torso.
 */
public final class BtBoardingPilotRenderer {
    private static final Set<Direction> ALL=EnumSet.allOf(Direction.class);
    private static final Map<String,ModelPart.Cube> CUBES=new HashMap<>();
    private BtBoardingPilotRenderer(){}

    public static void renderJoints(AbstractClientPlayer player,Map<String,Vector3f> joints,
                                    PoseStack stack,MultiBufferSource buffers,int light){
        if(player==null||joints==null||!valid(joints,"pelvis","chest","head"))return;
        VertexConsumer out=buffers.getBuffer(RenderType.entityCutoutNoCull(player.getSkin().texture()));
        Vector3f pelvis=joints.get("pelvis"),chest=joints.get("chest");
        Vector3f up=new Vector3f(chest).sub(pelvis);if(up.lengthSquared()<1e-8F)return;up.normalize();
        Vector3f right=valid(joints,"l_shoulder","r_shoulder")
            ?new Vector3f(joints.get("r_shoulder")).sub(joints.get("l_shoulder")):new Vector3f(1,0,0);
        Vector3f forward=new Vector3f(right).cross(up);
        if(forward.lengthSquared()<1e-8F)forward.set(0,0,1);else forward.normalize();
        // Torso preserves its standard 8x12 skin atlas, stretched only between source joints.
        segment(stack,out,light,chest,pelvis,forward,8,12,4,16,16,0,false);
        segment(stack,out,light,chest,pelvis,forward,8,12,4,16,32,.25F,false);
        head(stack,out,light,joints.get("head"),up,forward,0,0,0);
        head(stack,out,light,joints.get("head"),up,forward,32,0,.5F);
        boolean slim=player.getSkin().model()==PlayerSkin.Model.SLIM;int width=slim?3:4;
        limb(joints,"r_shoulder","r_elbow","r_wrist",stack,out,light,forward,width,40,16,40,32);
        limb(joints,"l_shoulder","l_elbow","l_wrist",stack,out,light,forward,width,32,48,48,48);
        limb(joints,"r_hip","r_knee","r_ankle",stack,out,light,forward,4,0,16,0,32);
        limb(joints,"l_hip","l_knee","l_ankle",stack,out,light,forward,4,16,48,0,48);
    }
    private static boolean valid(Map<String,Vector3f> points,String... keys){for(String key:keys){Vector3f p=points.get(key);if(p==null||!p.isFinite())return false;}return true;}
    public static void renderArms(AbstractClientPlayer player,Map<String,Vector3f> joints,PoseStack stack,MultiBufferSource buffers,int light){
        if(!valid(joints,"l_shoulder","l_elbow","l_wrist","r_shoulder","r_elbow","r_wrist"))return;
        VertexConsumer out=buffers.getBuffer(RenderType.entityCutoutNoCull(player.getSkin().texture()));
        int width=player.getSkin().model()==PlayerSkin.Model.SLIM?3:4;Vector3f forward=new Vector3f(0,0,1);
        limb(joints,"r_shoulder","r_elbow","r_wrist",stack,out,light,forward,width,40,16,40,32);
        limb(joints,"l_shoulder","l_elbow","l_wrist",stack,out,light,forward,width,32,48,48,48);
    }
    private static void limb(Map<String,Vector3f> points,String top,String mid,String end,
                             PoseStack stack,VertexConsumer out,int light,Vector3f forward,int width,
                             int u,int v,int overlayU,int overlayV){
        if(!valid(points,top,mid,end))return;
        // Upper/lower side rows retain their respective top/bottom six rows of the 12px skin.
        segment(stack,out,light,points.get(top),points.get(mid),forward,width,6,4,u,v,0,false);
        segment(stack,out,light,points.get(mid),points.get(end),forward,width,6,4,u,v+6,0,true);
        segment(stack,out,light,points.get(top),points.get(mid),forward,width,6,4,overlayU,overlayV,.25F,false);
        segment(stack,out,light,points.get(mid),points.get(end),forward,width,6,4,overlayU,overlayV+6,.25F,true);
    }
    private static void segment(PoseStack stack,VertexConsumer out,int light,Vector3f from,Vector3f to,
                                Vector3f forward,int width,int pixelsHigh,int depth,int u,int v,float grow,boolean lower){
        Vector3f down=new Vector3f(to).sub(from);float length=down.length();if(length<1e-5F)return;
        down.div(length);stack.pushPose();stack.translate(from.x,from.y,from.z);
        stack.mulPose(basis(down,forward));stack.scale(1,length/(pixelsHigh/16F),1);
        cube(width,pixelsHigh,depth,u,v,grow,false,lower).compile(stack.last(),out,light,OverlayTexture.NO_OVERLAY,-1);
        if(lower)endCap(width,pixelsHigh,depth,u,v-6,grow).compile(stack.last(),out,light,OverlayTexture.NO_OVERLAY,-1);
        stack.popPose();
    }
    private static void head(PoseStack stack,VertexConsumer out,int light,Vector3f center,Vector3f up,Vector3f forward,int u,int v,float grow){
        stack.pushPose();stack.translate(center.x,center.y,center.z);stack.mulPose(basis(new Vector3f(up).negate(),forward));
        cube(8,8,8,u,v,grow,true,false).compile(stack.last(),out,light,OverlayTexture.NO_OVERLAY,-1);stack.popPose();
    }
    private static Quaternionf basis(Vector3f down,Vector3f forward){
        // Native MC skin faces -Z and model Y points down. Keep front consistent through elbows.
        Vector3f z=new Vector3f(forward).negate();z.fma(-z.dot(down),down);
        if(z.lengthSquared()<1e-8F){z.set(0,0,1);z.fma(-z.dot(down),down);if(z.lengthSquared()<1e-8F)z.set(1,0,0);}
        z.normalize();Vector3f x=new Vector3f(down).cross(z).normalize();z.set(x).cross(down).normalize();
        return new Quaternionf().setFromNormalized(new Matrix3f().setColumn(0,x).setColumn(1,down).setColumn(2,z));
    }
    private static ModelPart.Cube cube(int width,int height,int depth,int u,int v,float grow,boolean centerY,boolean lower){
        String key=width+":"+height+":"+depth+":"+u+":"+v+":"+grow+":"+centerY+":"+lower;
        return CUBES.computeIfAbsent(key,k->{var faces=EnumSet.allOf(Direction.class);if(height==6&&!centerY){faces.remove(Direction.UP);if(lower)faces.remove(Direction.DOWN);}return new ModelPart.Cube(u,v,-width/2F,centerY?-height/2F:0,-depth/2F,width,height,depth,grow,grow,grow,false,64,64,faces);});
    }
    private static ModelPart.Cube endCap(int width,int height,int depth,int u,int v,float grow){String key="cap:"+width+":"+height+":"+depth+":"+u+":"+v+":"+grow;return CUBES.computeIfAbsent(key,k->new ModelPart.Cube(u,v,-width/2F,0,-depth/2F,width,height,depth,grow,grow,grow,false,64,64,EnumSet.of(Direction.UP)));}
}
