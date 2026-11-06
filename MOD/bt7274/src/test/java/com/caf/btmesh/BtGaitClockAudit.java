package com.caf.btmesh;

/** Standalone Java21 audit; no Minecraft, UI, graphics or file writes. */
public final class BtGaitClockAudit {
    private static final float[] MARKERS={.1F,.3F,.5F,.7F,.9F,.98F};
    private static final boolean[] LEFT={false,true,false,true,false,true};
    private static final float[] SPRINT={.0987654328F,.2592592537F,.4197530746F,.5925925970F,.7530864477F,.9259259105F};
    private static final float[] WALK={.0816326514F,.2551020384F,.4183673561F,.5816326737F,.7448979616F,.9183673263F};
    private static final boolean[] WALK_LEFT={true,false,true,false,true,false};
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void tick(BtGaitClock c,long n,double x,int id,boolean moving,boolean ground){
        c.advance(n,x,0,id,moving,ground,true,10,2.7,.5F,MARKERS,LEFT);
    }
    public static void main(String[] args){
        for(int loop=-2;loop<3;loop++)for(int i=0;i<MARKERS.length;i++){
            double phase=BtGaitClock.stepPhase((double)loop+MARKERS[i],MARKERS,LEFT);
            check(Math.abs(phase-(loop*6+i+1))<1e-6,"source marker/contact parity");
        }
        double prior=Double.NEGATIVE_INFINITY;
        for(int i=0;i<=1000;i++){
            double phase=BtGaitClock.stepPhase(-2+i/200.0,MARKERS,LEFT);
            check(phase>prior,"continuous monotonic contact phase across loop boundary");prior=phase;
            double inverse=BtGaitClock.inverseStepPhase(phase,MARKERS,LEFT);
            check(Math.abs(inverse-(-2+i/200.0))<1e-10,"contact map exact inverse including negative loops");
        }
        var c=new BtGaitClock();tick(c,1,0,4,true,true);tick(c,2,.5,4,true,true);
        var a=c.frame(.35F);check(a.equals(c.frame(.35F)),"read idempotence/FP hidden frame");
        check(Math.abs(c.frame(0).cycle())<1e-9&&Math.abs(c.frame(1).cycle()-.05)<1e-9,"previous-current render interpolation");
        tick(c,2,12,4,true,true);check(c.frame(.35F).equals(a),"duplicate tick cannot advance/pause");
        for(int i=3;i<=20;i++)tick(c,i,i*.5,1,false,true);
        double before=c.bodyCycle(1);tick(c,21,10.5,4,true,true);
        check(Math.abs(c.bodyCycle(1)-before-.05)<1e-9,"stopped FP distance not caught up");
        long gen=c.frame(1).generation();tick(c,22,11,24,true,true);
        check(c.frame(1).generation()>gen,"state transition resets cue baseline");
        gen=c.frame(1).generation();double beforeAir=c.bodyCycle(1);tick(c,23,11.5,24,true,false);
        check(c.frame(1).impact()==0,"visual grace without actual support has no audible/FP impact");
        check(c.bodyCycle(1)>beforeAir,"short visual support grace never freezes walking feet");
        beforeAir=c.bodyCycle(1);tick(c,24,12,24,false,false);double inAir=c.bodyCycle(1);
        check(c.frame(1).weight()==0&&c.bodyCycle(1)==beforeAir,"true airborne visual inactive refreshes position without stride");
        tick(c,25,12.5,24,true,true);check(c.frame(1).generation()>gen,"landing resets cue baseline");
        check(c.bodyCycle(1)-inAir<.051,"landing never catches prior air distance");
        gen=c.frame(1).generation();tick(c,26,40,24,true,true);
        check(c.frame(1).generation()>gen,"teleport suppresses old cue interval");
        tick(c,27,40.5,24,true,true);tick(c,28,40.5,1,false,true);
        double tail=c.frame(0).cycle();check(c.frame(.9F).cycle()>=tail,"stop tail continues forward");
        for(int i=29;i<38;i++)tick(c,i,40.5,1,false,true);
        check(c.frame(1).weight()==0&&c.frame(1).impact()==0,"stop tail bounded at .36s");
        var fresh=new BtGaitClock();tick(fresh,100,50,4,true,true);
        check(fresh.bodyCycle(1)==0,"new world/restart does not use old movement");
        check(fresh.frame(.5F).equals(fresh.frame(.5F)),"restart repeated frame pure");
        switchRegression();
        System.out.println("BtGaitClockAudit passed "+checks+" checks");
    }
    private static void switchRegression(){
        var clock=new BtGaitClock();double x=0;long tick=0;double stride=32.48769831879288;
        clock.advance(tick,x,0,4,true,true,true,stride,2.7,.5F,SPRINT,LEFT);
        // Reproduce the real clip4 contact92.01966 -> clip2 rollback91.6319.
        double at=BtGaitClock.inverseStepPhase(92.01966,SPRINT,LEFT)*stride;
        while(x<at){x=Math.min(at,x+1);clock.advance(++tick,x,0,4,true,true,true,stride,2.7,.5F,SPRINT,LEFT);}
        double phase=clock.frame(1).stepPhase();check(Math.abs(phase-92.01966)<1e-8,"real capture regression setup");
        clock.advance(++tick,x+.5,0,2,true,true,true,22.7514455768,3.266666667,.5F,WALK,WALK_LEFT);x+=.5;
        check(Math.abs(clock.frame(0).stepPhase()-phase)<1e-8,"clip4->2 starts at exact old phase, no rollback");
        check(Math.abs(clock.bodyCycle(.37F)-clock.frame(.37F).cycle())<1e-12,"BB body and audio/FP use same mapped native cycle");
        int repeated92=0;
        for(int i=0;i<12;i++){
            var previous=clock.frame(1);clock.advance(++tick,x+.5,0,2,true,true,true,22.7514455768,3.266666667,.5F,WALK,WALK_LEFT);x+=.5;
            var next=clock.frame(1);check(next.stepPhase()>=previous.stepPhase(),"walk after sprint phase monotonic");
            if(Math.floor(previous.stepPhase())<92&&Math.floor(next.stepPhase())>=92)repeated92++;
        }
        check(repeated92==0,"no second contact92 five ticks after captured left footfall");
        double end=clock.frame(1).stepPhase();
        clock.advance(++tick,x,0,1,false,true,true,0,1,0,new float[0],new boolean[0]);
        for(int i=0;i<10;i++){
            double previous=clock.frame(1).stepPhase();clock.advance(++tick,x,0,1,false,true,true,0,1,0,new float[0],new boolean[0]);
            check(clock.frame(1).stepPhase()>=previous-1e-10,"completed stop tail never snaps back");
        }
        double stopped=clock.frame(1).stepPhase();check(stopped>=end,"stop tail preserves completed contact displacement");
        clock.advance(++tick,x+.5,0,4,true,true,true,stride,2.7,.5F,SPRINT,LEFT);
        x+=.5;
        check(Math.abs(clock.frame(0).stepPhase()-stopped)<1e-8,"restart/new clip preserves settled L/R phase");
        // All possible source phases: switch both ways without changing the
        // intrinsic per-metre native cycle increment or even/odd foot labels.
        for(int i=0;i<600;i++){
            double oldPhase=clock.frame(1).stepPhase();boolean sprint=(i&1)==0;
            float[] marker=sprint?SPRINT:WALK;boolean[] side=sprint?LEFT:WALK_LEFT;double st=sprint?stride:22.7514455768;
            clock.advance(++tick,++x,0,sprint?4:2,true,true,true,st,sprint?2.7:3.266666667,.5F,marker,side);
            check(Math.abs(clock.frame(0).stepPhase()-oldPhase)<1e-8,"alternating clips exact step phase continuity");
            check(Math.abs(clock.frame(1).cycle()-clock.frame(0).cycle()-1/st)<1e-10,"normal stride rate unchanged by cycle offset");
            check(clock.frame(1).stepPhase()>oldPhase,"alternating clips monotonic contact phase");
        }
    }
}
