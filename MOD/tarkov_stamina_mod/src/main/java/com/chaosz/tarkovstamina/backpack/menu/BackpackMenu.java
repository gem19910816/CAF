package com.chaosz.tarkovstamina.backpack.menu;

import com.chaosz.tarkovstamina.backpack.BackpackRegistration;
import com.chaosz.tarkovstamina.backpack.item.MilitaryBackpackItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

/** Server-authoritative CAF backpack menu. Military backpack is 12x9 (wide);
 *  satchel/school/hiking packs are 9 columns wide with 3/4/5 rows. */
public class BackpackMenu extends AbstractContainerMenu {
    public static final int COLUMNS = 9;
    public static final int MILITARY_COLUMNS = 12;
    public static final int MILITARY_ROWS = 9;

    private final ItemStackHandler handler;
    private final ItemStack stack;
    private final Player owner;
    private final BackpackContainer backpack;
    private final int columns;
    private final int rows;
    private final int visibleSlots;

    public BackpackMenu(int id, Inventory inventory, FriendlyByteBuf buf) {
        this(id, inventory, new ItemStackHandler(MilitaryBackpackItem.SLOT_COUNT), ItemStack.EMPTY,
                buf.readVarInt(), buf.readVarInt());
    }

    public BackpackMenu(int id, Inventory inventory, ItemStackHandler handler, ItemStack stack,
                        int columns, int rows) {
        super(BackpackRegistration.BACKPACK_MENU.get(), id);
        this.handler = handler;
        this.stack = stack;
        this.owner = inventory.player;
        this.columns = columns;
        this.rows = rows;
        this.visibleSlots = columns * rows;
        this.backpack = new BackpackContainer(handler);

        boolean wide = columns == MILITARY_COLUMNS;
        int slotX = wide ? 16 : 8;
        int slotY = wide ? 25 : 18;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                int index = row * columns + col;
                this.addSlot(new Slot(backpack, index, slotX + col * 18, slotY + row * 18) {
                    @Override
                    public boolean mayPlace(ItemStack candidate) {
                        return !(candidate.getItem() instanceof MilitaryBackpackItem);
                    }

                    @Override
                    public int getMaxStackSize(ItemStack candidate) {
                        return backpack.slotLimit(index);
                    }
                });
            }
        }

        int playerX = wide ? 43 : 8;
        // 原版 generic_54: 玩家背包区 y=126 开始, 格子在 +13
        int invY = slotY + rows * 18 + 13;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(inventory, col + row * 9 + 9,
                        playerX + col * 18, invY + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(inventory, col, playerX + col * 18, invY + 58));
        }
    }

    public int getColumns() {
        return columns;
    }

    public int getRows() {
        return rows;
    }

    /** Kept for packet compatibility with older clients; the grid never scrolls. */
    public void setBackpackScroll(int offset) {
    }

    public int getBackpackScroll() {
        return 0;
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (owner instanceof ServerPlayer serverPlayer) {
            ItemStack actual = MilitaryBackpackItem.resolveCarried(serverPlayer, stack);
            if (!actual.isEmpty()) MilitaryBackpackItem.saveHandler(actual, handler);
        }
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (!player.level().isClientSide() && player instanceof ServerPlayer serverPlayer) {
            ItemStack actual = MilitaryBackpackItem.resolveCarried(serverPlayer, stack);
            if (!actual.isEmpty()) MilitaryBackpackItem.saveHandler(actual, handler);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stackInSlot = slot.getItem();
            result = stackInSlot.copy();
            if (index < visibleSlots) {
                if (!this.moveItemStackTo(stackInSlot, visibleSlots, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                if (stackInSlot.getItem() instanceof MilitaryBackpackItem
                        || !this.moveItemStackTo(stackInSlot, 0, visibleSlots, false)) {
                    return ItemStack.EMPTY;
                }
            }
            if (stackInSlot.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        return player instanceof ServerPlayer serverPlayer
                && serverPlayer.isAlive()
                && MilitaryBackpackItem.isCarriedBy(serverPlayer, stack);
    }

    public ItemStackHandler getHandler() {
        return handler;
    }

    private final class BackpackContainer implements Container {
        private final ItemStackHandler handler;

        private BackpackContainer(ItemStackHandler handler) {
            this.handler = handler;
        }

        private int slotLimit(int index) {
            return index >= 0 && index < handler.getSlots()
                    ? handler.getSlotLimit(index) : 64;
        }

        @Override
        public int getContainerSize() {
            return visibleSlots;
        }

        @Override
        public boolean isEmpty() {
            for (int i = 0; i < getContainerSize(); i++) {
                if (!getItem(i).isEmpty()) return false;
            }
            return true;
        }

        @Override
        public ItemStack getItem(int index) {
            return valid(index) ? handler.getStackInSlot(index) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItem(int index, int amount) {
            return valid(index) ? handler.extractItem(index, amount, false) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItemNoUpdate(int index) {
            if (!valid(index)) return ItemStack.EMPTY;
            ItemStack existing = handler.getStackInSlot(index);
            handler.setStackInSlot(index, ItemStack.EMPTY);
            return existing;
        }

        @Override
        public void setItem(int index, ItemStack stack) {
            if (valid(index)) {
                handler.setStackInSlot(index, stack);
            }
        }

        @Override
        public void setChanged() {
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void clearContent() {
            for (int i = 0; i < handler.getSlots(); i++) {
                handler.setStackInSlot(i, ItemStack.EMPTY);
            }
        }

        private boolean valid(int index) {
            return index >= 0 && index < visibleSlots && index < handler.getSlots();
        }
    }
}
