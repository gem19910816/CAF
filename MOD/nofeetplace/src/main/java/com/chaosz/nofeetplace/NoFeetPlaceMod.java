package com.chaosz.nofeetplace;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

@Mod(NoFeetPlaceMod.MODID)
public class NoFeetPlaceMod {

    public static final String MODID = "nofeetplace";

    public NoFeetPlaceMod() {
        // 服务端配置：服务端权威，Forge 会自动同步给客户端供预测使用
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, NoFeetPlaceConfig.SPEC, "nofeetplace-server.toml");

        // 放置拦截
        MinecraftForge.EVENT_BUS.register(PlacementGuard.class);
        // 提示冷却的清理
        MinecraftForge.EVENT_BUS.register(Feedback.class);    }
}
