package com.chaosz.gunpackfilter.client;

import com.chaosz.gunpackfilter.GunPackFilterMod;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 筛选按钮列上方 / 下方的翻页箭头按钮（对应 Arcana 的 IconButton）。
 *
 * <p>贴图布局跟 Arcana 一致：256×256 的 {@code textures/gui/icons.png}，
 * 上箭头在 (0,0)，下箭头在 (16,0)。</p>
 */
public class ScrollIconButton extends Button {

    private static final ResourceLocation ICONS =
            new ResourceLocation(GunPackFilterMod.MODID, "textures/gui/icons.png");

    private final int iconU;

    public ScrollIconButton(int x, int y, int iconU, Component tooltip, OnPress onPress) {
        super(x, y, 20, 20, Component.empty(), onPress, DEFAULT_NARRATION);
        this.iconU = iconU;
        this.setTooltip(Tooltip.create(tooltip));
    }

    @Override
    protected ClientTooltipPositioner createTooltipPositioner() {
        return DefaultTooltipPositioner.INSTANCE;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(graphics, mouseX, mouseY, partialTick);

        float[] previous = null;
        if (!this.active) {
            previous = RenderSystem.getShaderColor().clone();
            RenderSystem.setShaderColor(0.5F, 0.5F, 0.5F, 1.0F);
        }
        graphics.blit(ICONS, this.getX() + 2, this.getY() + 2, this.iconU, 0, 16, 16);
        if (previous != null) {
            RenderSystem.setShaderColor(previous[0], previous[1], previous[2], previous[3]);
        }
    }
}
