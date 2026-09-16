package com.chaosz.gunpackfilter;

import net.minecraftforge.fml.common.Mod;

/**
 * TaCZ Gunpack Filter — 只保留「在创造模式物品栏里按枪包区分枪械」这一个功能。
 *
 * <p>本模组是 Hamster-Baron/TACZ-Arcana (GPL-3.0) 中 CreativeTabManager 相关实现的
 * 精简剥离版本，仅保留创造模式页签的枪包筛选按钮，其余技能 / 渲染 / 网络功能全部移除。</p>
 *
 * <p>模组本身不注册任何内容，全部逻辑都在客户端事件与 Mixin 中，服务端不会加载任何客户端类。</p>
 */
@Mod(GunPackFilterMod.MODID)
public class GunPackFilterMod {

    public static final String MODID = "gunpackfilter";

    public GunPackFilterMod() {
        // 无需注册任何东西，客户端逻辑由 GunPackFilterClientEvents 处理
    }
}
