package com.gearsandflesh.market;

import com.gearsandflesh.market.network.MarketNetwork;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(MarketConstants.MOD_ID)
public final class GlobalMarketMod {
    public static final Logger LOGGER = LogUtils.getLogger();

    public GlobalMarketMod() {
        MarketNetwork.register();
    }
}
