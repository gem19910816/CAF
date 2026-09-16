package com.gearsandflesh.quests.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Draws live entity previews and item icons inside GUIs. */
public final class EntityIconRenderer {
    private static final float FILL = 0.9f;
    private static final Map<EntityType<?>, Entity> ENTITY_CACHE = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, ItemStack> ITEM_CACHE = new ConcurrentHashMap<>();

    private EntityIconRenderer() {
    }

    /** (x, y) is the horizontal center / bottom of the icon slot; size is the slot size in pixels. */
    public static void renderEntity(GuiGraphics graphics, EntityType<?> type, int x, int y, int size) {
        Minecraft mc = Minecraft.getInstance();
        if (type == null || mc.level == null) {
            return;
        }
        Entity entity = ENTITY_CACHE.computeIfAbsent(type, t -> {
            try {
                return t.create(mc.level);
            } catch (Exception ignored) {
                return null;
            }
        });
        if (entity == null) {
            return;
        }
        float bounds = Math.max(entity.getBbWidth(), entity.getBbHeight());
        float scale = bounds > 1.0E-4f ? (size * FILL) / bounds : size * FILL;
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        boolean shadowDisabled = false;
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(x, y, 50);
            graphics.pose().scale(scale, -scale, scale);
            dispatcher.setRenderShadow(false);
            shadowDisabled = true;
            RenderSystem.enableDepthTest();
            RenderSystem.runAsFancy(() -> dispatcher.render(entity, 0, 0, 0, 0, 1.0f,
                    graphics.pose(), graphics.bufferSource(), 0xF000F0));
            graphics.bufferSource().endBatch();
        } catch (Exception ignored) {
        } finally {
            if (shadowDisabled) {
                dispatcher.setRenderShadow(true);
            }
            graphics.pose().popPose();
            RenderSystem.disableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        }
    }

    public static ItemStack itemIcon(ResourceLocation id, ItemStack fallback) {
        if (id == null) {
            return fallback;
        }
        return ITEM_CACHE.computeIfAbsent(id, location -> {
            Item item = ForgeRegistries.ITEMS.getValue(location);
            return item == null ? fallback : new ItemStack(item);
        });
    }
}
