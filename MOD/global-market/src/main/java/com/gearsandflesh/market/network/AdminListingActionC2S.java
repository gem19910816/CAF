package com.gearsandflesh.market.network;

import com.gearsandflesh.market.MarketConstants;
import com.gearsandflesh.market.service.MarketService;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record AdminListingActionC2S(long listingId, Action action) {
    public enum Action {
        COPY_ITEM,
        REMOVE_AND_RETURN,
        DELETE_WITHOUT_RETURN
    }

    public AdminListingActionC2S {
        if (action == null) {
            throw new IllegalArgumentException("Administrator market action is required");
        }
    }

    public static void encode(AdminListingActionC2S message, FriendlyByteBuf buffer) {
        buffer.writeVarLong(message.listingId);
        buffer.writeVarInt(message.action.ordinal());
    }

    public static AdminListingActionC2S decode(FriendlyByteBuf buffer) {
        long listingId = buffer.readVarLong();
        int ordinal = buffer.readVarInt();
        Action[] actions = Action.values();
        if (ordinal < 0 || ordinal >= actions.length) {
            throw new DecoderException("Invalid administrator market action " + ordinal);
        }
        return new AdminListingActionC2S(listingId, actions[ordinal]);
    }

    public static void handle(
            AdminListingActionC2S message,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (!player.createCommandSourceStack().hasPermission(
                    MarketConstants.ADMIN_PERMISSION_LEVEL)) {
                MarketNetwork.finishOperation(
                        player,
                        MarketService.OperationResult.error("没有市场管理权限")
                );
                return;
            }
            MarketService.OperationResult result = switch (message.action) {
                case COPY_ITEM -> MarketService.adminCopyListingItem(
                        player, message.listingId);
                case REMOVE_AND_RETURN -> MarketService.adminRemoveListing(
                        player, message.listingId);
                case DELETE_WITHOUT_RETURN -> MarketService.adminDeleteListing(
                        player, message.listingId);
            };
            MarketNetwork.finishOperation(player, result);
        });
        context.setPacketHandled(true);
    }
}
