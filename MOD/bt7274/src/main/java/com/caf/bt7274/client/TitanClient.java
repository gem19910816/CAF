package com.caf.bt7274.client;

import com.caf.bt7274.BT7274Mod;
import com.caf.bt7274.BtAnimationTimings;
import com.caf.bt7274.TitanEntity;
import com.caf.bt7274.TitanInputPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid=BT7274Mod.ID,value=Dist.CLIENT)
public final class TitanClient {
    private static KeyMapping key(String id,int key){return new KeyMapping("key.bt7274."+id,KeyConflictContext.IN_GAME,InputConstants.Type.KEYSYM,key,"key.categories.bt7274");}
    public static final KeyMapping DASH=key("dash",GLFW.GLFW_KEY_SPACE),SHIELD=key("shield",GLFW.GLFW_KEY_Q),
        MELEE=key("melee",GLFW.GLFW_KEY_V),CORE=key("core",GLFW.GLFW_KEY_X),RELOAD=key("reload",GLFW.GLFW_KEY_R),
        ROCKET=key("rocket",GLFW.GLFW_KEY_F),SMOKE=key("smoke",GLFW.GLFW_KEY_C),CROUCH=key("crouch",GLFW.GLFW_KEY_LEFT_ALT),EJECT=key("eject",GLFW.GLFW_KEY_G);
    private static boolean wasPilot,dismountRequested;
    private static CameraType previousCamera=CameraType.FIRST_PERSON;
    private static int mountedTicks;
    private static int lastShotCount,shotFlashTick=-100;
    private static float previousAds,ads;
    private TitanClient(){}
    @EventBusSubscriber(modid=BT7274Mod.ID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        @SubscribeEvent public static void keys(RegisterKeyMappingsEvent e){for(var key:new KeyMapping[]{DASH,SHIELD,MELEE,CORE,RELOAD,ROCKET,SMOKE,CROUCH,EJECT})e.register(key);}
        @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers e){
            e.registerEntityRenderer(BT7274Mod.TITAN.get(),TitanRenderer::new);e.registerEntityRenderer(BT7274Mod.ROCKET.get(),TitanRocketRenderer::new);
        }
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post e){
        Minecraft mc=Minecraft.getInstance();boolean riding=mc.player!=null&&mc.player.getVehicle() instanceof TitanEntity;
        if(riding&&!wasPilot){previousCamera=mc.options.getCameraType();mc.options.setCameraType(CameraType.FIRST_PERSON);mountedTicks=0;lastShotCount=((TitanEntity)mc.player.getVehicle()).getShotCount();shotFlashTick=-100;}
        if(!riding&&wasPilot)mc.options.setCameraType(previousCamera);
        wasPilot=riding;if(!riding){previousAds=ads=0;dismountRequested=false;return;}mountedTicks++;
        if(lastShotCount!=((TitanEntity)mc.player.getVehicle()).getShotCount()){lastShotCount=((TitanEntity)mc.player.getVehicle()).getShotCount();shotFlashTick=mountedTicks;}
        previousAds=ads;ads=Mth.approach(ads,((TitanEntity)mc.player.getVehicle()).isAiming()?1:0,1/6F);
        int b=0;float forward=0,strafe=0;
        if(mc.screen==null&&mc.isWindowActive()){
            if(mc.options.keyUp.isDown())forward++;if(mc.options.keyDown.isDown())forward--;
            if(mc.options.keyLeft.isDown())strafe++;if(mc.options.keyRight.isDown())strafe--;
            if(mc.options.keyAttack.isDown())b|=1;if(mc.options.keyUse.isDown())b|=2;if(mc.options.keyJump.isDown())b|=4;
            if(DASH.isDown())b|=4;if(ROCKET.isDown())b|=8;if(MELEE.isDown())b|=16;if(SHIELD.isDown())b|=32;if(CORE.isDown())b|=64;if(mc.options.keySprint.isDown())b|=128;
            if(dismountRequested)b|=4096;
            if(RELOAD.isDown())b|=256;if(CROUCH.isDown())b|=512;if(SMOKE.isDown())b|=1024;if(EJECT.isDown())b|=2048;
        }
        if(Boolean.getBoolean("bt.nativeQa")){b=Integer.getInteger("bt.qaButtons",0);forward=Float.parseFloat(System.getProperty("bt.qaForward","0"));strafe=Float.parseFloat(System.getProperty("bt.qaStrafe","0"));}
        PacketDistributor.sendToServer(new TitanInputPayload(strafe,forward,b));dismountRequested=false;
    }
    @SubscribeEvent public static void interact(InputEvent.InteractionKeyMappingTriggered e){
        Minecraft mc=Minecraft.getInstance();if(mc.player!=null&&mc.player.getVehicle() instanceof TitanEntity){e.setCanceled(true);e.setSwingHand(false);}
    }
    @SubscribeEvent public static void pilotKeys(InputEvent.Key e){suppressVanillaPilotActions();}
    @SubscribeEvent public static void beforeTick(ClientTickEvent.Pre e){suppressVanillaPilotActions();}
    private static void suppressVanillaPilotActions(){
        var mc=Minecraft.getInstance();if(mc.player==null||!(mc.player.getVehicle() instanceof TitanEntity)||mc.screen!=null)return;
        // Q and F are titan abilities. Drain only vanilla item actions, leaving
        // the separate ability KeyMappings pressed for the pilot input packet.
        while(mc.options.keyDrop.consumeClick()){}while(mc.options.keySwapOffhand.consumeClick()){}
        mc.options.keyDrop.setDown(false);mc.options.keySwapOffhand.setDown(false);
        // Shift requests the authored 67-tick disembark. Suppress vanilla's
        // immediate local stopRiding so the cockpit sequence can finish first.
        if(mc.options.keyShift.isDown())dismountRequested=true;
        mc.options.keyShift.setDown(false);mc.player.input.shiftKeyDown=false;mc.player.setShiftKeyDown(false);
    }
    @SubscribeEvent public static void player(RenderPlayerEvent.Pre e){if(e.getEntity().getVehicle() instanceof TitanEntity)e.setCanceled(true);}
    @SubscribeEvent public static void hands(RenderHandEvent e){
        Minecraft mc=Minecraft.getInstance();if(mc.player!=null&&mc.player.getVehicle() instanceof TitanEntity)e.setCanceled(true);
    }
    @SubscribeEvent public static void cameraDistance(CalculateDetachedCameraDistanceEvent e){
        Minecraft mc=Minecraft.getInstance();if(mc.player!=null&&mc.player.getVehicle() instanceof TitanEntity)e.setDistance(9F);
    }
    @SubscribeEvent public static void vanillaCrosshair(RenderGuiLayerEvent.Pre e){
        Minecraft mc=Minecraft.getInstance();
        if(mc.player!=null&&mc.player.getVehicle() instanceof TitanEntity&&java.util.Set.of(VanillaGuiLayers.CROSSHAIR,VanillaGuiLayers.HOTBAR,VanillaGuiLayers.JUMP_METER,VanillaGuiLayers.EXPERIENCE_BAR,VanillaGuiLayers.PLAYER_HEALTH,VanillaGuiLayers.ARMOR_LEVEL,VanillaGuiLayers.FOOD_LEVEL,VanillaGuiLayers.VEHICLE_HEALTH,VanillaGuiLayers.AIR_LEVEL,VanillaGuiLayers.SELECTED_ITEM_NAME,VanillaGuiLayers.EXPERIENCE_LEVEL).contains(e.getName()))e.setCanceled(true);
    }
    @SubscribeEvent public static void fov(net.neoforged.neoforge.client.event.ViewportEvent.ComputeFov e){var mc=Minecraft.getInstance();if(mc.player!=null&&mc.player.getVehicle() instanceof TitanEntity&&mc.options.getCameraType().isFirstPerson())e.setFOV(e.usedConfiguredFov()?75-42*Mth.lerp((float)e.getPartialTick(),previousAds,ads):75);}
    @SubscribeEvent public static void hud(RenderGuiEvent.Post e){
        Minecraft mc=Minecraft.getInstance();if(mc.player==null||!(mc.player.getVehicle() instanceof TitanEntity t)||mc.options.hideGui)return;
        GuiGraphics g=e.getGuiGraphics();int w=g.guiWidth(),h=g.guiHeight();
        int c=t.isCoreActive()?0xFFFFA64A:0xFF84DCDF;
        if(transitionHud(g,t,w,h,c))return;
        if(mc.options.getCameraType().isFirstPerson())cockpit(g,t,w,h,c);
        g.fill(0,0,w,2,c);g.fill(0,h-2,w,h,c);
        g.fill(10,16,13,55,c);g.fill(w-13,16,w-10,55,c);
        g.fill(16,12,Math.min(w-16,350),45,0xB0101A1E);
        g.drawString(mc.font,"BT-7274 / EXPEDITION",21,18,c,false);
        g.drawString(mc.font,Component.translatable(t.isCoreActive()?"hud.bt7274.core_active":"hud.bt7274.linked"),21,31,0xFFC8DEE0,false);
        int x=20,y=h-122;
        g.fill(x-6,y-6,x+144,y+56,0xB0101A1E);
        g.fill(w-164,y+31,w-14,y+56,0xB0101A1E);
        bar(g,x,y,138,t.getHealth()/t.getMaxHealth(),0xFF7BC681,Component.translatable("hud.bt7274.hull"));
        if(!t.isDoomed())for(int segment=1;segment<5;segment++)g.fill(x+138*segment/5-1,y+9,x+138*segment/5+1,y+15,0xFF10181A);
        bar(g,x,y+19,138,t.getShield()/TitanEntity.MAX_SHIELD,0xFF79D9EE,Component.translatable("hud.bt7274.shield"));
        bar(g,x,y+38,138,t.getEnergy()/100,0xFFFFC677,Component.translatable("hud.bt7274.energy"));
        bar(g,w-158,y+38,138,t.isCoreActive()?t.getCoreActiveTicks()/(float)BtAnimationTimings.CORE_ACTIVE_TICKS:t.getCoreCharge()/100,0xFFFF9F48,Component.translatable("hud.bt7274.core"));
        g.drawString(mc.font,t.getReloadTicks()>0?"RELOAD "+String.format(java.util.Locale.ROOT,"%.1fs",t.getReloadTicks()/20F):"XO-16  "+t.getMagazine()+" / 30",w-155,y+15,0xFFE1EBED,false);
        if(t.getRocketLocks()>0)g.drawString(mc.font,"LOCK  "+t.getRocketLocks()+" / 12",w-155,y-2,0xFFFFBB55,false);
        TitanTargetHud.draw(g,t,w,h);
        abilityStatus(g,t,w,h);
        if(t.isDoomed())g.drawCenteredString(mc.font,Component.translatable("hud.bt7274.doomed"),w/2,70,0xFFFF624A);
        if(t.getEjectTicks()>0)g.drawCenteredString(mc.font,Component.translatable("hud.bt7274.ejecting"),w/2,h/2-46,0xFFFFA33D);
        else if(t.getEjectConfirmTicks()>0)g.drawCenteredString(mc.font,Component.translatable("hud.bt7274.eject_confirm",EJECT.getTranslatedKeyMessage()),w/2,h/2-46,0xFFFFA33D);
        if(t.getCoreChargeTicks()>0)g.drawCenteredString(mc.font,Component.translatable("hud.bt7274.core_charging",String.format(java.util.Locale.ROOT,"%.1f",t.getCoreChargeTicks()/20F)),w/2,h/2+40,0xFFFFB74D);
        var controls=mc.font.split(Component.translatable("hud.bt7274.controls_native",DASH.getTranslatedKeyMessage(),RELOAD.getTranslatedKeyMessage(),ROCKET.getTranslatedKeyMessage(),SHIELD.getTranslatedKeyMessage(),SMOKE.getTranslatedKeyMessage(),CORE.getTranslatedKeyMessage()),w-40);
        if(mountedTicks<160){g.fill(16,46,w-16,53+controls.size()*10,0xB0101A1E);for(int line=0;line<controls.size();line++)g.drawString(mc.font,controls.get(line),20,50+line*10,0xFFE1EBED,false);}
        int cx=w/2,cy=h/2;
        int radius=Math.round(16-7*ads),gap=Math.round(7-4*ads);
        g.fill(cx-radius-1,cy-1,cx-gap+1,cy+2,0xCC10181A);g.fill(cx+gap-1,cy-1,cx+radius+2,cy+2,0xCC10181A);
        g.fill(cx-1,cy-radius-1,cx+2,cy-gap+1,0xCC10181A);g.fill(cx-1,cy+gap-1,cx+2,cy+radius+2,0xCC10181A);
        g.fill(cx-radius,cy,cx-gap,cy+1,c);g.fill(cx+gap,cy,cx+radius+1,cy+1,c);g.fill(cx,cy-radius,cx+1,cy-gap,c);g.fill(cx,cy+gap,cx+1,cy+radius+1,c);
        if(ads>.8F)g.drawString(mc.font,"ZOOM",cx+21,cy+18,0xB084DCDF,false);
        if(t.getHealth()<150 && (mountedTicks/10)%2==0)g.drawCenteredString(mc.font,Component.translatable("hud.bt7274.critical"),cx,65+controls.size()*10,0xFFFF6655);
        if(t.isShieldActive()){g.fill(0,0,3,h,0xFF6CF2FF);g.fill(w-3,0,w,h,0xFF6CF2FF);}
    }
    /** Thin cabin frame and readable instruments leave the centre unobstructed. */
    private static void cockpit(GuiGraphics g,TitanEntity t,int w,int h,int color){
        int frame=0xF51B252A,edge=0xFF42545A,dim=0xAA6B969D;
        g.fill(0,2,w,7,frame);g.fill(0,7,w/5,10,edge);g.fill(w-w/5,7,w,10,edge);
        // Sloped upper corners, matching a recessed canopy rather than a flat box.
        for(int step=0;step<6;step++){int y=7+step*3;g.fill(0,y,23-step*3,y+3,frame);g.fill(w-23+step*3,y,w,y+3,frame);}
        g.fill(0,25,4,h-45,frame);g.fill(w-4,25,w,h-45,frame);
        for(int step=0;step<8;step++){int y=h-52+step*5,reach=8+step*4;g.fill(0,y,reach,y+5,frame);g.fill(w-reach,y,w,y+5,frame);g.fill(reach-2,y,reach,y+5,edge);g.fill(w-reach,y,w-reach+2,y+5,edge);}
        g.fill(0,h-12,w,h-2,frame);g.fill(36,h-13,w-36,h-11,edge);
        int y=h-50;var font=Minecraft.getInstance().font;
        g.drawString(font,"DASH",22,y,dim,false);g.drawString(font,"VORTEX",w-92,y,dim,false);
        for(int i=0;i<2;i++){int x=22+i*36;g.fill(x,y+11,x+31,y+15,0xFF26383D);g.fill(x,y+11,x+(int)(31*Mth.clamp((t.getEnergy()-i*50)/50,0,1)),y+15,color);}
        g.fill(w-92,y+11,w-22,y+15,0xFF26383D);g.fill(w-92,y+11,w-92+(int)(70*t.getVortexEnergy()),y+15,0xFF77DCED);
        if(t.getCoreChargeTicks()>0||t.isCoreActive()){
            float value=t.isCoreActive()?t.getCoreActiveTicks()/(float)BtAnimationTimings.CORE_ACTIVE_TICKS:1-t.getCoreChargeTicks()/(float)BtAnimationTimings.CORE_CHARGE_TICKS;
            g.fill(w/2-52,h-28,w/2+52,h-22,0xD024211B);g.fill(w/2-50,h-26,w/2-50+(int)(100*value),h-24,0xFFFFB35A);
            if(t.isCoreActive())g.fillGradient(5,7,w-5,40,0x22FF9C33,0x00FF9C33);
        }
        if(t.isVortexActive()){
            g.fillGradient(5,10,27,h-14,0x263AE9F4,0x063AE9F4);g.fillGradient(w-27,10,w-5,h-14,0x263AE9F4,0x063AE9F4);
            g.drawCenteredString(font,"VORTEX ACTIVE",w/2,h-42,0xFF85EDFA);
        }
        int age=mountedTicks-shotFlashTick;
        if(age>=0&&age<3){int alpha=(3-age)*16;g.fillGradient(w/2-45,h-58,w-6,h-9,(alpha<<24)|0xFFD286,0x00FFD286);}
        if(t.isDoomed()){int pulse=(mountedTicks/8)%2==0?0x45FF5030:0x1AFF5030;g.fillGradient(5,8,w-5,35,pulse,0x00FF5030);}
    }
    /** Boarding power-up and shutdown follow the same authoritative body action. */
    private static boolean transitionHud(GuiGraphics g,TitanEntity t,int w,int h,int color){
        double age=Math.max(0,(t.level().getGameTime()-t.getActionStartTick())/20.0);
        boolean boarding=t.getAction().equals("embark")&&age<BtAnimationTimings.EMBARK_TICKS/20.0;
        boolean leaving=t.getDisembarkTicks()>0||t.getEjectTicks()>0;
        if(!boarding&&!leaving)return false;
        int cx=w/2,cy=h/2;var font=Minecraft.getInstance().font;
        String key=boarding?"hud.bt7274.booting":t.getEjectTicks()>0?"hud.bt7274.eject_preparing":"hud.bt7274.exit_preparing";
        g.fill(cx-94,cy+25,cx+94,cy+56,0xC0101A1E);
        g.drawCenteredString(font,Component.translatable(key),cx,cy+31,color);
        float progress=boarding?(float)(age/(BtAnimationTimings.EMBARK_TICKS/20.0))
            :1-(t.getEjectTicks()>0?t.getEjectTicks()/(float)BtAnimationTimings.EJECT_TICKS:t.getDisembarkTicks()/(float)BtAnimationTimings.DISEMBARK_TICKS);
        g.fill(cx-80,cy+46,cx+80,cy+49,0xFF26383D);g.fill(cx-80,cy+46,cx-80+(int)(160*Mth.clamp(progress,0,1)),cy+49,color);
        return true;
    }
    /** Cooldowns come from synchronized server counters, never local key prediction. */
    private static void abilityStatus(GuiGraphics g,TitanEntity t,int w,int h){
        if(w<340||h<210)return;
        int x=w-157,y=h-159;var font=Minecraft.getInstance().font;
        g.fill(x-7,y-5,w-14,y+31,0xB0101A1E);
        cooldown(g,x,y,136,t.getRocketCooldownTicks(),400,"ROCKETS ["+ROCKET.getTranslatedKeyMessage().getString()+"]",0xFFFFBD61);
        cooldown(g,x,y+17,136,t.getSmokeCooldownTicks(),400,"SMOKE ["+SMOKE.getTranslatedKeyMessage().getString()+"]",0xFF9ED6D9);
    }
    private static void cooldown(GuiGraphics g,int x,int y,int width,int remaining,int total,String label,int color){
        var font=Minecraft.getInstance().font;String status=remaining>0?String.format(java.util.Locale.ROOT,"%.1fs",remaining/20F):"READY";
        g.drawString(font,label,x,y,0xFFDAE4E4,false);g.drawString(font,status,x+width-font.width(status),y,remaining>0?0xFF9AA9AB:color,false);
        g.fill(x,y+10,x+width,y+12,0xAF26383D);g.fill(x,y+10,x+(int)(width*(1-Mth.clamp(remaining/(float)total,0,1))),y+12,color);
    }
    private static void bar(GuiGraphics g,int x,int y,int width,float value,int color,Component label){
        var font=Minecraft.getInstance().font;g.drawString(font,label,x,y,0xFFDAE4E4,false);
        g.fill(x,y+10,x+width,y+14,0xAF182628);g.fill(x,y+10,x+(int)(width*Mth.clamp(value,0,1)),y+14,color);
    }
}
