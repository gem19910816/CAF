package com.caf.btmesh;

/** Pure simulation gait clock. Rendering and audio only sample this state.
 * A marker is one foot contact; two markers are one left/right pair. */
public final class BtGaitClock {
    public record Frame(int clip,double cycle,double stepPhase,float weight,float impact,
                        boolean left,boolean grounded,boolean moving,long generation){}
    private final NativeLocomotionClock distance=new NativeLocomotionClock();
    private boolean initialized,wasMoving,grounded,tail;
    private long tick=Long.MIN_VALUE,generation;
    private int clip;
    private double duration=1,lastRate,tailBase,tailStart,tailWeight,cycleOffset,previousWeight,weight;
    private double lastX,lastZ;
    private float[] markers=new float[0];private boolean[] sides=new boolean[0];

    public void advance(long now,double x,double z,int requestedClip,boolean moving,boolean ground,
                        boolean allowTail,double stride,double nativeDuration,float speed,
                        float[] feet,boolean[] left){
        if(now==tick)return;
        // Capture the presentation endpoint before advancing the raw distance
        // accumulator. It includes a settling tail, unlike raw world distance.
        Frame before=initialized?frame(1):null;
        boolean consecutive=initialized&&now==tick+1;
        // Always refresh world position, including stopped/airborne/hidden FP.
        // `moving` is the server-authorized visual locomotion state (including
        // its bounded ground grace). Real contact gates impacts, not this clock:
        // client stair interpolation must not freeze the visible walking feet.
        var d=distance.advance(now,x,z,Integer.toString(requestedClip),moving?stride:0);
        boolean discontinuity=initialized&&(!consecutive||Math.hypot(x-lastX,z-lastZ)>4);
        if(!initialized){weight=previousWeight=0;generation++;}
        previousWeight=weight;
        boolean validMoving=moving&&stride>1e-6&&feet.length>0;
        if(validMoving){
            if(!wasMoving||clip!=requestedClip||discontinuity)generation++;
            // Native locomotion clips have different contact markers and even
            // different contact counts. Preserve the exact unwrapped L/R phase,
            // then let the new stride increment advance it normally. This same
            // native-cycle offset is consumed by the body, audio and FP layers.
            if(before!=null&&markers.length>0&&(!wasMoving||clip!=requestedClip||tail))
                cycleOffset=inverseStepPhase(before.stepPhase(),feet,left)-d.previousCycle();
            clip=requestedClip;duration=nativeDuration;markers=feet;sides=left;tail=false;
            lastRate=Math.max(0,speed)*20/stride*nativeDuration;
        }else if(wasMoving&&ground&&allowTail&&consecutive){
            tail=true;tailBase=before.cycle();tailStart=now;tailWeight=weight;
        }else if(!ground||!allowTail||discontinuity){
            if(tail||wasMoving||ground!=grounded)generation++;
            if(tail&&before!=null)cycleOffset=before.cycle()-d.previousCycle();
            tail=false;
        }
        if(tail&&now-tailStart>=.36*20){
            // Commit the completed settling displacement so restarting the same
            // clip cannot return to its pre-stop phase and repeat that footfall.
            cycleOffset=tailCycle((now-tailStart)/20.0)-d.previousCycle();tail=false;
        }
        if(!ground&&grounded)generation++;
        if(ground&&!grounded&&initialized)generation++;
        float target=validMoving?(float)Math.min(1,Math.max(0,speed)/(.0254*150/20)):0;
        weight+=Math.max(-.25,Math.min(.25,target-weight));
        tick=now;lastX=x;lastZ=z;grounded=ground;wasMoving=validMoving;initialized=true;
    }
    public Frame frame(float partial){
        if(!initialized)return new Frame(0,0,0,0,0,false,false,false,0);
        double p=Math.max(0,Math.min(1,partial)),cycle=distance.current().cycle((float)p)+cycleOffset;
        float amplitude=(float)(previousWeight+p*(weight-previousWeight));
        if(!wasMoving){
            if(!tail)return new Frame(clip,cycle,stepPhase(cycle,markers,sides),0,0,false,grounded,false,generation);
            double age=Math.max(0,(tick+p-tailStart)/20),u=Math.min(1,age/.36);
            cycle=tailCycle(age);
            amplitude=(float)((1-u)*tailWeight);
        }
        double phase=stepPhase(cycle,markers,sides),fraction=phase-Math.floor(phase);
        float impact=grounded?(float)(Math.exp(-fraction/.16)*amplitude):0;
        boolean left=(((long)Math.floor(phase))&1)==0;
        return new Frame(clip,cycle,phase,amplitude,impact,left,grounded,wasMoving,generation);
    }
    public double bodyCycle(float partial){return frame(partial).cycle();}
    private double tailCycle(double seconds){
        double age=Math.max(0,Math.min(.36,seconds)),u=age/.36;
        return tailBase+age*(1-.5*u)*Math.max(0,Math.min(.6,lastRate))/Math.max(1e-6,duration);
    }

    /** Piecewise map exact native markers to integer contacts, preserving L/R parity. */
    public static double stepPhase(double cycle,float[] markers,boolean[] sides){
        int n=markers.length;if(n==0)return cycle*2;
        long loop=(long)Math.floor(cycle);double local=cycle-loop;int lower=n-1;long lowerLoop=loop-1;
        for(int i=0;i<n;i++)if(local>=markers[i]){lower=i;lowerLoop=loop;}else break;
        int upper=(lower+1)%n;long upperLoop=lowerLoop+(upper==0?1:0);
        double start=(double)lowerLoop+markers[lower],end=(double)upperLoop+markers[upper];
        int parity=sides.length>0&&!sides[0]?1:0;
        return lowerLoop*n+lower+parity+(cycle-start)/Math.max(1e-9,end-start);
    }
    /** Exact inverse of the contact map, including negative cycles and L/R parity. */
    public static double inverseStepPhase(double phase,float[] markers,boolean[] sides){
        int n=markers.length;if(n==0)return phase/2;
        int parity=sides.length>0&&!sides[0]?1:0;
        double ordinal=phase-parity;long contact=(long)Math.floor(ordinal);double fraction=ordinal-contact;
        long loop=Math.floorDiv(contact,n);int lower=Math.floorMod(contact,n),upper=(lower+1)%n;
        double start=(double)loop+markers[lower],end=(double)loop+(upper==0?1:0)+markers[upper];
        return start+fraction*(end-start);
    }
}
