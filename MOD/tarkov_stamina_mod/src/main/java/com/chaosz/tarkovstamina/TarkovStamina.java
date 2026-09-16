package com.chaosz.tarkovstamina;

import com.chaosz.tarkovstamina.backpack.BackpackRegistration;
import com.chaosz.tarkovstamina.condition.*;
import com.chaosz.tarkovstamina.client.HudConfig;
import com.chaosz.tarkovstamina.item.BodyMonitorHandler;
import com.chaosz.tarkovstamina.item.StaminaEntities;
import com.chaosz.tarkovstamina.item.StaminaItems;
import com.chaosz.tarkovstamina.network.StaminaNetwork;
import com.chaosz.tarkovstamina.profession.*;
import com.chaosz.tarkovstamina.stamina.*;
import com.chaosz.tarkovstamina.survival.*;
import com.chaosz.tarkovstamina.tent.TentChannelHandler;
import com.chaosz.tarkovstamina.ui.StatusCommand;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import static com.chaosz.tarkovstamina.backpack.BackpackRegistration.HIKING_BACKPACK;
import static com.chaosz.tarkovstamina.backpack.BackpackRegistration.MILITARY_BACKPACK;
import static com.chaosz.tarkovstamina.backpack.BackpackRegistration.SATCHEL;
import static com.chaosz.tarkovstamina.backpack.BackpackRegistration.SCHOOL_BAG;

@Mod(TarkovStamina.MOD_ID)
public final class TarkovStamina {
    public static final String MOD_ID = "tarkov_stamina";

    // 创造标签页
    private static final DeferredRegister<CreativeModeTab> TAB_REG =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);
    public static final RegistryObject<CreativeModeTab> CAF_TAB = TAB_REG
            .register("tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + MOD_ID))
                    .icon(() -> new ItemStack(StaminaItems.SPECIAL_STRENGTH_INJECTION.get()))
                    .displayItems((params, output) -> {
                        // 体力系统物品
                        output.accept(StaminaItems.SPECIAL_STRENGTH_INJECTION.get());
                        output.accept(StaminaItems.BODY_MONITOR.get());
                        output.accept(StaminaItems.SHIT.get());
                        output.accept(StaminaItems.LAPTOP.get());
                        // 背包
                        output.accept(SATCHEL.get());
                        output.accept(SCHOOL_BAG.get());
                        output.accept(HIKING_BACKPACK.get());
                        output.accept(MILITARY_BACKPACK.get());
                    })
                    .build());

    public TarkovStamina(FMLJavaModLoadingContext context) {
        IEventBus modBus = context.getModEventBus();
        IEventBus forgeBus = MinecraftForge.EVENT_BUS;

        // ── 网络 ──
        StaminaNetwork.register();

        // ── 物品注册 ──
        StaminaItems.REGISTRY.register(modBus);

        // ── 实体注册 ──
        StaminaEntities.REGISTRY.register(modBus);

        // ── 创造标签页 ──
        TAB_REG.register(modBus);

        // ── 体力核心 ──
        forgeBus.register(StaminaSystem.class);
        forgeBus.register(ExerciseSystem.class);
        forgeBus.register(WeightSystem.class);
        forgeBus.register(InjectionSystem.class);

        // ── 状态系统 ──
        forgeBus.register(DepressionSystem.class);
        forgeBus.register(DiseaseSystem.class);
        forgeBus.register(PanicSystem.class);

        // ── 生存系统 ──
        forgeBus.register(PoopSystem.class);
        forgeBus.register(ShitballThrowHandler.class);

        // ── 职业系统 ──
        forgeBus.register(ProfessionSystem.class);
        forgeBus.register(FishingSystem.class);
        forgeBus.register(SkillTerminalHandler.class);

        // ── 命令 ──
        forgeBus.register(StatusCommand.class);

        // ── 物品交互 ──
        forgeBus.register(StaminaItems.class);
        forgeBus.register(BodyMonitorHandler.class);

        // ── 配置 ──
        context.registerConfig(ModConfig.Type.COMMON, StaminaConfig.SPEC);
        context.registerConfig(ModConfig.Type.CLIENT, HudConfig.SPEC, "tarkov_stamina_hud-client.toml");

        // ═══════════════════════════════════════════════════════════════
        //  CAF 核心整合模块
        // ═══════════════════════════════════════════════════════════════

        // ── 军用背包（namespace: caf）──
        BackpackRegistration.register(modBus);

        // ── 帐篷交互读条（TentChannelHandler 自带 @Mod.EventBusSubscriber(Bus.FORGE) 自动注册）──
    }
}
