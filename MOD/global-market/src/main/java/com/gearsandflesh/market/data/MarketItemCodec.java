package com.gearsandflesh.market.data;

import com.gearsandflesh.market.MarketConstants;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Market-specific ItemStack serialization and validation.
 *
 * The quantity travels separately from FriendlyByteBuf#writeItem. This keeps
 * the market protocol explicit and prevents a byte-sized vanilla count from
 * truncating a BiggerStacks quantity if a platform patch ever regresses.
 */
public final class MarketItemCodec {
    private static final int NETWORK_BUFFER_SLACK = 1_024;

    private MarketItemCodec() {
    }

    public static void write(FriendlyByteBuf buffer, ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getCount() <= 0) {
            throw new IllegalArgumentException("Cannot encode an empty market item");
        }
        int quantity = stack.getCount();
        ItemStack template = stack.copy();
        template.setCount(1);
        buffer.writeItem(template);
        buffer.writeVarInt(quantity);
    }

    public static ItemStack read(FriendlyByteBuf buffer) {
        ItemStack stack = buffer.readItem();
        int quantity = buffer.readVarInt();
        if (stack.isEmpty() || quantity <= 0) {
            throw new DecoderException("Invalid market item quantity: " + quantity);
        }
        stack.setCount(quantity);
        return stack;
    }

    /**
     * Returns null when the stack is safe to escrow, persist, and send. The
     * error text is intentionally suitable for a player-facing rejection.
     */
    public static String validationProblem(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getCount() <= 0) {
            return "物品数量无效";
        }
        int maxStackSize = stack.getMaxStackSize();
        if (maxStackSize <= 0 || stack.getCount() > maxStackSize) {
            return "物品数量超过当前堆叠上限";
        }

        CompoundTag saved;
        try {
            saved = stack.save(new CompoundTag());
        } catch (RuntimeException failure) {
            return "物品数据无法序列化";
        }

        if (!fitsUncompressedNbtLimit(saved)) {
            return "物品未压缩数据超过 128 KiB，不能上架";
        }

        ItemStack persisted;
        try {
            persisted = ItemStack.of(saved);
        } catch (RuntimeException failure) {
            return "物品数据无法恢复";
        }
        if (!sameItemAndCount(stack, persisted)
                || !ItemStack.isSameItemSameTags(stack, persisted)) {
            return "物品数量无法通过 BigCount/NBT 安全保存";
        }

        return marketNetworkRoundTripProblem(stack);
    }

    /**
     * Verifies the raw platform ItemStack codec used by inventory sync. A
     * count above 127 specifically exercises BiggerStacks' int network patch.
     */
    public static String platformCountRoundTripProblem(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getCount() <= 0) {
            return "物品数量无效";
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(64, 4_096));
        try {
            buffer.writeItem(stack);
            buffer.readerIndex(0);
            ItemStack decoded = buffer.readItem();
            if (!sameItemAndCount(stack, decoded)) {
                return "BiggerStacks 的原生网络数量编码未生效";
            }
            return null;
        } catch (RuntimeException failure) {
            return "BiggerStacks 的原生网络数量编码不可用";
        } finally {
            buffer.release();
        }
    }

    public static int uncompressedPersistentBytes(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return -1;
        }
        CountingOutputStream output = new CountingOutputStream(Integer.MAX_VALUE);
        try (DataOutputStream dataOutput = new DataOutputStream(output)) {
            NbtIo.write(stack.save(new CompoundTag()), dataOutput);
            return output.written();
        } catch (IOException | RuntimeException failure) {
            return -1;
        }
    }

    private static boolean fitsUncompressedNbtLimit(CompoundTag tag) {
        try (DataOutputStream output = new DataOutputStream(
                new CountingOutputStream(MarketConstants.MAX_ITEM_NBT_BYTES))) {
            NbtIo.write(tag, output);
            return true;
        } catch (SizeLimitException tooLarge) {
            return false;
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private static String marketNetworkRoundTripProblem(ItemStack stack) {
        int maximumCapacity = MarketConstants.MAX_ITEM_NBT_BYTES + NETWORK_BUFFER_SLACK;
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(256, maximumCapacity));
        try {
            write(buffer, stack);
            if (buffer.readableBytes() > MarketConstants.MAX_ITEM_NBT_BYTES) {
                return "物品网络数据超过 128 KiB，不能上架";
            }
            buffer.readerIndex(0);
            ItemStack decoded = read(buffer);
            if (!sameItemAndCount(stack, decoded)) {
                return "物品数量无法通过市场网络协议安全传输";
            }
            return null;
        } catch (IndexOutOfBoundsException tooLarge) {
            return "物品网络数据超过 128 KiB，不能上架";
        } catch (RuntimeException failure) {
            return "物品网络数据无法安全传输";
        } finally {
            buffer.release();
        }
    }

    private static boolean sameItemAndCount(ItemStack expected, ItemStack actual) {
        return actual != null
                && !actual.isEmpty()
                && expected.getItem() == actual.getItem()
                && expected.getCount() == actual.getCount();
    }

    private static class CountingOutputStream extends OutputStream {
        private final int limit;
        private int written;

        private CountingOutputStream(int limit) {
            this.limit = limit;
        }

        int written() {
            return written;
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            written++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (offset < 0 || length < 0 || offset > bytes.length - length) {
                throw new IndexOutOfBoundsException();
            }
            ensureCapacity(length);
            written += length;
        }

        private void ensureCapacity(int additional) throws SizeLimitException {
            if (additional < 0 || additional > limit - written) {
                throw new SizeLimitException();
            }
        }
    }

    private static final class SizeLimitException extends IOException {
    }
}
