package com.fungalmoon;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(FungalMoon.MODID)
public class FungalMoon {

    public static final String MODID = "fungalmoon";
    public static final Logger LOGGER = LogUtils.getLogger();

    public FungalMoon() {
        FMConfig.register();
        LOGGER.info("[FungalMoon] 月球真菌入侵模组已加载");
    }
}
