package com.vehiclelock.mod.network;

import com.vehiclelock.mod.VehicleLockMod;
import com.vehiclelock.mod.handler.VehicleLockHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.UUID;

/**
 * Network channel for the vehicle lock mod.
 * Currently only handles the "approval" packet (Y key → server).
 */
public class VehicleLockNetwork {

    public static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.of(VehicleLockMod.MOD_ID + ":main", ':'),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );
    private static int id = 0;

    /**
     * Packet: client → server.  Sent when the player presses the "approve" key (Y).
     * No payload needed — the server knows who the player is.
     */
    public static class C2SApproveRequest {
        public C2SApproveRequest() {}
    }

    public static void register() {
        CHANNEL.registerMessage(id++, C2SApproveRequest.class,
                (pkt, buf) -> {},
                buf -> new C2SApproveRequest(),
                (pkt, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        ServerPlayer player = ctx.get().getSender();
                        if (player != null) {
                            VehicleLockHandler.onApproveKeyPressed(player);
                        }
                    });
                    ctx.get().setPacketHandled(true);
                }
        );
    }

    public static void sendApproveRequest() {
        CHANNEL.sendToServer(new C2SApproveRequest());
    }
}