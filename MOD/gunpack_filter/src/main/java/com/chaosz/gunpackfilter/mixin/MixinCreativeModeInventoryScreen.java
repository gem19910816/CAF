package com.chaosz.gunpackfilter.mixin;

import com.chaosz.gunpackfilter.client.GunPackFilterManager;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/**
 * 在创造模式物品栏重新填充物品列表之后，重新应用枪包筛选。
 */
@Mixin(CreativeModeInventoryScreen.class)
public class MixinCreativeModeInventoryScreen {

    @Inject(method = "selectTab", at = @At("TAIL"))
    private void gunpackfilter$afterSelectTab(CreativeModeTab tab, CallbackInfo ci) {
        GunPackFilterManager.INSTANCE.onTabSelected((CreativeModeInventoryScreen) (Object) this, tab);
    }

    @Inject(method = "refreshCurrentTabContents", at = @At("TAIL"))
    private void gunpackfilter$afterRefreshCurrentTabContents(Collection<ItemStack> items, CallbackInfo ci) {
        GunPackFilterManager.INSTANCE.onItemsRepopulated((CreativeModeInventoryScreen) (Object) this);
    }
}
