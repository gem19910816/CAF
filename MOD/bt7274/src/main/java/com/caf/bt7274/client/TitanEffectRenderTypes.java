package com.caf.bt7274.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/** Truly unlit, additive, double-sided effects. Textures have premultiplied RGB
 * because the vanilla additive state uses ONE/ONE. Shields retain alpha blend. */
final class TitanEffectRenderTypes extends RenderType {
    private static final Map<ResourceLocation,RenderType> ADDITIVE=new HashMap<>();
    private TitanEffectRenderTypes(){super("bt_effect_state",DefaultVertexFormat.NEW_ENTITY,VertexFormat.Mode.QUADS,1536,false,true,()->{},()->{});}
    static RenderType additive(ResourceLocation texture){
        return ADDITIVE.computeIfAbsent(texture,t->create("bt_unlit_additive",DefaultVertexFormat.NEW_ENTITY,VertexFormat.Mode.QUADS,1536,false,true,
            CompositeState.builder().setShaderState(RENDERTYPE_EYES_SHADER).setTextureState(new TextureStateShard(t,false,false))
                .setTransparencyState(ADDITIVE_TRANSPARENCY).setCullState(NO_CULL).setWriteMaskState(COLOR_WRITE).createCompositeState(false)));
    }
}
