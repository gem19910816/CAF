package com.chaosz.gunpackfilter.client;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一个筛选按钮对应一个枪包（tacz 目录下的一个文件夹 / 一个 zip）。
 *
 * <p>一个枪包可能包含多个资源命名空间（例如 [TaCZ]Warzone 里有 bo6 / bo7 / jak / nmw1 / nmw2 / nmw3 / wz），
 * 所以筛选条件是一组命名空间。命名空间的归属由 {@link GunPackIndex} 保证唯一，
 * 不会出现两个按钮点出来内容一样的情况。</p>
 */
public class PackFilter {

    private final String packName;
    private final Set<String> namespaces;
    private final Component displayName;

    /** 按钮图标，取该枪包在页签里出现过的第一个物品。 */
    private ItemStack icon = ItemStack.EMPTY;

    /** 是否被点中（点中即只看这个枪包）。 */
    private boolean selected = false;

    public PackFilter(String packName, Set<String> namespaces) {
        this.packName = packName;
        this.namespaces = Collections.unmodifiableSet(new LinkedHashSet<>(namespaces));
        this.displayName = Component.literal(packName);
    }

    public String getPackName() {
        return packName;
    }

    public Set<String> getNamespaces() {
        return namespaces;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public ItemStack getIcon() {
        return icon;
    }

    public Component getTooltip() {
        return Component.empty()
                .append(displayName.copy().withStyle(ChatFormatting.WHITE))
                .append(Component.literal("\n"))
                .append(Component.literal(String.join(", ", namespaces)).withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("\n"))
                .append(Component.translatable("gunpackfilter.tooltip.toggle").withStyle(ChatFormatting.GRAY));
    }

    /**
     * 从给定物品里挑出属于本枪包的，顺便更新按钮图标。
     *
     * <p>只有匹配到东西时才更新图标，这样切到没有本枪包物品的页签时按钮不会变成空白。</p>
     */
    public List<ItemStack> getItems(Iterable<ItemStack> input) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack stack : input) {
            String namespace = GunPackFilterManager.packNamespaceOf(stack);
            if (namespace != null && this.namespaces.contains(namespace)) {
                result.add(stack);
            }
        }
        if (!result.isEmpty()) {
            this.icon = result.get(0);
        }
        return result;
    }
}
