package com.caf.bt7274;

/** Small, deterministic policy shared by movement and damage-feedback checks. */
final class TitanMovementFeedback {
    static final double FOLLOW_WALK_SPEED=1.3, FOLLOW_RUN_SPEED=1.85;
    private int lastEnvironmentalDamage=-1000, lastCombatFeedback=-1000;

    static boolean shouldFollow(boolean followMode,boolean following,double distanceSquared){
        return followMode && distanceSquared>(following?25:49);
    }
    static boolean shouldRun(boolean following,boolean running,double distanceSquared,boolean ownerSprinting){
        return following && (distanceSquared>(running?81:144) || ownerSprinting&&distanceSquared>49);
    }
    boolean acceptEnvironmentalDamage(int tick){
        // Shield hits bypass LivingEntity's invulnerability frames. Restore a
        // half-second contact cadence before applying environmental damage.
        if(tick-lastEnvironmentalDamage<10)return false;
        lastEnvironmentalDamage=tick;return true;
    }
    boolean playCombatFeedback(int tick,boolean environmental){
        // Burning feet are not a cannon strike: they must not restart the
        // original full-body flinch and cockpit impact sample indefinitely.
        if(environmental||tick-lastCombatFeedback<12)return false;
        lastCombatFeedback=tick;return true;
    }
}
