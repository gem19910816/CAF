package com.caf.bt7274;

/** Executable without game bootstrap: sustained hazards and follow hysteresis. */
public final class TitanMovementFeedbackAudit {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        TitanMovementFeedback policy=new TitanMovementFeedback();
        int fireDamage=0,fireFeedback=0;
        for(int tick=0;tick<200;tick++){
            if(policy.acceptEnvironmentalDamage(tick))fireDamage++;
            if(policy.playCombatFeedback(tick,true))fireFeedback++;
        }
        check(fireDamage==20,"200 fire-contact ticks must produce 20 damage applications, not 200");
        check(fireFeedback==0,"fire under the Titan must not repeatedly play combat impacts/flinches");
        check(policy.playCombatFeedback(200,false),"combat hit after burning must still have feedback");
        check(!policy.playCombatFeedback(201,false),"rapid hits must not stack cockpit impact audio");
        check(policy.playCombatFeedback(212,false),"later combat hits must retain feedback");
        check(TitanMovementFeedback.shouldFollow(true,false,64),"follow must begin beyond seven blocks");
        check(TitanMovementFeedback.shouldFollow(true,true,36),"must keep closing until five blocks");
        check(!TitanMovementFeedback.shouldFollow(true,true,25),"must stop with room for owner and Titan");
        check(!TitanMovementFeedback.shouldFollow(false,true,900),"guard mode must never chase owner");
        check(TitanMovementFeedback.shouldRun(true,false,169,false),"far owner needs running catch-up");
        check(TitanMovementFeedback.shouldRun(true,true,100,false),"run state must not chatter near threshold");
        check(!TitanMovementFeedback.shouldRun(true,true,64,false),"close owner needs walking approach");
        check(TitanMovementFeedback.shouldRun(true,false,64,true),"sprinting owner must be followed at a run");
        check(!TitanMovementFeedback.shouldRun(false,true,900,true),"idle Titan cannot remain in run state");
        System.out.println("PASS: 200-tick fire cadence, combat feedback throttling, follow/run hysteresis");
    }
}
