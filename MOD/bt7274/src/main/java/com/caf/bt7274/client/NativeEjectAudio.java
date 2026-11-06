package com.caf.bt7274.client;

import com.caf.bt7274.TitanEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Native cockpit EjectAudioThink phases: settings switch, boost/ascent,
 * apex, descent. Local player owns the flight wind, never the dying Titan. */
@EventBusSubscriber(modid="bt7274",value=Dist.CLIENT)
public final class NativeEjectAudio {
    private static Object level;
    private static Session active;
    private NativeEjectAudio(){}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();
        if(mc.level!=level||mc.player==null||!mc.player.isAlive()){clear();level=mc.level;return;}
        if(mc.isPaused())return;
        var player=mc.player;
        if(player.getVehicle() instanceof TitanEntity titan&&titan.getEjectTicks()>0){
            if(active==null||active.titan!=titan||active.start!=titan.getActionStartTick()){
                clear();active=new Session(player,titan,titan.getActionStartTick());
            }
        }
        if(active!=null&&!active.tick(player))clear();
    }
    private static void clear(){if(active!=null)active.stop();active=null;}
    private static void one(String id,float volume){
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(ResourceLocation.fromNamespaceAndPath("bt7274",id),SoundSource.NEUTRAL,volume,1,
            SoundInstance.createUnseededRandom(),false,0,SoundInstance.Attenuation.NONE,0,0,0,true));
    }
    private static final class Session {
        final TitanEntity titan;final int start;final LocalPlayer owner;final int began;
        Wind alarm,wind;boolean launched,descending;int releaseTick;
        Session(LocalPlayer owner,TitanEntity titan,int start){
            this.owner=owner;this.titan=titan;this.start=start;began=owner.tickCount;
            one("eject_button_1p",.8F);alarm=new Wind(owner,"eject_alarm_1p",.7F,false);play(alarm);
        }
        boolean tick(LocalPlayer player){
            if(player!=owner||player.tickCount-began>400||player.isRemoved()||!player.isAlive())return false;
            if(!launched){
                if(player.getVehicle()==titan)return titan.getEjectTicks()>0;
                if(player.getVehicle()!=null)return false;
                launched=true;releaseTick=player.tickCount;alarm.fade();
                one("eject_boost_1p",.9F);wind=new Wind(owner,"eject_ascent_1p",.7F,true);play(wind);
            }
            if(player.getVehicle()!=null)return false;
            if(!descending&&player.getDeltaMovement().y<=0&&player.tickCount-releaseTick>2){
                descending=true;wind.fade();one("eject_apex_1p",.7F);
                wind=new Wind(owner,"eject_descent_1p",.65F,true);play(wind);
            }
            return !player.onGround()||player.tickCount-releaseTick<5;
        }
        void stop(){if(alarm!=null)alarm.fade();if(wind!=null)wind.fade();}
        private static void play(Wind sound){Minecraft.getInstance().getSoundManager().play(sound);}
    }
    private static final class Wind extends AbstractTickableSoundInstance {
        final LocalPlayer owner;final float target;boolean fading;
        Wind(LocalPlayer owner,String id,float target,boolean loop){
            super(SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath("bt7274",id)),SoundSource.NEUTRAL,SoundInstance.createUnseededRandom());
            this.owner=owner;this.target=target;looping=loop;delay=0;relative=true;attenuation=Attenuation.NONE;pitch=1;volume=.01F;
        }
        void fade(){fading=true;}
        @Override public void tick(){
            if(owner.isRemoved()||!owner.isAlive()||Minecraft.getInstance().player!=owner||Minecraft.getInstance().level!=owner.level()){stop();return;}
            volume=fading?Math.max(0,volume-.12F):Math.min(target,volume+.12F);if(fading&&volume<=0)stop();
        }
    }
}
