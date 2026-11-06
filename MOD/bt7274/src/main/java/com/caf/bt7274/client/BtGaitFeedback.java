package com.caf.bt7274.client;

import com.caf.bt7274.TitanEntity;
import com.caf.btmesh.BtGaitClock;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Sole simulation owner for body, camera/bob and audible gait feedback. */
@EventBusSubscriber(modid="bt7274",value=Dist.CLIENT)
public final class BtGaitFeedback {
    public record Sample(int clip,double cycle,double stepPhase,float weight,float impact,
                         boolean left,boolean grounded,boolean moving,long generation){}
    private static final Map<TitanEntity,BtGaitClock> CLOCKS=new WeakHashMap<>();
    private static Object level;
    private BtGaitFeedback(){}
    @SubscribeEvent(priority=EventPriority.HIGHEST)public static void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();if(mc.level!=level){CLOCKS.clear();level=mc.level;}
        if(mc.level==null||mc.isPaused())return;
        Set<TitanEntity> seen=new HashSet<>();
        for(var e:mc.level.entitiesForRendering())if(e instanceof TitanEntity t){
            seen.add(t);int id=TitanRenderer.bodyAnimationId(t);boolean moving=TitanRenderer.bodyMoving(t);
            CLOCKS.computeIfAbsent(t,k->new BtGaitClock()).advance(mc.level.getGameTime(),t.getX(),t.getZ(),id,
                moving,contact(t),id==1||id==22,BtFootstepCues.stride(id),
                BtFootstepCues.duration(id),t.getGroundSpeed(),BtFootstepCues.markers(id),BtFootstepCues.left(id));
        }
        CLOCKS.keySet().removeIf(t->t.isRemoved()||!seen.contains(t));
    }
    private static boolean contact(TitanEntity t){
        if(!t.hasGroundContact()||!t.isAlive()||t.noPhysics||t.getDeltaMovement().y>.1)return false;
        var box=t.getBoundingBox();var down=new Vec3(0,-.015,0);
        // GROUND_CONTACT has visual grace; an audible impact needs current hull
        // support, not that grace or an unreliable interpolated onGround flag.
        return Entity.collideBoundingBox(t,down,box,t.level(),t.level().getEntityCollisions(t,box.expandTowards(down))).y>-.014999;
    }
    /** Pure read; safe to call repeatedly from camera, hand, body and audio renders. */
    public static Sample frame(TitanEntity titan,float partial){
        var clock=CLOCKS.get(titan);if(clock==null)return new Sample(0,0,0,0,0,false,false,false,0);
        var s=clock.frame(partial);return new Sample(s.clip(),s.cycle(),s.stepPhase(),s.weight(),s.impact(),s.left(),s.grounded(),s.moving(),s.generation());
    }
    public static float bodyCycle(TitanEntity titan,float partial){var c=CLOCKS.get(titan);return c==null?0:(float)c.bodyCycle(partial);}
}
