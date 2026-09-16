package com.vehiclelock.mod.handler;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.vehiclelock.mod.VehicleLockMod;
import com.vehiclelock.mod.item.KeychainItem;
import com.vehiclelock.mod.item.VehicleKeyHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Vehicle lock adapted to work with the official superbwarfare key item.
 * - Vehicle claim: UUID + license plate, stored on the vehicle; first driver gets an
 *   official superbwarfare:vehicle_key carrying our VLUuid/VLPlate NBT.
 * - Boarding: enforced via EntityMountEvent (cancel). Requires matching key,
 *   an owner grant, or an unclaimed vehicle.
 * - Owner approves requesters by pressing Y (client sends a packet).
 * - Engine still requires the key.
 */
@Mod.EventBusSubscriber(modid = VehicleLockMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class VehicleLockHandler {

    private static final String TAG_UUID = "VehicleLockUUID";
    private static final String TAG_PLATE = "VehicleLockPlate";
    private static final String TAG_OWNER = "VehicleLockOwner";

    private static final long REQUEST_TIMEOUT_MS = 90000;
    private static final long GRANT_DURATION_MS = 60000;

    private static final Map<UUID, List<PendingRequester>> PENDING = new HashMap<>();
    private static final Map<UUID, Map<UUID, Long>> GRANTS = new HashMap<>();

    private record PendingRequester(UUID playerUuid, long time) {}

    // ================= Engine lock (driver seat tick) =================

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Player player = event.player;
        if (player.level().isClientSide()) return;
        if (!player.isPassenger()) return;

        Entity vehicle = player.getVehicle();
        if (!(vehicle instanceof VehicleEntity ve)) return;
        if (!isDriver(ve, player)) return;

        CompoundTag vd = ve.getPersistentData();

        // ---- First driver claims this vehicle ----
        if (!vd.hasUUID(TAG_UUID)) {
            UUID newUuid = UUID.randomUUID();
            String type = ve.getType().builtInRegistryHolder().key().location().toString();
            String plate = genPlate(type, newUuid);
            vd.putUUID(TAG_UUID, newUuid);
            vd.putString(TAG_PLATE, plate);
            vd.putUUID(TAG_OWNER, player.getUUID());

            if (player.level() instanceof ServerLevel serverLevel) {
                VehicleLockData.get(serverLevel).register(plate, newUuid, player.getUUID(), type);
            }

            // Give an official superbwarfare key carrying our VLUuid/VLPlate
            ItemStack key = VehicleKeyHelper.create(newUuid, plate);
            if (!key.isEmpty()) {
                if (!player.addItem(key)) player.drop(key, false);
                player.displayClientMessage(
                        Component.literal("§a§l[车锁] §a你获得了这辆车的钥匙，车牌 " + plate + "！"),
                        false);
                player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            }
            return;
        }

        // ---- Already claimed: enforce key ----
        UUID vehicleUuid = vd.getUUID(TAG_UUID);
        if (hasKey(player, vehicleUuid)) return;

        ve.setPower(0);
        ve.setEngineStart(false);
        ve.setDeltaMovement(0, 0, 0);

        String plate = vd.getString(TAG_PLATE);
        player.displayClientMessage(
                Component.literal("§c§l[车锁] §c需要 " + plate + " 的钥匙才能启动！"),
                true);
    }

    // ================= Boarding enforcement =================

    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (!event.isMounting() || event.isCanceled()) return;
        Entity rider = event.getEntityMounting();
        Entity vehicle = event.getEntityBeingMounted();
        if (!(rider instanceof Player player) || !(vehicle instanceof VehicleEntity ve)) return;
        if (player.level().isClientSide()) return;

        CompoundTag vd = ve.getPersistentData();
        if (!vd.hasUUID(TAG_UUID)) return; // unclaimed → allow

        UUID vehicleUuid = vd.getUUID(TAG_UUID);
        if (hasKey(player, vehicleUuid)) return;      // has key → allow
        if (hasGrant(player.getUUID(), vehicleUuid)) return; // owner grant → allow

        event.setCanceled(true);
        requestApproval(ve, player);
    }

    // ================= Interaction: plate display, key verify =================

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        Entity target = event.getTarget();
        if (!(target instanceof VehicleEntity ve)) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;

        CompoundTag vd = ve.getPersistentData();
        ItemStack hand = player.getMainHandItem();

        // Sneak + empty hand → show plate
        if (player.isShiftKeyDown() && hand.isEmpty()) {
            if (vd.hasUUID(TAG_UUID)) {
                player.displayClientMessage(
                        Component.literal("§e[车锁] 车牌号: §f" + vd.getString(TAG_PLATE)),
                        true);
            } else {
                player.displayClientMessage(
                        Component.literal("§e[车锁] 这辆车还没有牌照，坐上驾驶位即可认领"),
                        true);
            }
            event.setCanceled(true);
            return;
        }

        // Key in hand → verify (never board)
        if (VehicleKeyHelper.isKeyItem(hand)) {
            if (!vd.hasUUID(TAG_UUID)) {
                player.displayClientMessage(
                        Component.literal("§e[车锁] 这辆车还没有被认领，坐上驾驶位即可获得钥匙"),
                        true);
            } else {
                UUID vehicleUuid = vd.getUUID(TAG_UUID);
                String plate = vd.getString(TAG_PLATE);
                String shortType = VehicleKeyHelper.shortType(ve.getType().builtInRegistryHolder().key().location().toString());
                if (VehicleKeyHelper.matches(hand, vehicleUuid)) {
                    player.displayClientMessage(
                            Component.literal("§a[车锁] 匹配成功！这是 " + plate + " 的钥匙"),
                            true);
                } else {
                    player.displayClientMessage(
                            Component.literal("§c[车锁] 钥匙不匹配，这辆车是 " + shortType + " 车牌 " + plate),
                            true);
                }
            }
            event.setCanceled(true);
            return;
        }
    }

    // ================= Approval request system =================

    /** Server-side handler when owner presses Y. */
    public static void onApproveKeyPressed(ServerPlayer owner) {
        long now = System.currentTimeMillis();
        int approved = 0;

        Iterator<Map.Entry<UUID, List<PendingRequester>>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, List<PendingRequester>> entry = it.next();
            UUID vehicleUuid = entry.getKey();

            if (!ownsVehicle(owner, vehicleUuid)) continue;

            List<PendingRequester> list = entry.getValue();
            list.removeIf(p -> now - p.time() > REQUEST_TIMEOUT_MS);
            if (list.isEmpty()) {
                it.remove();
                continue;
            }

            Map<UUID, Long> grants = GRANTS.computeIfAbsent(vehicleUuid, k -> new HashMap<>());
            for (PendingRequester pending : list) {
                grants.put(pending.playerUuid(), now + GRANT_DURATION_MS);
                ServerPlayer requester = owner.server.getPlayerList().getPlayer(pending.playerUuid());
                if (requester != null) {
                    requester.displayClientMessage(
                            Component.literal("§a[车锁] 车主已批准你上车！(60秒内有效)"),
                            true);
                }
                approved++;
            }
            list.clear();
            it.remove();
        }

        if (approved > 0) {
            owner.displayClientMessage(
                    Component.literal("§a[车锁] 已批准 " + approved + " 名玩家上车"),
                    true);
        } else {
            owner.displayClientMessage(
                    Component.literal("§7[车锁] 当前没有待批准的请求"),
                    true);
        }
    }

    /** Check whether the player owns the vehicle, via the world registry. */
    private static boolean ownsVehicle(ServerPlayer owner, UUID vehicleUuid) {
        ServerLevel overworld = owner.server.overworld();
        if (overworld == null) return false;
        VehicleLockData data = VehicleLockData.get(overworld);
        for (Map.Entry<String, VehicleLockData.Entry> e : data.entries()) {
            VehicleLockData.Entry entry = e.getValue();
            if (entry.vehicleUuid().equals(vehicleUuid)
                    && entry.ownerUuid() != null
                    && entry.ownerUuid().equals(owner.getUUID())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasGrant(UUID playerUuid, UUID vehicleUuid) {
        Map<UUID, Long> grants = GRANTS.get(vehicleUuid);
        if (grants == null) return false;
        Long expiry = grants.get(playerUuid);
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) {
            grants.remove(playerUuid);
            return false;
        }
        return true;
    }

    private static void requestApproval(VehicleEntity ve, Player player) {
        CompoundTag vd = ve.getPersistentData();
        UUID vehicleUuid = vd.getUUID(TAG_UUID);
        UUID ownerUuid = vd.hasUUID(TAG_OWNER) ? vd.getUUID(TAG_OWNER) : null;
        String plate = vd.getString(TAG_PLATE);

        if (ownerUuid == null) return;

        if (ownerUuid.equals(player.getUUID())) {
            player.displayClientMessage(
                    Component.literal("§c[车锁] 你需要自己的钥匙才能上车！"),
                    true);
            return;
        }

        MinecraftServer server = player.level().getServer();
        ServerPlayer owner = server != null ? server.getPlayerList().getPlayer(ownerUuid) : null;

        if (owner == null) {
            player.displayClientMessage(
                    Component.literal("§c[车锁] 车主不在线，无法请求上车"),
                    true);
            return;
        }

        PENDING.computeIfAbsent(vehicleUuid, k -> new ArrayList<>())
                .add(new PendingRequester(player.getUUID(), System.currentTimeMillis()));

        player.displayClientMessage(
                Component.literal("§e[车锁] 已向车主发送请求，等待批准..."),
                true);
        owner.displayClientMessage(
                Component.literal("§e[车锁] §f" + player.getName().getString()
                        + " §e请求乘坐你的 §f" + plate + "§e，按 Y 批准"),
                true);
        owner.playSound(SoundEvents.NOTE_BLOCK_BELL.value(), 1.0f, 1.0f);
    }

    // ================= Helpers =================

    private static boolean isDriver(VehicleEntity ve, Player player) {
        var passengers = ve.getOrderedPassengers();
        if (!passengers.isEmpty() && passengers.get(0) == player) return true;
        Entity controlling = ve.getControllingPassenger();
        return controlling == player;
    }

    private static boolean hasKey(Player player, UUID vehicleUuid) {
        if (VehicleKeyHelper.matches(player.getMainHandItem(), vehicleUuid)) return true;
        if (VehicleKeyHelper.matches(player.getOffhandItem(), vehicleUuid)) return true;

        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            if (VehicleKeyHelper.matches(stack, vehicleUuid)) return true;
            if (stack.getItem() instanceof KeychainItem && KeychainItem.containsVehicle(stack, vehicleUuid)) return true;
        }
        return false;
    }

    /** License plate: e.g. "M1A2-4821". */
    private static String genPlate(String vehicleType, UUID uuid) {
        String raw = vehicleType.contains(":") ? vehicleType.split(":")[1] : vehicleType;
        StringBuilder abbr = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (c >= 'a' && c <= 'z') abbr.append((char) (c - 32));
            else if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) abbr.append(c);
        }
        if (abbr.length() > 6) abbr.setLength(6);
        if (abbr.length() == 0) abbr.append("VHC");

        long h = uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
        int num = (int) (Math.abs(h) % 9000) + 1000;
        return abbr + "-" + num;
    }
}