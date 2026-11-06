package com.caf.bt7274;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Inputs contain no entity id, damage, position or target chosen by the client. */
public record TitanInputPayload(float strafe,float forward,int buttons) implements CustomPacketPayload {
    public static final Type<TitanInputPayload> TYPE=new Type<>(BT7274Mod.id("pilot_input"));
    public static final StreamCodec<RegistryFriendlyByteBuf,TitanInputPayload> CODEC=new StreamCodec<>() {
        @Override public TitanInputPayload decode(RegistryFriendlyByteBuf b){return new TitanInputPayload(b.readFloat(),b.readFloat(),b.readVarInt());}
        @Override public void encode(RegistryFriendlyByteBuf b,TitanInputPayload p){b.writeFloat(p.strafe);b.writeFloat(p.forward);b.writeVarInt(p.buttons);}
    };
    @Override public Type<TitanInputPayload> type(){return TYPE;}
    public static void register(RegisterPayloadHandlersEvent e){
        e.registrar("2").playToServer(TYPE,CODEC,(p,ctx)->ctx.enqueueWork(()->{
            if(ctx.player() instanceof ServerPlayer player && player.getVehicle() instanceof TitanEntity titan)
                titan.acceptInput(player,p.strafe,p.forward,p.buttons);
        }));
    }
}
