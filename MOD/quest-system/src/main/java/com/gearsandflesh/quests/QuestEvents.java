package com.gearsandflesh.quests;

import com.gearsandflesh.quests.data.QuestSavedData;
import com.gearsandflesh.quests.network.OpenQuestS2C;
import com.gearsandflesh.quests.network.QuestNetwork;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = QuestConstants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class QuestEvents {
    private static final org.slf4j.Logger LOGGER =
            com.mojang.logging.LogUtils.getLogger();

    /** Last known counts of quest-relevant items per player, for diff-based collection progress. */
    private static final Map<UUID, Map<String, Integer>> INVENTORY_SNAPSHOTS = new HashMap<>();
    private static int tickCounter;

    private QuestEvents() {
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("quests")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    QuestNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenQuestS2C(false));
                    return 1;
                })
                .then(Commands.literal("edit")
                        .requires(source -> source.hasPermission(QuestConstants.ADMIN_PERMISSION_LEVEL))
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            QuestNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenQuestS2C(true));
                            return 1;
                        }))
                .then(Commands.literal("export")
                        .requires(source -> source.hasPermission(QuestConstants.ADMIN_PERMISSION_LEVEL))
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            QuestNetwork.sendNotice(player, QuestStorage.exportToFile(player.server));
                            return 1;
                        }))
                .then(Commands.literal("import")
                        .requires(source -> source.hasPermission(QuestConstants.ADMIN_PERMISSION_LEVEL))
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            QuestNetwork.sendNotice(player, QuestStorage.importFromFile(player.server));
                            return 1;
                        }))
                .then(Commands.literal("view")
                        .requires(source -> source.hasPermission(QuestConstants.ADMIN_PERMISSION_LEVEL))
                        .then(net.minecraft.commands.Commands.argument("player", EntityArgument.player())
                                .executes(context -> {
                                    ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                    QuestSavedData data = QuestSavedData.get(target.server);
                                    context.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                                            "=== " + target.getGameProfile().getName() + " 的任务进度 ==="), false);
                                    for (var definition : data.definitions(true)) {
                                        for (var group : definition.types()) {
                                            var state = data.state(target.getUUID(),
                                                    QuestService.stateKey(definition.id(), group),
                                                    QuestService.periodFor(group), definition.revision());
                                            String line = "• " + definition.title() + " [" + group.label() + "] "
                                                    + state.progress() + "/" + definition.target()
                                                    + (state.claimed() ? " 已领取" : "");
                                            context.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(line), false);
                                        }
                                    }
                                    return 1;
                                })))
                .then(Commands.literal("reset")
                        .requires(source -> source.hasPermission(QuestConstants.ADMIN_PERMISSION_LEVEL))
                        .then(net.minecraft.commands.Commands.argument("player", EntityArgument.player())
                                .then(net.minecraft.commands.Commands.argument("quest", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(context -> {
                                            ServerPlayer admin = context.getSource().getPlayerOrException();
                                            ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                            String questId = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "quest");
                                            QuestNetwork.sendNotice(admin, QuestService.resetPlayer(admin, target, questId));
                                            return 1;
                                        }))))
                .then(Commands.literal("progress")
                        .requires(source -> source.hasPermission(QuestConstants.ADMIN_PERMISSION_LEVEL))
                        .then(net.minecraft.commands.Commands.argument("player", EntityArgument.player())
                                .then(net.minecraft.commands.Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .then(net.minecraft.commands.Commands.argument("amount", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 1_000_000))
                                                .executes(context -> {
                                                    ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                                    QuestService.recordProgress(target, com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id"), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "amount"));
                                                    return 1;
                                                })))))
        );
    }

    @SubscribeEvent
    public static void serverStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        // Always sync definitions from the config file on startup so pack updates apply.
        QuestSavedData data = QuestSavedData.get(server);
        if (Files.exists(QuestStorage.configPath())) {
            var result = QuestStorage.importFromFile(server);
            LOGGER.info("[任务] 从配置加载任务列表：{}", result.message());
        }
    }

    /**
     * Collection progress via periodic inventory diffing, so items gained by any means
     * (ground pickup, chests, crafting, trading) all count — not just ground pickup.
     */
    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++tickCounter % 10 != 0) {
            return;
        }
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        TrackedGoals tracked = trackedGoals(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!tracked.collectItems.isEmpty()) {
                Map<String, Integer> counts = countTrackedItems(player, tracked.collectItems);
                Map<String, Integer> previous = INVENTORY_SNAPSHOTS.get(player.getUUID());
                if (previous != null) {
                    for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                        int delta = entry.getValue() - previous.getOrDefault(entry.getKey(), 0);
                        if (delta > 0) {
                            QuestService.recordCollectedDelta(player, entry.getKey(), delta);
                        }
                    }
                }
                INVENTORY_SNAPSHOTS.put(player.getUUID(), counts);
            }
            checkExploration(player, tracked);
        }
    }

    /** Fires explore progress when a player stands inside a tracked biome or structure. */
    private static void checkExploration(ServerPlayer player, TrackedGoals tracked) {
        if (tracked.biomes.isEmpty() && tracked.structures.isEmpty()) {
            return;
        }
        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        var position = player.blockPosition();
        level.getBiome(position).unwrapKey().ifPresent(biomeKey -> {
            String id = biomeKey.location().toString();
            if (tracked.biomes.contains(id)) {
                QuestService.recordExplore(player, com.gearsandflesh.quests.data.QuestGoalType.BIOME, id);
            }
        });
        if (!tracked.structures.isEmpty()) {
            var structureRegistry = level.registryAccess()
                    .registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
            for (var structure : level.structureManager().getAllStructuresAt(position).keySet()) {
                var key = structureRegistry.getKey(structure);
                if (key != null && tracked.structures.contains(key.toString())) {
                    QuestService.recordExplore(player, com.gearsandflesh.quests.data.QuestGoalType.STRUCTURE, key.toString());
                }
            }
        }
    }

    private record TrackedGoals(Set<String> collectItems, Set<String> biomes, Set<String> structures) {
    }

    private static TrackedGoals trackedGoals(MinecraftServer server) {
        Set<String> collectItems = new HashSet<>();
        Set<String> biomes = new HashSet<>();
        Set<String> structures = new HashSet<>();
        for (var definition : QuestSavedData.get(server).definitions(false)) {
            switch (definition.goalType()) {
                case COLLECT -> collectItems.addAll(definition.goalIds());
                case BIOME -> biomes.addAll(definition.goalIds());
                case STRUCTURE -> structures.addAll(definition.goalIds());
                default -> {
                }
            }
        }
        return new TrackedGoals(collectItems, biomes, structures);
    }

    private static Map<String, Integer> countTrackedItems(ServerPlayer player, Set<String> tracked) {
        Map<String, Integer> counts = new HashMap<>();
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
            if (tracked.contains(id)) {
                counts.merge(id, stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    @SubscribeEvent
    public static void playerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        INVENTORY_SNAPSHOTS.remove(event.getEntity().getUUID());
        QuestNetwork.clearPlayer(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLivingDeath(net.minecraftforge.event.entity.living.LivingDeathEvent event) {
        if (event.getSource().getEntity() instanceof ServerPlayer player) {
            QuestService.recordKill(player, event.getEntity());
        }
    }
}
