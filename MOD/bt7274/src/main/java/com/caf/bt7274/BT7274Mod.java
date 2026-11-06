package com.caf.bt7274;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(BT7274Mod.ID)
public final class BT7274Mod {
    public static final String ID = "bt7274";
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ID);
    public static final DeferredHolder<EntityType<?>, EntityType<TitanEntity>> TITAN = ENTITIES.register("bt7274",
        () -> EntityType.Builder.of(TitanEntity::new, MobCategory.MISC).sized(3.048F, 5.969F)
            .eyeHeight(4.699F).clientTrackingRange(16).updateInterval(1).fireImmune().build("bt7274:bt7274"));
    public static final DeferredHolder<EntityType<?>, EntityType<TitanRocket>> ROCKET = ENTITIES.register("titan_rocket",
        () -> EntityType.Builder.<TitanRocket>of(TitanRocket::new,MobCategory.MISC).sized(.35F,.35F)
            .clientTrackingRange(16).updateInterval(1).fireImmune().build("bt7274:titan_rocket"));
    public static final DeferredHolder<Item, Item> BEACON = ITEMS.register("titanfall_beacon",
        () -> new TitanBeaconItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant()));
    public static final DeferredHolder<Item, Item> BATTERY = ITEMS.register("titan_battery",
        () -> new Item(new Item.Properties().stacksTo(16).rarity(Rarity.RARE)));
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("vanguard",
        () -> CreativeModeTab.builder().title(Component.translatable("itemGroup.bt7274"))
            .icon(() -> new ItemStack(BEACON.get())).withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
            .displayItems((p,o) -> { o.accept(BEACON.get()); o.accept(BATTERY.get()); }).build());

    public BT7274Mod(IEventBus bus) {
        ENTITIES.register(bus); ITEMS.register(bus); TABS.register(bus);
        bus.addListener(this::attributes);
        bus.addListener(TitanInputPayload::register);
        NeoForge.EVENT_BUS.addListener(this::commands);
        NeoForge.EVENT_BUS.addListener(this::logout);
        NeoForge.EVENT_BUS.addListener(this::protectPilot);
        NeoForge.EVENT_BUS.addListener(this::dismount);
    }
    public static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(ID,path); }
    private void attributes(EntityAttributeCreationEvent e) { e.put(TITAN.get(), TitanEntity.createAttributes().build()); }
    private void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity().getVehicle() instanceof TitanEntity titan) titan.clearInput();
    }
    private void protectPilot(LivingIncomingDamageEvent e){
        if(!(e.getEntity() instanceof ServerPlayer p)||!(p.getVehicle() instanceof TitanEntity titan)||!titan.isAlive())return;
        if(e.getSource().is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY))return;
        e.setCanceled(true);
        if(!e.getSource().is(net.minecraft.world.damagesource.DamageTypes.FALL)
            &&!e.getSource().is(net.minecraft.world.damagesource.DamageTypes.IN_WALL)
            &&!e.getSource().is(net.minecraft.world.damagesource.DamageTypes.DROWN)
            &&!titan.tookDamageThisTick())titan.hurt(e.getSource(),e.getOriginalAmount());
    }
    private void dismount(net.neoforged.neoforge.event.entity.EntityMountEvent e){
        if(e.getLevel().isClientSide||!e.isDismounting()||!(e.getEntityBeingMounted() instanceof TitanEntity titan)||titan.isIntentionalDismount())return;
        if(!(e.getEntityMounting() instanceof net.minecraft.world.entity.player.Player player)||!player.getUUID().equals(titan.getOwnerUUID()))return;
        e.setCanceled(true);
        if(!e.getLevel().isClientSide)titan.requestDisembark();
    }
    private void commands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("bt7274")
            .then(Commands.literal("help").executes(ctx -> {
                ctx.getSource().sendSuccess(() -> Component.translatable("message.bt7274.help"), false); return 1;
            }))
            .then(Commands.literal("beacon").requires(s -> s.hasPermission(2)).executes(ctx -> {
                ctx.getSource().getPlayerOrException().getInventory().add(new ItemStack(BEACON.get()));return 1;
            }))
            .then(Commands.literal("summon").requires(s -> s.hasPermission(2)).executes(ctx -> {
                ServerPlayer p=ctx.getSource().getPlayerOrException();
                var target=p.serverLevel().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,p.blockPosition().relative(p.getDirection(),12));
                return TitanDeployment.deploy(p,target)==null?0:1;
            })));
    }
}
