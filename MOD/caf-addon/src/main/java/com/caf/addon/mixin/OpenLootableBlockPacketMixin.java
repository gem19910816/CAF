package com.caf.addon.mixin;

import com.raiiiden.doomsdayfunctionality.blockentity.DoomsdayBlockEntity;
import com.raiiiden.doomsdayfunctionality.network.OpenLootableBlockPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
 * The original packet handler refuses to open the GUI when:
 *  - the block has no DoomsdayBlockEntity (client/server block list mismatch), or
 *  - the inventory rolled no loot ("Empty" message).
 *
 * This mixin replaces handle():
 *  - If the block entity already exists, behave like vanilla (loot + open).
 *  - If the block has no block entity, create a VIRTUAL one that is never
 *    written into the world. If the rolled loot is empty, the container GUI
 *    still opens (empty), and the world stays untouched — no block entity
 *    is persisted. If the rolled loot is non-empty, the block entity is
 *    placed into the world so the items survive and the looted state is
 *    saved. Storage blocks (player can insert items) are always persisted.
 *
 * Net effect: tens of thousands of empty containers leave zero traces in
 * the world save.
 */
@Mixin(value = OpenLootableBlockPacket.class, remap = false)
public abstract class OpenLootableBlockPacketMixin {

    @Shadow
    private BlockPos pos;

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private static void caf$alwaysOpen(OpenLootableBlockPacket msg,
                                       Supplier<NetworkEvent.Context> ctxSupplier,
                                       CallbackInfo ci) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;

            OpenLootableBlockPacketMixin self = (OpenLootableBlockPacketMixin) (Object) msg;
            BlockPos pos = self.pos;
            if (pos == null) return;
            if (player.blockPosition().distSqr(pos) > 400) return;

            ServerLevel level = (ServerLevel) player.level();
            BlockState state = level.getBlockState(pos);

            var blockId = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock());
            boolean storageBlock = blockId != null
                    && com.raiiiden.doomsdayfunctionality.config.DoomsdayCommonConfig.isStorageBlock(blockId.toString());
            if (!storageBlock && blockId != null
                    && com.raiiiden.doomsdayfunctionality.config.DoomsdayCommonConfig.isBlacklisted(blockId.toString())) {
                return;
            }

            BlockEntity be = level.getBlockEntity(pos);
            DoomsdayBlockEntity lootBe;
            boolean virtual = false;
            if (be instanceof DoomsdayBlockEntity existing) {
                lootBe = existing;
            } else {
                var type = com.raiiiden.doomsdayfunctionality.init.BlockEntities.GENERIC.get();
                if (!type.isValid(state)) return;
                // Virtual block entity: exists only in memory, never saved.
                lootBe = new DoomsdayBlockEntity(pos, state);
                lootBe.setLevel(level);
                virtual = true;
            }

            if (!storageBlock && lootBe.isLootingDisabled()) return;

            lootBe.tryLoadLoot(player);

            if (virtual) {
                boolean empty = true;
                for (int i = 0; i < lootBe.getLootHandler().getSlots(); i++) {
                    if (!lootBe.getLootHandler().getStackInSlot(i).isEmpty()) { empty = false; break; }
                }
                // Persist only when there is something worth saving:
                // storage blocks (player may insert items) or non-empty loot.
                if (storageBlock || !empty) {
                    level.setBlockEntity(lootBe);
                }
            }

            // Always open the GUI — empty or not.
            NetworkHooks.openScreen(player, lootBe, pos);
        });
        ctx.setPacketHandled(true);
        ci.cancel();
    }
}
