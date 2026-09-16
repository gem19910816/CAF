package com.vehiclelock.mod.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Keychain: stores {UUID, Plate} entries from both official superbwarfare keys
 * and our legacy keys.
 * Right-click → absorb a key from inventory.
 * Sneak+right-click → restore last key as item.
 */
public class KeychainItem extends Item {
    private static final String TAG_KEYS = "Keys";
    private static final String TAG_U = "U";       // UUID
    private static final String TAG_P = "P";       // Plate

    public KeychainItem() {
        super(new Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResultHolder.success(player.getItemInHand(hand));
        ItemStack stack = player.getItemInHand(hand);
        if (player.isCrouching()) removeLastKey(player, stack);
        else addKeyFromInventory(player, stack);
        return InteractionResultHolder.success(stack);
    }

    private void addKeyFromInventory(Player player, ItemStack keychain) {
        List<Entry> entries = loadEntries(keychain);
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack slot = player.getInventory().getItem(i);
            if (slot.isEmpty() || !VehicleKeyHelper.isKeyItem(slot)) continue;

            UUID uuid = VehicleKeyHelper.getVehicleUuid(slot);
            String plate = VehicleKeyHelper.getVehiclePlate(slot);
            if (uuid == null) continue; // no VLUuid → can't identify → skip

            if (!contains(entries, uuid)) {
                slot.shrink(1);
                entries.add(new Entry(uuid, plate != null ? plate : "?"));
                saveEntries(keychain, entries);
                player.displayClientMessage(Component.literal("§a已放入钥匙串 [" + plate + "]"), true);
                player.playSound(SoundEvents.ARMOR_EQUIP_CHAIN, 1.0f, 1.0f);
                return;
            }
        }
        player.displayClientMessage(Component.literal("§c背包中没有可放入的钥匙"), true);
    }

    private void removeLastKey(Player player, ItemStack keychain) {
        List<Entry> entries = loadEntries(keychain);
        if (entries.isEmpty()) {
            player.displayClientMessage(Component.literal("§c钥匙串是空的！"), true);
            return;
        }
        Entry last = entries.remove(entries.size() - 1);
        saveEntries(keychain, entries);

        ItemStack key = VehicleKeyHelper.create(last.uuid, last.plate);
        if (!key.isEmpty()) {
            if (!player.addItem(key)) player.drop(key, false);
            player.displayClientMessage(Component.literal("§a取出了钥匙 [" + last.plate + "]"), true);
        } else {
            player.displayClientMessage(Component.literal("§c无法取出钥匙（模组未加载？）"), true);
        }
        player.playSound(SoundEvents.ARMOR_EQUIP_CHAIN, 1.0f, 1.0f);
    }

    /** 钥匙串里存的一把钥匙。 */
    public static final class Entry {
        public final UUID uuid;
        public final String plate;

        public Entry(UUID uuid, String plate) {
            this.uuid = uuid;
            this.plate = plate;
        }

        public UUID uuid() { return uuid; }
        public String plate() { return plate; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Entry)) return false;
            Entry other = (Entry) o;
            return this.uuid.equals(other.uuid);
        }
        @Override public int hashCode() { return uuid.hashCode(); }
    }

    private static boolean contains(List<Entry> entries, UUID uuid) {
        return entries.stream().anyMatch(e -> e.uuid.equals(uuid));
    }

    public static List<Entry> loadEntries(ItemStack stack) {
        List<Entry> result = new ArrayList<>();
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(TAG_KEYS)) {
            ListTag list = tag.getList(TAG_KEYS, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag e = list.getCompound(i);
                UUID uuid = e.hasUUID(TAG_U) ? e.getUUID(TAG_U) : null;
                if (uuid == null) continue;
                result.add(new Entry(uuid, e.getString(TAG_P)));
            }
        }
        return result;
    }

    private static void saveEntries(ItemStack stack, List<Entry> entries) {
        ListTag list = new ListTag();
        for (Entry e : entries) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID(TAG_U, e.uuid);
            tag.putString(TAG_P, e.plate);
            list.add(tag);
        }
        stack.getOrCreateTag().put(TAG_KEYS, list);
    }

    public static boolean containsVehicle(ItemStack keychain, UUID vehicleUuid) {
        return loadEntries(keychain).stream().anyMatch(e -> e.uuid.equals(vehicleUuid));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        List<Entry> entries = loadEntries(stack);
        if (entries.isEmpty()) {
            tooltip.add(Component.literal("§7[空钥匙串]"));
        } else {
            tooltip.add(Component.literal("§6共 " + entries.size() + " 把钥匙:"));
            for (int i = 0; i < Math.min(entries.size(), 8); i++) {
                Entry e = entries.get(i);
                tooltip.add(Component.literal("§7  §8- §7[" + e.plate + "]"));
            }
            if (entries.size() > 8) {
                tooltip.add(Component.literal("§7  ...还有 " + (entries.size() - 8) + " 把"));
            }
        }
    }
}