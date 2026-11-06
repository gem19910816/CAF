package com.caf.btmesh;

/** Distance-driven, tick-idempotent original animation clock. The six footsteps
 * in an original BT locomotion clip travel its decoded full stride distance;
 * actual world travel advances the clip by that same fraction. Speed changes
 * alter only the next increment, never elapsedTime*newSpeed or the old phase.
 */
public final class NativeLocomotionClock {
    public record Sample(String sequence,double previousCycle,double cycle,double increment,long tick) {
        public double cycle(float partial){return previousCycle+Math.max(0,Math.min(1,partial))*(cycle-previousCycle);}
        public double seconds(float partial,double nativeDuration){return cycle(partial)*nativeDuration;}
    }
    private boolean initialized;
    private double lastX,lastZ,cycles;
    private long lastTick=Long.MIN_VALUE;
    private Sample sample=new Sample("",0,0,0,Long.MIN_VALUE);
    public Sample advance(long tick,double x,double z,String sequence,double strideMetres){
        if(tick==lastTick)return sample;
        double before=cycles,increment=0;
        if(initialized&&tick>lastTick){
            double distance=Math.hypot(x-lastX,z-lastZ);
            // Network teleports/deployment movement are not an accelerated stride.
            if(distance<=4&&strideMetres>1e-6)increment=distance/strideMetres;
        }
        cycles+=increment;lastX=x;lastZ=z;lastTick=tick;initialized=true;
        sample=new Sample(sequence,before,cycles,increment,tick);return sample;
    }
    public Sample current(){return sample;}
    public void reset(){initialized=false;cycles=0;lastTick=Long.MIN_VALUE;}
}
