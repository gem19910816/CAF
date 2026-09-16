package com.chaosz.tarkovstamina.backpack.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.client.ICurioRenderer;

/**
 * 挎包渲染器：斜挎在身体侧面（右髋），而不是像背包一样贴在背上。
 */
public class SatchelCurioRenderer implements ICurioRenderer {

    @Override
    public <T extends LivingEntity, M extends EntityModel<T>> void render(
            ItemStack stack, SlotContext slotContext, PoseStack poseStack,
            RenderLayerParent<T, M> renderLayerParent, MultiBufferSource buffer,
            int light, float limbSwing, float limbSwingAmount, float partialTicks,
            float ageInTicks, float netHeadYaw, float headPitch) {

        poseStack.pushPose();

        LivingEntity entity = slotContext.entity();

        // 跟随身体骨骼
        EntityModel<?> model = renderLayerParent.getModel();
        if (model instanceof HumanoidModel<?> humanoidModel) {
            humanoidModel.body.translateAndRotate(poseStack);
        }

        // Move the bag from the back to the right hip and shrink it a bit.
        poseStack.translate(-0.28D, 0.55D, 0.04D);
        poseStack.mulPose(Axis.XP.rotationDegrees(180.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(-8.0F));
        poseStack.scale(0.8F, 0.8F, 0.8F);
        Minecraft.getInstance().getItemRenderer().renderStatic(
                entity, stack, ItemDisplayContext.NONE, false,
                poseStack, buffer, entity.level(),
                light, OverlayTexture.NO_OVERLAY, entity.getId());

        poseStack.popPose();
    }
}
