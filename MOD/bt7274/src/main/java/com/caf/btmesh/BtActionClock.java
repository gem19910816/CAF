package com.caf.btmesh;

import com.caf.bt7274.BT7274Mod;
import com.caf.bt7274.BtAnimationTimings;
import com.caf.bt7274.TitanEntity;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

/** Shared FP/source-foley clock, pegged to the approved BB magazine contact. */
public final class BtActionClock {
    private static double[] times,cycles,slopes,meleeTimes,meleeCycles,meleeSlopes;
    private BtActionClock(){}
    public static double reloadCycle(TitanEntity titan,float partial){
        double age=(titan.level().getGameTime()+partial-titan.getWeaponActionStartTick())/20.0;
        return reloadCycle(age);
    }
    public static double reloadCycle(double seconds){
        load();
        return evaluate(seconds,times,cycles,slopes);
    }
    public static double meleeCycle(double seconds){load();return evaluate(seconds,meleeTimes,meleeCycles,meleeSlopes);}
    private static double evaluate(double seconds,double[] times,double[] cycles,double[] slopes){
        if(seconds<=times[0])return cycles[0];
        if(seconds>=times[times.length-1])return cycles[cycles.length-1];
        int segment=0;while(segment+1<times.length-1&&seconds>times[segment+1])segment++;
        double h=times[segment+1]-times[segment],u=(seconds-times[segment])/h,u2=u*u,u3=u2*u;
        double value=(2*u3-3*u2+1)*cycles[segment]+(u3-2*u2+u)*h*slopes[segment]
            +(-2*u3+3*u2)*cycles[segment+1]+(u3-u2)*h*slopes[segment+1];
        return Math.max(cycles[segment],Math.min(cycles[segment+1],value));
    }
    private static void load(){
        if(times!=null)return;
        try(var reader=Minecraft.getInstance().getResourceManager().openAsReader(BT7274Mod.id("motion/bb_action_events.json"))){
            var document=JsonParser.parseReader(reader).getAsJsonObject();
            var reload=document.getAsJsonObject("reload");
            if(reload.get("total_ticks").getAsInt()!=BtAnimationTimings.RELOAD_TICKS
                ||reload.get("commit_tick").getAsInt()!=BtAnimationTimings.RELOAD_TICKS-BtAnimationTimings.RELOAD_COMMIT_REMAINING)
                throw new IllegalStateException("BB reload contact and server ammunition clock differ");
            var knots=reload.getAsJsonArray("clock_knots");
            double[] t=new double[knots.size()],c=new double[knots.size()],m=new double[knots.size()];
            for(int i=0;i<t.length;i++){var point=knots.get(i).getAsJsonObject();t[i]=point.get("seconds").getAsDouble();c[i]=point.get("source_cycle").getAsDouble();m[i]=point.get("slope").getAsDouble();}
            var melee=document.getAsJsonObject("melee");
            if(melee.get("total_ticks").getAsInt()!=BtAnimationTimings.MELEE_TICKS||melee.get("contact_tick").getAsInt()!=BtAnimationTimings.MELEE_HIT_TICKS)
                throw new IllegalStateException("BB melee and server contact differ");
            var mk=melee.getAsJsonArray("clock_knots");
            meleeTimes=new double[mk.size()];meleeCycles=new double[mk.size()];meleeSlopes=new double[mk.size()];
            for(int i=0;i<mk.size();i++){var point=mk.get(i).getAsJsonObject();meleeTimes[i]=point.get("seconds").getAsDouble();meleeCycles[i]=point.get("source_cycle").getAsDouble();meleeSlopes[i]=point.get("slope").getAsDouble();}
            cycles=c;slopes=m;times=t;
        }catch(Exception failure){throw new IllegalStateException("Approved BB action cue clock failed",failure);}
    }
}
