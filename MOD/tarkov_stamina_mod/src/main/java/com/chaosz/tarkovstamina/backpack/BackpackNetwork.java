package com.chaosz.tarkovstamina.backpack;

import com.chaosz.tarkovstamina.backpack.item.MilitaryBackpackItem;
import com.chaosz.tarkovstamina.backpack.menu.BackpackMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;

import java.util.function.Supplier;

public class BackpackNetwork {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            CafBackpack.id("backpack_main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );
    private static int packetId = 0;

    public static void register() {
        CHANNEL.registerMessage(packetId++, OpenBackpackPacket.class,
                OpenBackpackPacket::encode, OpenBackpackPacket::decode, OpenBackpackPacket::handle);
        CHANNEL.registerMessage(packetId++, ScrollBackpackPacket.class,
                ScrollBackpackPacket::encode, ScrollBackpackPacket::decode, ScrollBackpackPacket::handle);
    }

    public static void sendOpenBackpackPacket() {
        CHANNEL.sendToServer(new OpenBackpackPacket());
    }

    public static void sendScrollPacket(int offset) {
        CHANNEL.sendToServer(new ScrollBackpackPacket(offset));
    }

    public record OpenBackpackPacket() {
        public static void encode(OpenBackpackPacket packet, FriendlyByteBuf buf) {}

        public static OpenBackpackPacket decode(FriendlyByteBuf buf) {
            return new OpenBackpackPacket();
        }

        public static void handle(OpenBackpackPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
            NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> {
                ServerPlayer player = ctx.getSender();
                if (player == null) return;
                ItemStack backpackStack = findBackpack(player);
                if (!backpackStack.isEmpty()) {
                    MilitaryBackpackItem.openMenu(player, backpackStack);
                }
            });
            ctx.setPacketHandled(true);
        }

        private static ItemStack findBackpack(ServerPlayer player) {
            if (player.getMainHandItem().getItem() instanceof MilitaryBackpackItem) {
                return player.getMainHandItem();
            }
            if (player.getOffhandItem().getItem() instanceof MilitaryBackpackItem) {
                return player.getOffhandItem();
            }
            return CuriosApi.getCuriosInventory(player).resolve()
                    .flatMap(inv -> inv.findFirstCurio(stack -> stack.getItem() instanceof MilitaryBackpackItem))
                    .map(SlotResult::stack)
                    .orElse(ItemStack.EMPTY);
        }
    }

    public record ScrollBackpackPacket(int offset) {
        public static void encode(ScrollBackpackPacket packet, FriendlyByteBuf buf) {
            buf.writeVarInt(packet.offset);
        }

        public static ScrollBackpackPacket decode(FriendlyByteBuf buf) {
            return new ScrollBackpackPacket(buf.readVarInt());
        }

        public static void handle(ScrollBackpackPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
            NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> {
                ServerPlayer player = ctx.getSender();
                if (player != null && player.containerMenu instanceof BackpackMenu menu) {
                    menu.setBackpackScroll(packet.offset);
                }
            });
            ctx.setPacketHandled(true);
        }
    }
}
