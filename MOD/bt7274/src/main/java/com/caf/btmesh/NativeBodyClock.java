package com.caf.btmesh;

import com.caf.bt7274.TitanEntity;
import java.util.*;

/** Shared per-entity phase for render and original footstep/servo event scheduling. */
public final class NativeBodyClock {
    private static final Map<TitanEntity,NativeLocomotionClock> CLOCKS=new WeakHashMap<>();
    public static NativeLocomotionClock.Sample sample(TitanEntity titan,NativeMotionPlayer player){
        String sequence=sequence(titan);if(!player.hasSequence(sequence))sequence=titan.getLocomotion()==2?"bt_combat_run_forward":titan.getLocomotion()==1?"bt_combat_walk_forward":"bt_combat_idle";double stride=player.strideMetres(sequence);
        return CLOCKS.computeIfAbsent(titan,t->new NativeLocomotionClock()).advance(titan.level().getGameTime(),titan.getX(),titan.getZ(),sequence,stride);
    }
    public static String sequence(TitanEntity titan){
        int motion=titan.getLocomotion();
        if(titan.isDoomed())return motion==1||motion==2?"at_doomed_walk_F":"at_doomed_idle";
        if(motion==3)return "at_Crouch_Aim";
        if(motion==2)return !titan.isAiming()&&titan.getReloadTicks()==0&&!titan.isVortexActive()?"bt_combat_sprint_forward_noaim":"bt_combat_run_forward";
        return motion==1?"bt_combat_walk_forward":"bt_combat_idle";
    }
}
