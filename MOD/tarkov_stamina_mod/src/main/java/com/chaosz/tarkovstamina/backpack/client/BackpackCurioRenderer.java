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
 * Curios 背部饰品渲染器：使用模型自身的 display 变换定位到背上。
 * 模型文件（末日背包.json）的 thirdperson_righthand 变换决定了背包在背上的最终位置。
 */
public class BackpackCurioRenderer implements ICurioRenderer {

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

        // Curios gives us the body pivot, so render the item as a centered model
        // instead of reusing the hand transform from the Blockbench export.
        poseStack.translate(0.0D, 0.08D, 0.24D);
        // The Blockbench export is upside down at a body pivot. Correct its
        // vertical orientation, then turn the outer face toward the player's back.
        poseStack.mulPose(Axis.XP.rotationDegrees(180.0F));
        poseStack.scale(0.92F, 0.92F, 0.92F);
        Minecraft.getInstance().getItemRenderer().renderStatic(
                entity, stack, ItemDisplayContext.NONE, false,
                poseStack, buffer, entity.level(),
                light, OverlayTexture.NO_OVERLAY, entity.getId());

        poseStack.popPose();
    }
}
