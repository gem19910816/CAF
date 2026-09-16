package com.chaosz.gunpackfilter.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

/**
 * 枪包筛选按钮，外观照搬 Arcana：用原版创造模式页签贴图旋转 90° 画成向左伸出的页签。
 *
 * <p>选中（筛选开启）时用页签的「选中」形态（贴图 y=32，宽 32）；未选中时用普通形态（贴图 y=0，宽 28）。</p>
 */
public class PackFilterButton extends Button {

    private static final ResourceLocation CREATIVE_TABS =
            new ResourceLocation("textures/gui/container/creative_inventory/tabs.png");

    private static final int WIDTH_SELECTED = 32;
    private static final int WIDTH_UNSELECTED = 28;
    private static final int HEIGHT = 26;
    private static final int TEXTURE_X = 26;

    private final PackFilter filter;
    private final Runnable onToggle;

    public PackFilterButton(int x, int y, PackFilter filter, Runnable onToggle) {
        super(x, y, WIDTH_SELECTED, HEIGHT, Component.empty(), button -> {
        }, DEFAULT_NARRATION);
        this.filter = filter;
        this.onToggle = onToggle;
        this.setTooltip(Tooltip.create(filter.getTooltip()));
    }

    public PackFilter getFilter() {
        return filter;
    }

    @Override
    public void onPress() {
        this.filter.setSelected(!this.filter.isSelected());
        this.onToggle.run();
        super.onPress();
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean selected = this.filter.isSelected();
        int width = selected ? WIDTH_SELECTED : WIDTH_UNSELECTED;
        int textureY = selected ? 32 : 0;

        RenderSystem.setShaderTexture(0, CREATIVE_TABS);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, this.alpha);
        drawRotatedTexture(graphics.pose().last().pose(),
                this.getX(), this.getY(), TEXTURE_X, textureY, width, HEIGHT);

        ItemStack icon = this.filter.getIcon();
        if (!icon.isEmpty()) {
            graphics.renderItem(icon, this.getX() + 8, this.getY() + 5);
        }
    }

    /**
     * 把贴图里 width × height 的区域旋转 90° 画到屏幕上（与 Arcana 的 drawRotatedTexture 完全一致）。
     */
    private static void drawRotatedTexture(Matrix4f matrix, int x, int y,
                                           int textureX, int textureY, int width, int height) {
        float scale = 0.00390625F;
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.vertex(matrix, x, y + height, 0.0F)
                .uv((textureX + height) * scale, textureY * scale)
                .endVertex();
        builder.vertex(matrix, x + width, y + height, 0.0F)
                .uv((textureX + height) * scale, (textureY + width) * scale)
                .endVertex();
        builder.vertex(matrix, x + width, y, 0.0F)
                .uv(textureX * scale, (textureY + width) * scale)
                .endVertex();
        builder.vertex(matrix, x, y, 0.0F)
                .uv(textureX * scale, textureY * scale)
                .endVertex();
        BufferUploader.drawWithShader(builder.end());
    }
}
