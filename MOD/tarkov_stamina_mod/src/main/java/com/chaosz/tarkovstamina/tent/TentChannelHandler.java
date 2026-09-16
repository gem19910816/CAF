package com.chaosz.tarkovstamina.tent;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 帐篷交互读条系统
 * <p>
 * 潜行+空手右键帐篷 → 读条收起帐篷（200 tick）
 * 手持帐篷物品右键（空气/非交互方块）→ 读条放下帐篷
 * 进度通过 BossBar 展示，中途取消直接移除。
 * </p>
 */
@Mod.EventBusSubscriber(modid = com.chaosz.tarkovstamina.TarkovStamina.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TentChannelHandler {
    private static final int CHANNEL_TICKS = 200;
    private static final int MAX_TICKS = 300;
    private static final double PICKUP_DISTANCE = 4.0;
    private static final double PLACE_DISTANCE = 4.5;

    private static final Set<ResourceLocation> TENT_IDS = Set.of(
            ResourceLocation.parse("simplytents:tent"),
            ResourceLocation.parse("simplytents:wall_tent"),
            ResourceLocation.parse("simplytents:roof_tent"),
            ResourceLocation.parse("simplytents:zip_tent"),
            ResourceLocation.parse("simplytents:duo_tent"),
            ResourceLocation.parse("simplytents:duo_wall_tent"),
            ResourceLocation.parse("simplytents:duo_roof_tent"),
            ResourceLocation.parse("simplytents:duo_zip_tent"),
            ResourceLocation.parse("simplytents:large_tent"),
            ResourceLocation.parse("simplytents:large_wall_tent"),
            ResourceLocation.parse("simplytents:large_roof_tent"),
            ResourceLocation.parse("simplytents:large_zip_tent"),
            ResourceLocation.parse("simplytents:tipi_tent"),
            ResourceLocation.parse("simplytents:small_tipi_tent"),
            ResourceLocation.parse("simplytents:yurt_tent")
    );
    private static final Map<UUID, Channel> CHANNELS = new HashMap<>();

    private TentChannelHandler() {
    }

    private static void startChannel(ServerPlayer player, String type, BlockPos pos, String blockId, ItemStack stack, InteractionHand hand, Direction face, boolean hasTarget) {
        Channel c = new Channel();
        c.dimension = player.level().dimension();
        c.pos = pos.immutable();
        c.blockId = blockId;
        c.itemId = stack.getItem() == null ? "" : String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem()));
        c.type = type;
        c.hand = hand;
        c.face = face;
        c.startTick = player.server.getTickCount();
        c.hasTarget = hasTarget;
        CHANNELS.put(player.getUUID(), c);

        String label = type.equals("pickup") ? "正在收起帐篷 0%" : "正在放下帐篷 0%";
        ServerBossEvent boss = new ServerBossEvent(
                Component.literal(label),
                BossEvent.BossBarColor.YELLOW,
                BossEvent.BossBarOverlay.PROGRESS);
        boss.setProgress(0.0f);
        boss.setVisible(true);
        boss.addPlayer(player);
        c.bossEvent = boss;
    }

    private static void cancel(ServerPlayer player, String message) {
        if (message != null && !message.isEmpty()) {
            player.displayClientMessage(Component.literal(message).withStyle(ChatFormatting.RED), true);
        }
    }

    private static void cleanupChannel(Channel c) {
        if (c != null && c.bossEvent != null) {
            c.bossEvent.removeAllPlayers();
        }
    }

    private static String getBlockId(Level level, BlockPos pos) {
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock());
        return key == null ? "" : key.toString();
    }

    private static boolean isTentBlock(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl != null && rl.getNamespace().equals("simplytents");
    }

    private static boolean isTentItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key != null && TENT_IDS.contains(key);
    }

    private static boolean isInteractiveBlock(BlockState state) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (id == null || !id.getNamespace().equals("minecraft")) {
            return false;
        }
        String p = id.getPath();
        return p.contains("chest") || p.contains("furnace") || p.contains("shulker_box")
                || p.contains("barrel") || p.contains("crafting_table") || p.contains("anvil")
                || p.contains("grindstone") || p.contains("stonecutter") || p.contains("loom")
                || p.contains("cartography_table") || p.contains("smithing_table")
                || p.contains("enchanting_table") || p.contains("lectern") || p.contains("jukebox")
                || p.contains("note_block") || p.contains("beacon") || p.contains("brewing_stand")
                || p.contains("cauldron") || p.contains("composter") || p.contains("respawn_anchor")
                || p.contains("bell") || p.contains("chiseled_bookshelf") || p.contains("hopper")
                || p.contains("dispenser") || p.contains("dropper") || p.contains("_door")
                || p.contains("_trapdoor") || p.contains("_button") || p.contains("_bed")
                || p.contains("_fence_gate") || p.contains("_sign") || p.contains("_banner")
                || p.contains("_sapling");
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getSide().isClient()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ItemStack stack = event.getItemStack();
        BlockPos pos = event.getPos();
        Direction face = event.getFace();
        if (face == null) face = Direction.UP;
        String blockId = getBlockId(player.level(), pos);

        Channel existing = CHANNELS.get(player.getUUID());
        if (existing != null && existing.pos.equals(pos)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }

        if (player.isCrouching() && stack.isEmpty() && isTentBlock(blockId)) {
            startChannel(player, "pickup", pos, blockId, stack, event.getHand(), face, true);
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }

        if (isTentItem(stack) && !isInteractiveBlock(player.level().getBlockState(pos))) {
            startChannel(player, "place", pos, blockId, stack, event.getHand(), face, true);
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getSide().isClient()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ItemStack stack = event.getItemStack();
        if (!isTentItem(stack)) return;

        Channel existing = CHANNELS.get(player.getUUID());
        if (existing != null && existing.type.equals("place")) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);

        HitResult hit = player.pick(4.5, 1.0f, false);
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bhr) {
            String blockId = getBlockId(player.level(), bhr.getBlockPos());
            startChannel(player, "place", bhr.getBlockPos(), blockId, stack, event.getHand(), bhr.getDirection(), true);
        } else {
            startChannel(player, "place", player.blockPosition(), "", stack, event.getHand(), Direction.UP, false);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || CHANNELS.isEmpty()) return;

        Iterator<Map.Entry<UUID, Channel>> it = CHANNELS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Channel> entry = it.next();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                cleanupChannel(entry.getValue());
                it.remove();
                continue;
            }
            if (!tickChannel(player, entry.getValue())) continue;
            cleanupChannel(entry.getValue());
            it.remove();
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() != null) {
            cleanupChannel(CHANNELS.remove(event.getEntity().getUUID()));
        }
    }

    private static boolean tickChannel(ServerPlayer player, Channel c) {
        if (player.isRemoved()) return true;
        if (!player.level().dimension().equals(c.dimension)) {
            cancel(player, "目标发生变化，读条中断！");
            return true;
        }
        long tick = player.server.getTickCount();
        long elapsed = tick - c.startTick;
        if (elapsed < 0L || elapsed > MAX_TICKS) return true;

        if (c.type.equals("pickup")) {
            if (!player.isCrouching()) {
                cancel(player, "松开潜行，读条中断！");
                return true;
            }
            if (!player.getItemInHand(c.hand).isEmpty()) {
                cancel(player, "手里有东西，读条中断！");
                return true;
            }
        } else if (!isTentItem(player.getItemInHand(c.hand))) {
            cancel(player, "手里的帐篷变了，读条中断！");
            return true;
        }

        if (c.hasTarget) {
            double maxDist = c.type.equals("pickup") ? PICKUP_DISTANCE : PLACE_DISTANCE;
            Vec3 center = Vec3.atCenterOf(c.pos);
            if (player.distanceToSqr(center) > maxDist * maxDist) {
                cancel(player, "离开太远，读条中断！");
                return true;
            }
            if (!c.blockId.equals(getBlockId(player.level(), c.pos))) {
                cancel(player, "目标发生变化，读条中断！");
                return true;
            }
        }

        if (elapsed >= CHANNEL_TICKS) {
            finish(player, c);
            return true;
        }

        if (c.bossEvent != null) {
            int pct = (int) (elapsed * 100L / CHANNEL_TICKS);
            String label = c.type.equals("pickup") ? "正在收起帐篷" : "正在放下帐篷";
            c.bossEvent.setName(Component.literal(label + " " + pct + "%"));
            c.bossEvent.setProgress((float) elapsed / (float) CHANNEL_TICKS);
        }
        return false;
    }

    private static void finish(ServerPlayer player, Channel c) {
        Level level = player.level();
        InteractionHand hand = c.hand;
        try {
            if (c.type.equals("pickup")) {
                BlockState state = level.getBlockState(c.pos);
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(c.pos), c.face, c.pos, false);
                state.use(level, player, hand, hit);
                player.displayClientMessage(Component.literal("帐篷已收起！").withStyle(ChatFormatting.GREEN), true);
            } else {
                ItemStack stack = player.getItemInHand(hand);
                if (c.hasTarget) {
                    BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(c.pos), c.face, c.pos, false);
                    stack.useOn(new UseOnContext(level, player, hand, stack, hit));
                } else {
                    stack.use(level, player, hand);
                }
                player.displayClientMessage(Component.literal("帐篷已放下！").withStyle(ChatFormatting.GREEN), true);
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private static final class Channel {
        ResourceKey<Level> dimension;
        BlockPos pos;
        String blockId;
        String itemId;
        String type;
        InteractionHand hand;
        Direction face;
        long startTick;
        boolean hasTarget;
        ServerBossEvent bossEvent;
    }
}