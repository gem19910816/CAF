package com.chaosz.tarkovstamina.survival;

import com.chaosz.tarkovstamina.entity.ShitballEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 屎投掷处理
 * <p>
 * 未蹲下 + 右键 caf:shit → 投掷屎球
 * 蹲下 + 右键 caf:shit → 吃屎（由 PoopSystem 处理）
 * </p>
 */
public final class ShitballThrowHandler {
    private static final ResourceLocation SHIT_ID = ResourceLocation.tryBuild("caf", "shit");

    private ShitballThrowHandler() {
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getSide().isClient()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.isCrouching()) return; // 蹲下由 PoopSystem 吃屎处理

        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (!SHIT_ID.equals(id)) return;

        throwShitball(player, stack);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getSide().isClient()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.isCrouching()) return;

        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (!SHIT_ID.equals(id)) return;

        event.setCanceled(true);
        throwShitball(player, stack);
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getSide().isClient()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (!SHIT_ID.equals(id)) return;

        event.setCanceled(true);
        throwShitball(player, stack);
    }

    private static void throwShitball(Player player, ItemStack stack) {
        var level = player.level();
        if (!level.isClientSide()) {
            var entity = new ShitballEntity(level, player);
            entity.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, 1.5F, 1.0F);
            level.addFreshEntity(entity);
        }

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.SNOWBALL_THROW, SoundSource.NEUTRAL,
                0.5F, 0.4F / (level.getRandom().nextFloat() * 0.4F + 0.8F));

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
    }
}