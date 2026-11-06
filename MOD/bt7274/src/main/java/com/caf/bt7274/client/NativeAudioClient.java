package com.caf.bt7274.client;

import com.caf.bt7274.TitanEntity;
import com.caf.bt7274.BtAnimationTimings;
import com.caf.btmesh.BtActionClock;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

/** Original complete samples; separate cockpit and spatial-world playback.
 * Reload cue cycles come from atpov_xo16shorty reload_seq AE_CL_PLAYSOUND.
 * Compiled Miles randomization/mixing is still outside this implementation.
 */
@EventBusSubscriber(modid="bt7274",value=Dist.CLIENT)
public final class NativeAudioClient {
    private static final Map<Integer,Tracker> TRACKERS=new HashMap<>();
    private static Object previousLevel;
    private static final ArrayDeque<Map<String,Object>> GAIT_DIAGNOSTICS=new ArrayDeque<>();
    public static List<Map<String,Object>> gaitDiagnostics(){return List.copyOf(GAIT_DIAGNOSTICS);}
    private static final float[] RELOAD_CYCLES={0,.076388888F,.145833328F,.298611104F,.430555552F,.506944418F,.680555582F,.8125F,.861111104F};
    private static final float[] RELOAD_EMPTY_CYCLES={0,.076388888F,.145833328F,.298611104F,.444444448F,.506944418F,.680555582F,.8125F,.861111104F};
    private NativeAudioClient(){}

    /** The driver hears BT's radio voice locally, rather than a voice attenuated
     * from a point several metres outside the cockpit. World listeners retain
     * the mono spatial event. This does not synthesize or replace dialogue. */
    @SubscribeEvent public static void cockpitVoice(PlaySoundEvent event){
        var mc=Minecraft.getInstance();var incoming=event.getSound();
        if(incoming==null||mc.player==null||!(mc.player.getVehicle() instanceof TitanEntity titan)||!mc.options.getCameraType().isFirstPerson())return;
        ResourceLocation id=incoming.getLocation();
        if(!id.getNamespace().equals("bt7274")||id.getPath().endsWith("_1p"))return;
        // The server owns these one-shots; replace their perspective here,
        // rather than also playing them from state edges and doubling the cue.
        boolean voice=id.getPath().startsWith("voice_");
        if(!voice&&!Set.of("rocket","melee","dash","embark","disembark","impact","hurt","shield_hit","smoke").contains(id.getPath()))return;
        if(incoming.isRelative())return;
        double dx=incoming.getX()-titan.getX(),dy=incoming.getY()-(titan.getY()+3),dz=incoming.getZ()-titan.getZ();
        if(dx*dx+dy*dy+dz*dz>9)return;
        // PlaySoundEvent runs before SoundEngine resolves the sample. Vanilla
        // getVolume/getPitch dereference that sample, so resolve before reading.
        if(incoming.getSound()==null)incoming.resolve(mc.getSoundManager());
        event.setSound(new SimpleSoundInstance(ResourceLocation.fromNamespaceAndPath("bt7274",id.getPath()+"_1p"),
            SoundSource.NEUTRAL,incoming.getVolume(),incoming.getPitch(),SoundInstance.createUnseededRandom(),false,0,
            SoundInstance.Attenuation.NONE,0,0,0,true));
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        Minecraft mc=Minecraft.getInstance();
        if(mc.level!=previousLevel){clear();previousLevel=mc.level;}
        if(mc.level==null||mc.player==null){clear();return;}
        if(mc.isPaused())return;
        Set<Integer> seen=new HashSet<>();
        for(var entity:mc.level.entitiesForRendering())if(entity instanceof TitanEntity titan){
            if(titan.distanceToSqr(mc.player)>256*256)continue;
            seen.add(titan.getId());boolean cockpit=mc.player.getVehicle()==titan&&mc.options.getCameraType().isFirstPerson();
            Tracker tracker=TRACKERS.get(titan.getId());
            if(tracker==null||tracker.titan!=titan||tracker.cockpit!=cockpit){
                if(tracker!=null)tracker.stop();tracker=new Tracker(titan,cockpit);TRACKERS.put(titan.getId(),tracker);
            }
            tracker.tick();
        }
        var iterator=TRACKERS.entrySet().iterator();
        while(iterator.hasNext()){var entry=iterator.next();if(!seen.contains(entry.getKey())){entry.getValue().stop();iterator.remove();}}
    }
    public static void clear(){for(Tracker tracker:TRACKERS.values())tracker.stop();TRACKERS.clear();GAIT_DIAGNOSTICS.clear();}
    /** Audio crosses the exact same interpolated contact as this rendered frame. */
    @SubscribeEvent public static void frame(RenderFrameEvent.Pre event){
        var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null||mc.isPaused())return;
        float partial=event.getPartialTick().getGameTimeDeltaPartialTick(false);
        for(var tracker:TRACKERS.values())tracker.locomotionAudio(partial);
    }
    private static SoundEvent sound(String id){return SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath("bt7274",id));}
    private static void one(TitanEntity titan,boolean cockpit,String id,float volume){
        var s=new SimpleSoundInstance(ResourceLocation.fromNamespaceAndPath("bt7274",id),SoundSource.NEUTRAL,volume,1,SoundInstance.createUnseededRandom(),
            false,0,cockpit?SoundInstance.Attenuation.NONE:SoundInstance.Attenuation.LINEAR,
            cockpit?0:titan.getX(),cockpit?0:titan.getY()+4.6,cockpit?0:titan.getZ(),cockpit);
        Minecraft.getInstance().getSoundManager().play(s);
    }
    private static final class Loop extends AbstractTickableSoundInstance {
        final TitanEntity titan;final boolean cockpit;Vec3 fixedOrigin;
        Loop(TitanEntity titan,boolean cockpit,String id,float volume){this(titan,cockpit,id,volume,true);}
        Loop(TitanEntity titan,boolean cockpit,String id,float volume,boolean repeat){
            super(sound(id),SoundSource.NEUTRAL,SoundInstance.createUnseededRandom());this.titan=titan;this.cockpit=cockpit;
            this.looping=repeat;this.delay=0;this.volume=volume;this.pitch=1;this.relative=cockpit;
            this.attenuation=cockpit?Attenuation.NONE:Attenuation.LINEAR;tick();
        }
        @Override public void tick(){if(titan.isRemoved()||!titan.isAlive()){stop();return;}if(!cockpit){x=fixedOrigin==null?titan.getX():fixedOrigin.x;y=fixedOrigin==null?titan.getY()+4.6:fixedOrigin.y;z=fixedOrigin==null?titan.getZ():fixedOrigin.z;}}
    }
    private static final class Tracker {
        final TitanEntity titan;final boolean cockpit;
        int lastShot,lastShotTick=-100,lastReload,lastVortexCaptured,lastBodyStart;String lastBodyAction;double lastBodySeconds;float reloadCycle=-1;
        BtGaitFeedback.Sample previousGait;boolean tailFootPlayed;
        boolean firing,shield,smoke,emptyReload,core;int shieldStart;Loop fireLoop,shieldLoop,shieldStartSound,smokeElectric,smokeHiss,engine,coreStartSound,coreMotor,lockLoop;
        Tracker(TitanEntity titan,boolean cockpit){this.titan=titan;this.cockpit=cockpit;lastShot=titan.getShotCount();lastReload=titan.getReloadTicks();reloadCycle=lastReload>0?(float)BtActionClock.reloadCycle(titan,0):-1;emptyReload=titan.getMagazine()==0;lastBodyStart=titan.getActionStartTick();lastBodyAction=titan.getAction();lastBodySeconds=(titan.level().getGameTime()-lastBodyStart)/20.0;}
        void tick(){
            if(cockpit&&engine==null&&titan.isAlive()&&(!titan.getAction().equals("embark")||(titan.level().getGameTime()-titan.getActionStartTick())>=60)){engine=new Loop(titan,true,"cockpit_engine_1p",.45F);Minecraft.getInstance().getSoundManager().play(engine);}
            bodyActionAudio();
            int now=titan.tickCount,shots=titan.getShotCount();
            boolean coreRunning=titan.getCoreChargeTicks()>0||titan.isCoreActive();
            if(coreRunning&&!core){
                core=true;
                if(titan.getCoreChargeTicks()>0){coreStartSound=new Loop(titan,cockpit,cockpit?"core_start_1p":"core_start_3p",cockpit?.75F:2,false);Minecraft.getInstance().getSoundManager().play(coreStartSound);}
                coreMotor=new Loop(titan,cockpit,cockpit?"core_motor_1p":"core_motor_3p",cockpit?.5F:1.5F);Minecraft.getInstance().getSoundManager().play(coreMotor);
            }
            if(!coreRunning&&core){
                core=false;stopLoop(coreStartSound);stopLoop(coreMotor);coreStartSound=coreMotor=null;
                if(titan.isAlive())one(titan,cockpit,cockpit?"core_end_1p":"core_end_3p",cockpit?.7F:2);
            }
            boolean acquiring=cockpit&&TitanClient.ROCKET.isDown()&&titan.getRocketCooldownTicks()==0&&titan.getRocketLocks()>0&&titan.getRocketLocks()<12;
            if(acquiring&&lockLoop==null){lockLoop=new Loop(titan,true,"rocket_lock_loop_1p",.5F);Minecraft.getInstance().getSoundManager().play(lockLoop);}
            if(!acquiring&&lockLoop!=null){stopLoop(lockLoop);lockLoop=null;}
            if(shots!=lastShot){
                int count=Math.min(8,Math.max(1,shots-lastShot));boolean start=!firing;
                if(cockpit){
                    for(int n=0;n<count;n++)one(titan,true,titan.isCoreActive()?"fire_amped_1p":start&&n==0?"fire_first_1p":"fire_second_1p",1);
                }
                if(!firing){firing=true;fireLoop=new Loop(titan,cockpit,cockpit?"fire_loop_1p":"fire_loop_3p",cockpit?.65F:3);Minecraft.getInstance().getSoundManager().play(fireLoop);}
                lastShot=shots;lastShotTick=now;
            }
            if(firing&&(now-lastShotTick>3||titan.getReloadTicks()>0||!titan.isAlive())){
                stopLoop(fireLoop);fireLoop=null;firing=false;
                if(cockpit){one(titan,true,"fire_tail_1p",1);one(titan,true,"fire_tail_env_1p",.65F);one(titan,true,"fire_tail_mech_1p",1);}
                else one(titan,false,"fire_tail_3p",3);
            }
            int reload=titan.getReloadTicks();
            if(reload>0){
                if(lastReload<=0||reload>lastReload){reloadCycle=-1;emptyReload=titan.getMagazine()==0;}
                float cycle=(float)BtActionClock.reloadCycle(titan,0);
                float[] cues=emptyReload?RELOAD_EMPTY_CYCLES:RELOAD_CYCLES;
                for(int i=0;i<cues.length;i++)if(cues[i]>reloadCycle&&cues[i]<=cycle)
                    one(titan,cockpit,"reload_"+(cockpit?"1p_":"3p_")+(i+1),cockpit?1:2);
                reloadCycle=cycle;
            }else reloadCycle=-1;
            lastReload=reload;
            boolean vortex=titan.isVortexActive();
            if(vortex)lastVortexCaptured=Math.max(lastVortexCaptured,titan.getVortexCapturedCount());
            if(vortex&&!shield){shield=true;lastVortexCaptured=titan.getVortexCapturedCount();shieldStart=now;shieldStartSound=new Loop(titan,cockpit,cockpit?"shield_start_1p":"shield_start_3p",cockpit?1:2,false);Minecraft.getInstance().getSoundManager().play(shieldStartSound);}
            if(vortex&&cockpit&&shieldLoop==null&&now-shieldStart>=12){shieldLoop=new Loop(titan,true,"shield_loop_1p",.7F);Minecraft.getInstance().getSoundManager().play(shieldLoop);}
            if(!vortex&&shield){shield=false;stopLoop(shieldLoop);stopLoop(shieldStartSound);shieldLoop=shieldStartSound=null;one(titan,cockpit,(lastVortexCaptured>0||titan.getVortexCapturedCount()>0)?(cockpit?"shield_throw_1p":"shield_throw_3p"):(cockpit?"shield_end_1p":"shield_end_3p"),cockpit?1:2);lastVortexCaptured=0;}
            boolean smoking=titan.getSmokeTicks()>0;
            if(smoking&&!smoke){
                smoke=true;smokeElectric=new Loop(titan,false,"smoke_electric",1.5F,false);smokeHiss=new Loop(titan,false,"smoke_hiss",1.5F,false);
                smokeElectric.fixedOrigin=titan.getSmokeOrigin();smokeHiss.fixedOrigin=titan.getSmokeOrigin();smokeElectric.tick();smokeHiss.tick();
                Minecraft.getInstance().getSoundManager().play(smokeElectric);Minecraft.getInstance().getSoundManager().play(smokeHiss);
            }
            if(!smoking&&smoke){smoke=false;stopLoop(smokeElectric);stopLoop(smokeHiss);smokeElectric=smokeHiss=null;}
            if(!titan.isAlive())stop();
        }
        /** The authored entry blends are part of the action clock, so native
         * foley begins when its source motion begins rather than on input. */
        void bodyActionAudio(){
            String action=titan.getAction();int start=titan.getActionStartTick();
            double age=Math.max(0,(titan.level().getGameTime()-start)/20.0);
            if(start!=lastBodyStart||!action.equals(lastBodyAction)){lastBodyStart=start;lastBodyAction=action;lastBodySeconds=-.001;}
            double cue=switch(action){case "melee"->.18;case "embark","disembark"->.2;default->-1;};
            if(cue>lastBodySeconds&&cue<=age)one(titan,cockpit,action+(cockpit?"_1p":""),cockpit?1:2);
            lastBodySeconds=age;
        }
        /** A state edge/landing establishes a baseline, never replays old contacts. */
        void locomotionAudio(float partial){
            var now=BtGaitFeedback.frame(titan,partial);var before=previousGait;previousGait=now;
            if(now.moving())tailFootPlayed=false;
            if(before==null||before.generation()!=now.generation()||before.clip()!=now.clip()
                ||!now.grounded()||!titan.isAlive()||now.weight()<=.15F||!before.grounded())return;
            double crossed=Math.floor(now.stepPhase())-Math.floor(before.stepPhase());
            // No catch-up after frame suspension, teleport or a visibility switch.
            if(crossed>0&&crossed<=1&&(now.moving()||!tailFootPlayed)){
                one(titan,cockpit,cockpit?"step_1p":"step",(cockpit?.85F:2.5F)*Math.min(1,now.weight()));
                recordGait(now,"step");if(!now.moving())tailFootPlayed=true;
            }
            if(now.moving())for(float marker:BtFootstepCues.servos(now.clip()))if(crossed(before.cycle(),now.cycle(),marker)){
                one(titan,cockpit,cockpit?"servo_1p":"servo",(cockpit?.28F:.7F)*Math.min(1,now.weight()));recordGait(now,"servo");
            }
        }
        private void recordGait(BtGaitFeedback.Sample s,String cue){
            Map<String,Object> entry=new LinkedHashMap<>();entry.put("titan",titan.getId());entry.put("game_tick",titan.level().getGameTime());
            entry.put("clip",s.clip());entry.put("cycle",s.cycle());entry.put("step_phase",s.stepPhase());entry.put("left",s.left());
            entry.put("weight",s.weight());entry.put("impact",s.impact());entry.put("moving",s.moving());entry.put("grounded",s.grounded());entry.put("cue",cue);
            GAIT_DIAGNOSTICS.addLast(Map.copyOf(entry));while(GAIT_DIAGNOSTICS.size()>128)GAIT_DIAGNOSTICS.removeFirst();
        }
        private static boolean crossed(double previous,double current,float marker){
            return current>=previous&&Math.floor(current-marker)>Math.floor(previous-marker);
        }
        void stop(){stopLoop(fireLoop);stopLoop(shieldLoop);stopLoop(shieldStartSound);stopLoop(smokeElectric);stopLoop(smokeHiss);stopLoop(engine);stopLoop(coreStartSound);stopLoop(coreMotor);stopLoop(lockLoop);fireLoop=shieldLoop=shieldStartSound=smokeElectric=smokeHiss=engine=coreStartSound=coreMotor=lockLoop=null;}
        private static void stopLoop(Loop loop){if(loop!=null)Minecraft.getInstance().getSoundManager().stop(loop);}
    }
}
