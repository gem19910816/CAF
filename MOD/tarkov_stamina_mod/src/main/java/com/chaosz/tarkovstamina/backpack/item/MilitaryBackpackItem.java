package com.chaosz.tarkovstamina.backpack.item;

import com.chaosz.tarkovstamina.backpack.menu.BackpackMenu;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.CuriosCapability;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurio;

import java.util.List;
import java.util.UUID;

public class MilitaryBackpackItem extends Item {
    /** Full-size CAF storage: twelve columns by nine rows. */
    public static final int SLOT_COUNT = 108;
    private static final String BACKPACK_ID = "BackpackId";

    private final int columns;
    private final int rows;

    public MilitaryBackpackItem(Properties properties) {
        this(properties, BackpackMenu.MILITARY_COLUMNS, BackpackMenu.MILITARY_ROWS);
    }

    public MilitaryBackpackItem(Properties properties, int columns, int rows) {
        super(properties);
        this.columns = columns;
        this.rows = rows;
    }

    public int getColumns() {
        return columns;
    }

    public int getRows() {
        return rows;
    }

    public int getSlotCount() {
        return columns * rows;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            ensureId(stack);
            // Right-click is an open action only. Curios can still equip the item manually.
            openMenu(serverPlayer, stack);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    public static void openMenu(ServerPlayer player, ItemStack stack) {
        ensureId(stack);
        if (!(stack.getItem() instanceof MilitaryBackpackItem backpack)) return;
        net.minecraftforge.network.NetworkHooks.openScreen(player, new net.minecraft.world.MenuProvider() {
            @Override
            public Component getDisplayName() {
                return stack.getHoverName();
            }

            @Nullable
            @Override
            public net.minecraft.world.inventory.AbstractContainerMenu createMenu(
                    int id, net.minecraft.world.entity.player.Inventory inventory, Player p) {
                ItemStackHandler handler = createHandler(stack, backpack.getSlotCount());
                // Normalize legacy NBT to the current fixed format
                // as soon as the menu opens, before any client interaction.
                saveHandler(stack, handler);
                return new BackpackMenu(id, inventory, handler, stack,
                        backpack.getColumns(), backpack.getRows());
            }
        }, buf -> {
            buf.writeVarInt(backpack.getColumns());
            buf.writeVarInt(backpack.getRows());
        });
    }

    public static ItemStackHandler createHandler(ItemStack stack, int slotCount) {
        var handler = new ItemStackHandler(slotCount);
        if (stack.hasTag() && stack.getTag().contains("Inventory", Tag.TAG_COMPOUND)) {
            // Never call ItemStackHandler.deserializeNBT here: older backpacks
            // stored a 45-slot Size tag, and that method resizes the handler.
            CompoundTag inventory = stack.getTag().getCompound("Inventory");
            if (inventory.contains("Items", Tag.TAG_LIST)) {
                ListTag items = inventory.getList("Items", Tag.TAG_COMPOUND);
                for (int i = 0; i < items.size(); i++) {
                    CompoundTag entry = items.getCompound(i);
                    int slot = entry.getInt("Slot");
                    if (slot >= 0 && slot < slotCount) {
                        handler.setStackInSlot(slot, ItemStack.of(entry));
                    }
                }
            }
        }
        return handler;
    }

    public static void saveHandler(ItemStack stack, ItemStackHandler handler) {
        stack.getOrCreateTag().put("Inventory", handler.serializeNBT());
    }

    public static void ensureId(ItemStack stack) {
        if (!stack.isEmpty() && (!stack.hasTag() || !stack.getTag().hasUUID(BACKPACK_ID))) {
            stack.getOrCreateTag().putUUID(BACKPACK_ID, UUID.randomUUID());
        }
    }

    public static ItemStack resolveCarried(ServerPlayer player, ItemStack target) {
        ensureId(target);
        if (sameId(player.getMainHandItem(), target)) return player.getMainHandItem();
        if (sameId(player.getOffhandItem(), target)) return player.getOffhandItem();
        return CuriosApi.getCuriosInventory(player).resolve()
                .flatMap(inv -> inv.findFirstCurio(stack -> sameId(stack, target)))
                .map(top.theillusivec4.curios.api.SlotResult::stack)
                .orElse(ItemStack.EMPTY);
    }

    private static boolean sameId(ItemStack a, ItemStack b) {
        return !a.isEmpty() && !b.isEmpty() && a.hasTag() && b.hasTag()
                && a.getTag().hasUUID(BACKPACK_ID) && b.getTag().hasUUID(BACKPACK_ID)
                && a.getTag().getUUID(BACKPACK_ID).equals(b.getTag().getUUID(BACKPACK_ID));
    }

    /** The server must close the menu once the backing stack leaves its slot. */
    public static boolean isCarriedBy(ServerPlayer player, ItemStack target) {
        return !resolveCarried(player, target).isEmpty();
    }

    @Override
    public @Nullable ICapabilityProvider initCapabilities(ItemStack stack, @Nullable net.minecraft.nbt.CompoundTag nbt) {
        return new ICapabilityProvider() {
            @Override
            public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
                if (cap == CuriosCapability.ITEM) {
                    return LazyOptional.of(() -> (ICurio) new ICurio() {
                        @Override
                        public ItemStack getStack() { return stack; }

                        @Override
                        public boolean canEquip(SlotContext slotContext) {
                            return "back".equals(slotContext.identifier());
                        }

                        @Override
                        public boolean canEquipFromUse(SlotContext slotContext) {
                            return false;
                        }

                        @Override
                        public boolean canUnequip(SlotContext slotContext) {
                            return true;
                        }
                    }).cast();
                }
                return LazyOptional.empty();
            }
        };
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltipComponents, TooltipFlag isAdvanced) {
        super.appendHoverText(stack, level, tooltipComponents, isAdvanced);
        tooltipComponents.add(Component.translatable("tooltip.caf.backpack.slots", getSlotCount()));
        tooltipComponents.add(Component.translatable("tooltip.caf.backpack.curios"));
    }
}
