package com.caf.btmesh;

import java.nio.file.*;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Isolated demonstration renderer, driven by tick+partialTick; no gameplay state changed. */
@EventBusSubscriber(modid="bt7274",value=Dist.CLIENT)
public final class ProbeClient {
    private static SkinnedMeshRenderer renderer;private static boolean failed;
    public static volatile String animation=System.getProperty("btmesh.animation","");
    public static volatile long startTick=-1;
    @SubscribeEvent public static void render(RenderLevelStageEvent e){
        if(!Boolean.getBoolean("btmesh.qa")||e.getStage()!=RenderLevelStageEvent.Stage.AFTER_ENTITIES)return;var mc=Minecraft.getInstance();if(mc.level==null)return;
        if(renderer==null&&!failed){
            try{
                String model=System.getProperty("btmesh.model","F:/自制武器源文件/08_BT7274/research/native_bt/converted/titan_buddy/native_animated.glb");
                renderer=new SkinnedMeshRenderer(GltfSkin.load(Files.readAllBytes(Path.of(model))));
                if(animation.isEmpty()&&!renderer.model.clips.isEmpty())animation=renderer.model.clips.keySet().iterator().next();
                Files.writeString(Path.of("F:/自制武器源文件/08_BT7274/qa/mesh_probe_loaded.txt"),"nodes="+renderer.model.nodes.length+" primitives="+renderer.model.primitives.size()+" clips="+renderer.model.clips.keySet());
            }catch(Exception error){failed=true;throw new RuntimeException("Native mesh probe load failed",error);}
        }
        if(renderer==null)return;if(startTick<0)startTick=mc.level.getGameTime();
        double time=System.getProperty("btmesh.time")==null?(mc.level.getGameTime()-startTick+e.getPartialTick().getGameTimeDeltaPartialTick(false))/20.0:Double.parseDouble(System.getProperty("btmesh.time"));
        var cam=e.getCamera().getPosition();PoseStack stack=e.getPoseStack();stack.pushPose();
        double x=Double.parseDouble(System.getProperty("btmesh.x","0.5")),y=Double.parseDouble(System.getProperty("btmesh.y","80")),z=Double.parseDouble(System.getProperty("btmesh.z","0.5"));
        stack.translate(x-cam.x,y-cam.y,z-cam.z);
        float scale=Float.parseFloat(System.getProperty("btmesh.scale","1"));stack.scale(scale,scale,scale);
        var buffers=mc.renderBuffers().bufferSource();int light=LevelRenderer.getLightColor(mc.level,BlockPos.containing(x,y+3,z));
        renderer.render(stack,buffers,light,animation,time,true);stack.popPose();buffers.endBatch();
    }
}
