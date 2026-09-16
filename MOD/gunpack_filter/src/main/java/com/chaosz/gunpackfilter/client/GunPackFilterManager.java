package com.chaosz.gunpackfilter.client;

import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.init.ModCreativeTabs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.CreativeModeTabRegistry;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.RegistryObject;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 创造模式物品栏左侧的枪包筛选按钮列。
 *
 * <p>一个按钮 = 一个枪包。交互是「点一下筛选」：
 * <ul>
 *   <li>默认一个都不选中 → 不过滤，和原版表现一致</li>
 *   <li>点中某个枪包 → 只显示这个枪包的枪械 / 配件 / 弹药</li>
 *   <li>再点几个 → 同时显示这几个枪包</li>
 *   <li>全部点掉 → 恢复显示全部</li>
 * </ul>
 *
 * <p>按钮外观、页签贴图画法、翻页箭头布局都照搬 Arcana 的 CreativeTabManager；
 * 只显示「当前页签真的有东西」的枪包（Arcana 的 validButtons 逻辑），
 * 已经被点中的枪包即使本页签没有东西也保持可见，方便再点掉。</p>
 */
public final class GunPackFilterManager {

    public static final GunPackFilterManager INSTANCE = new GunPackFilterManager();

    /** 一屏最多显示几个枪包按钮 */
    private static final int VISIBLE_COUNT = 4;
    private static final int BUTTON_PITCH = 29;
    private static final int BUTTON_Y_BASE = 11;
    /** 按钮 x = guiLeft - 28 */
    private static final int BUTTON_X_OFFSET = 28;
    /** 翻页箭头 x = guiLeft - 22 */
    private static final int SCROLL_X_OFFSET = 22;
    private static final int SCROLL_UP_Y_OFFSET = -12;
    private static final int SCROLL_DOWN_Y_OFFSET = 127;
    /** TaCZ 的枪包目录名，相对游戏目录。 */
    private static final String PACK_ROOT = "tacz";

    private final List<PackFilter> filters = new ArrayList<>();
    private final List<PackFilterButton> buttons = new ArrayList<>();
    private final List<PackFilterButton> visibleButtons = new ArrayList<>();
    /** 枪包名 → 是否被点中。 */
    private final Map<String, Boolean> selection = new HashMap<>();
    /** 需要挂筛选按钮的创造页签。 */
    private final Set<CreativeModeTab> filterableTabs = new LinkedHashSet<>();

    private ScrollIconButton scrollUp;
    private ScrollIconButton scrollDown;

    private CreativeModeTab currentTab;
    private int startIndex = 0;
    private int guiLeft = 0;
    private int guiTop = 0;
    private boolean rendering = false;

    private GunPackFilterManager() {
    }

    // ------------------------------------------------------------------
    // 事件入口
    // ------------------------------------------------------------------

    public void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof CreativeModeInventoryScreen screen)) {
            this.rendering = false;
            return;
        }

        this.createFilters();

        this.guiLeft = screen.getGuiLeft();
        this.guiTop = screen.getGuiTop();
        this.buttons.clear();
        this.visibleButtons.clear();

        for (PackFilter filter : this.filters) {
            PackFilterButton button = new PackFilterButton(
                    this.guiLeft - BUTTON_X_OFFSET, this.guiTop, filter, this::onFilterToggled);
            button.visible = false;
            this.buttons.add(button);
            event.addListener(button);
        }

        this.scrollUp = new ScrollIconButton(this.guiLeft - SCROLL_X_OFFSET,
                this.guiTop + SCROLL_UP_Y_OFFSET, 0,
                Component.translatable("gunpackfilter.tooltip.scroll_up"), button -> {
            if (this.startIndex > 0) {
                this.startIndex--;
                this.updateFilterButtons();
            }
        });
        this.scrollUp.visible = false;
        event.addListener(this.scrollUp);

        this.scrollDown = new ScrollIconButton(this.guiLeft - SCROLL_X_OFFSET,
                this.guiTop + SCROLL_DOWN_Y_OFFSET, 16,
                Component.translatable("gunpackfilter.tooltip.scroll_down"), button -> {
            if (this.startIndex <= this.visibleButtons.size() - VISIBLE_COUNT - 1) {
                this.startIndex++;
                this.updateFilterButtons();
            }
        });
        this.scrollDown.visible = false;
        event.addListener(this.scrollDown);

        // 页面 init 时原版还没调用 selectTab，真正的同步在 selectTab 注入里做；
        // 这里先用已知页签同步一次，两次都是幂等的。
        if (this.currentTab != null) {
            this.onTabSelected(screen, this.currentTab);
        } else {
            this.setRendering(false);
        }
    }

    public void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        if (!(event.getScreen() instanceof CreativeModeInventoryScreen screen)) {
            this.rendering = false;
            return;
        }
        int left = screen.getGuiLeft();
        int top = screen.getGuiTop();
        if (left != this.guiLeft || top != this.guiTop) {
            this.guiLeft = left;
            this.guiTop = top;
            this.updateButtonLayout();
        }
    }

    public void onLogout() {
        this.rendering = false;
        this.currentTab = null;
        GunPackIndex.invalidate();
    }

    /** 由 Mixin 在 selectTab 之后调用。 */
    public void onTabSelected(CreativeModeInventoryScreen screen, CreativeModeTab tab) {
        this.currentTab = tab;
        this.guiLeft = screen.getGuiLeft();
        this.guiTop = screen.getGuiTop();

        if (this.filterableTabs.contains(tab)) {
            this.setRendering(true);
            this.startIndex = 0;
            this.updateFilterButtons();
            this.updateItems(screen);
        } else {
            this.setRendering(false);
        }
    }

    /** 由 Mixin 在原版重新填充物品列表之后调用。 */
    public void onItemsRepopulated(CreativeModeInventoryScreen screen) {
        this.updateItems(screen);
    }

    // ------------------------------------------------------------------
    // 核心逻辑
    // ------------------------------------------------------------------

    private void setRendering(boolean rendering) {
        this.rendering = rendering;
        if (this.scrollUp != null) {
            this.scrollUp.visible = rendering;
        }
        if (this.scrollDown != null) {
            this.scrollDown.visible = rendering;
        }
        if (!rendering) {
            for (PackFilterButton button : this.buttons) {
                button.visible = false;
            }
        }
    }

    /**
     * 摆放可见的按钮：当前页签里有该枪包物品的，或者已经被点中的。
     */
    private void updateFilterButtons() {
        for (PackFilterButton button : this.buttons) {
            button.visible = false;
        }
        this.visibleButtons.clear();

        Collection<ItemStack> items = this.currentTab == null ? null : this.currentTab.getDisplayItems();
        for (PackFilterButton button : this.buttons) {
            PackFilter filter = button.getFilter();
            boolean hasItems = items != null && !filter.getItems(items).isEmpty();
            if (hasItems || filter.isSelected()) {
                this.visibleButtons.add(button);
            }
        }

        for (int i = this.startIndex; i < this.startIndex + VISIBLE_COUNT && i < this.visibleButtons.size(); i++) {
            PackFilterButton button = this.visibleButtons.get(i);
            button.setY(this.guiTop + BUTTON_PITCH * (i - this.startIndex) + BUTTON_Y_BASE);
            button.visible = this.rendering;
        }

        if (this.scrollUp != null) {
            this.scrollUp.active = this.startIndex > 0;
        }
        if (this.scrollDown != null) {
            this.scrollDown.active = this.startIndex <= this.visibleButtons.size() - VISIBLE_COUNT - 1;
        }
    }

    /** 重排按钮位置（窗口缩放时用）。 */
    private void updateButtonLayout() {
        if (this.scrollUp != null) {
            this.scrollUp.setX(this.guiLeft - SCROLL_X_OFFSET);
            this.scrollUp.setY(this.guiTop + SCROLL_UP_Y_OFFSET);
        }
        if (this.scrollDown != null) {
            this.scrollDown.setX(this.guiLeft - SCROLL_X_OFFSET);
            this.scrollDown.setY(this.guiTop + SCROLL_DOWN_Y_OFFSET);
        }
        for (PackFilterButton button : this.buttons) {
            button.setX(this.guiLeft - BUTTON_X_OFFSET);
        }
        this.updateFilterButtons();
    }

    /**
     * 按点中的枪包重建物品列表。一个都没点中时不过滤，直接显示全部。
     */
    private void updateItems(CreativeModeInventoryScreen screen) {
        if (!this.rendering || this.currentTab == null) {
            return;
        }
        Collection<ItemStack> source = this.currentTab.getDisplayItems();
        if (source == null) {
            return;
        }

        // 顺便刷新每个枪包的按钮图标
        Set<String> enabledNamespaces = new LinkedHashSet<>();
        for (PackFilter filter : this.filters) {
            filter.getItems(source);
            if (filter.isSelected()) {
                enabledNamespaces.addAll(filter.getNamespaces());
            }
        }

        var menu = screen.getMenu();
        menu.items.clear();
        if (enabledNamespaces.isEmpty()) {
            menu.items.addAll(source);
        } else {
            for (ItemStack stack : source) {
                String namespace = packNamespaceOf(stack);
                // 不属于任何已装枪包的物品不受筛选影响
                if (namespace == null
                        || GunPackIndex.getOwnerOf(namespace) == null
                        || enabledNamespaces.contains(namespace)) {
                    menu.items.add(stack);
                }
            }
        }
        menu.items.sort(Comparator.comparingInt(stack -> Item.getId(stack.getItem())));
        menu.scrollTo(0.0F);
    }

    private void onFilterToggled() {
        for (PackFilterButton button : this.buttons) {
            this.selection.put(button.getFilter().getPackName(), button.getFilter().isSelected());
        }
        if (Minecraft.getInstance().screen instanceof CreativeModeInventoryScreen screen) {
            this.updateItems(screen);
        }
    }

    /** 物品属于哪个资源命名空间（枪械 / 配件 / 弹药优先，其次看注册名）。 */
    @Nullable
    public static String packNamespaceOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun != null) {
            ResourceLocation id = gun.getGunId(stack);
            if (id != null) {
                return id.getNamespace();
            }
        }
        IAttachment attachment = IAttachment.getIAttachmentOrNull(stack);
        if (attachment != null) {
            ResourceLocation id = attachment.getAttachmentId(stack);
            if (id != null) {
                return id.getNamespace();
            }
        }
        IAmmo ammo = IAmmo.getIAmmoOrNull(stack);
        if (ammo != null) {
            ResourceLocation id = ammo.getAmmoId(stack);
            if (id != null) {
                return id.getNamespace();
            }
        }
        Holder<Item> holder = stack.getItemHolder();
        if (holder instanceof Holder.Reference<?> reference) {
            return reference.key().location().getNamespace();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 建立枪包列表
    // ------------------------------------------------------------------

    private void createFilters() {
        if (!GunPackIndex.isLoaded()) {
            GunPackIndex.load(FMLPaths.GAMEDIR.get().resolve(PACK_ROOT));
        }

        this.filters.clear();
        for (String packName : GunPackIndex.getPackNames()) {
            PackFilter filter = new PackFilter(packName, GunPackIndex.getNamespacesOf(packName));
            filter.setSelected(Boolean.TRUE.equals(this.selection.get(packName)));
            this.filters.add(filter);
        }

        this.filterableTabs.clear();
        this.filterableTabs.addAll(this.getTaczTabs());
        this.filterableTabs.addAll(this.detectFilterableTabs());
    }

    /** 遍历全部创造页签，找出含有已装枪包物品的那些。 */
    private Set<CreativeModeTab> detectFilterableTabs() {
        Set<CreativeModeTab> detected = new LinkedHashSet<>();
        for (CreativeModeTab tab : CreativeModeTabRegistry.getSortedCreativeModeTabs()) {
            Collection<ItemStack> items = tab.getDisplayItems();
            if (items == null || items.isEmpty()) {
                continue;
            }
            for (ItemStack stack : items) {
                String namespace = packNamespaceOf(stack);
                if (namespace != null && GunPackIndex.getOwnerOf(namespace) != null) {
                    detected.add(tab);
                    break;
                }
            }
        }
        return detected;
    }

    /** TaCZ 自己的创造页签，作为「哪些页签要挂筛选」的兜底。 */
    private List<CreativeModeTab> getTaczTabs() {
        List<CreativeModeTab> tabs = new ArrayList<>();
        addTab(tabs, ModCreativeTabs.OTHER_TAB);
        addTab(tabs, ModCreativeTabs.AMMO_TAB);
        addTab(tabs, ModCreativeTabs.ATTACHMENT_SCOPE_TAB);
        addTab(tabs, ModCreativeTabs.ATTACHMENT_MUZZLE_TAB);
        addTab(tabs, ModCreativeTabs.ATTACHMENT_STOCK_TAB);
        addTab(tabs, ModCreativeTabs.ATTACHMENT_GRIP_TAB);
        addTab(tabs, ModCreativeTabs.ATTACHMENT_EXTENDED_MAG_TAB);
        addTab(tabs, ModCreativeTabs.ATTACHMENT_LASER_TAB);
        addTab(tabs, ModCreativeTabs.GUN_PISTOL_TAB);
        addTab(tabs, ModCreativeTabs.GUN_SNIPER_TAB);
        addTab(tabs, ModCreativeTabs.GUN_RIFLE_TAB);
        addTab(tabs, ModCreativeTabs.GUN_SHOTGUN_TAB);
        addTab(tabs, ModCreativeTabs.GUN_SMG_TAB);
        addTab(tabs, ModCreativeTabs.GUN_RPG_TAB);
        addTab(tabs, ModCreativeTabs.GUN_MG_TAB);
        return tabs;
    }

    private static void addTab(List<CreativeModeTab> tabs, @Nullable RegistryObject<CreativeModeTab> holder) {
        if (holder == null || !holder.isPresent()) {
            return;
        }
        CreativeModeTab tab = holder.get();
        if (tab != null && !tabs.contains(tab)) {
            tabs.add(tab);
        }
    }
}
