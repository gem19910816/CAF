package com.caf.bt7274;

/** Event clocks for the approved 27-clip cube body. Times are server ticks, not source FPS. */
public final class BtAnimationTimings {
    private BtAnimationTimings(){}
    // BB06 follows SP217: AE_NPC_WEAPON_RELOAD at warped 2.90644017s.
    // Nearest MC tick = round(2.90644017 * 20) = 58; keep the full 80-tick action.
    public static final int RELOAD_TICKS=80, RELOAD_COMMIT_REMAINING=22;
    public static final int EMBARK_TICKS=80, DISEMBARK_TICKS=67;
    // Original paired player pt_dismount_buddy_stand SetVelocity at 1.0s,
    // after BB's .2s entry. The body's empty-cockpit recovery lasts 67 ticks.
    public static final int DISEMBARK_RELEASE_TICKS=24;
    public static final int SKYFALL_TICKS=60, LAND_TICKS=102, LAND_IMPACT_TICKS=29;
    // BB10 radiusHit sourceCycle .2777778 passes poseMelee/actionMeleeTime:
    // 1.00222s -> round(1.00222 * 20) = tick 20. Total action remains 60 ticks.
    // This is source-event timing; body/FP geometric contact still needs visual QA.
    public static final int MELEE_TICKS=60, MELEE_HIT_TICKS=20;
    public static final int EJECT_TICKS=48, EJECT_RELEASE_TICKS=36;
    public static final int CORE_CHARGE_TICKS=37, CORE_ACTIVE_TICKS=110;
    public static final int DOOMED_ENTER_TICKS=28;
    public static final int CROUCH_DOWN_TICKS=30, CROUCH_UP_TICKS=27, HURT_TICKS=17, DEATH_TICKS=60;
    public static int bodyTicks(String action){return switch(action){
        case "embark"->EMBARK_TICKS;case "disembark"->DISEMBARK_TICKS;case "land"->LAND_TICKS;
        case "melee"->MELEE_TICKS;case "eject"->EJECT_TICKS;case "hurt"->HURT_TICKS;case "death"->DEATH_TICKS;
        case "doomed_enter"->DOOMED_ENTER_TICKS;case "crouch_down"->CROUCH_DOWN_TICKS;case "crouch_up"->CROUCH_UP_TICKS;
        case "dash_forward"->50;case "dash_back"->49;case "dash_left"->48;case "dash_right"->52;
        default->0;};}
}
